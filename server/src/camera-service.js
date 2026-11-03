const express = require('express');
const config = require('./config');
const { db } = require('./db');
const { optionalAuth, nowSeconds } = require('./user-service');
const { priorityGate } = require('./priority');
const { fetchDeflockCamerasInBbox } = require('./deflock');
const {
  haversineMeters,
  bboxAround,
  bboxAroundPoints,
  camerasAlongRoute,
  camerasWithinRadius,
  listUserCamerasInBbox,
  straightLineRoute,
  routeDistanceMeters,
} = require('./routing');

const router = express.Router();
const routeAliasRouter = express.Router();

function numberOrNull(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function readCoordinate(req, name) {
  return numberOrNull(req.query[name] ?? req.body?.[name]);
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function parseBbox(source) {
  const minLat = numberOrNull(source.minLat ?? source.min_lat);
  const maxLat = numberOrNull(source.maxLat ?? source.max_lat);
  const minLng = numberOrNull(source.minLng ?? source.min_lng ?? source.minLon);
  const maxLng = numberOrNull(source.maxLng ?? source.max_lng ?? source.maxLon);
  if ([minLat, maxLat, minLng, maxLng].some((value) => value == null)) return null;
  if (minLat >= maxLat || minLng >= maxLng) return null;
  if (minLat < -85 || maxLat > 85 || minLng < -180 || maxLng > 180) return null;
  return { minLat, maxLat, minLng, maxLng };
}

function entitlementLimits(req) {
  const entitlement = req.entitlement || {};
  return {
    tier: entitlement.tier === 'premium' ? 'premium' : 'free',
    maxRadiusMeters: Number(entitlement.maxRadiusMeters || config.freeMaxRadiusMeters),
    visibleCameras: entitlement.visibleCameras == null ? null : Number(entitlement.visibleCameras),
    regionMaxCameras: Number(entitlement.regionMaxCameras || config.freeRegionMaxCameras),
    adsRequired: entitlement.adsRequired !== false,
  };
}

function dedupe(cameras) {
  const seen = new Set();
  const output = [];
  for (const camera of cameras) {
    if (camera.latitude == null || camera.longitude == null) continue;
    const key = `${camera.id || ''}|${camera.latitude.toFixed(5)}|${camera.longitude.toFixed(5)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    output.push(compactCamera(camera));
  }
  return output;
}

// Drop null/undefined fields before serialization. This is a cheap, safe way
// to cut payload size without changing the wire schema.
function compactCamera(camera) {
  const output = {};
  for (const [key, value] of Object.entries(camera)) {
    if (value !== null && value !== undefined) output[key] = value;
  }
  return output;
}

function bboxKey(bbox) {
  return [bbox.minLat, bbox.maxLat, bbox.minLng, bbox.maxLng]
    .map((value) => Number(value).toFixed(4))
    .join(':');
}

function bboxSpanMeters(bbox) {
  const midLat = (bbox.minLat + bbox.maxLat) / 2;
  const latMeters = (bbox.maxLat - bbox.minLat) * 111_320;
  const lngMeters = (bbox.maxLng - bbox.minLng)
    * 111_320
    * Math.max(Math.cos((midLat * Math.PI) / 180), 0.01);
  return Math.max(latMeters, lngMeters);
}

// A free client can only ask for the local area it is entitled to. Without
// this, a modified client could page through a whole country 250 cameras at a
// time and rebuild the paid dataset.
function guardRegionSize(area, limits) {
  if (limits.tier === 'premium') return null;
  const maxSpan = limits.maxRadiusMeters * 2.5 + 5_000;
  if (bboxSpanMeters(area) <= maxSpan) return null;
  const error = new Error(
    'Free requests are limited to your local area. Upgrade to Premium for a wider region.',
  );
  error.code = 'REGION_TOO_LARGE';
  error.status = 413;
  return error;
}

// Coalesce identical region reads. Under load this turns N upstream tile reads
// and N normalizations into one, which is the single biggest CPU saving here.
const inflightRegions = new Map();
const MAX_INFLIGHT_REGIONS = 64;

async function loadCamerasInBboxUncached(bbox) {
  const [deflock, user] = await Promise.all([
    fetchDeflockCamerasInBbox(bbox.minLat, bbox.maxLat, bbox.minLng, bbox.maxLng),
    Promise.resolve(listUserCamerasInBbox(bbox.minLat, bbox.maxLat, bbox.minLng, bbox.maxLng)),
  ]);
  return dedupe([...user, ...deflock]);
}

async function loadCamerasInBbox(bbox) {
  const key = bboxKey(bbox);
  const existing = inflightRegions.get(key);
  if (existing) return existing;
  if (inflightRegions.size >= MAX_INFLIGHT_REGIONS) {
    return loadCamerasInBboxUncached(bbox);
  }
  const promise = loadCamerasInBboxUncached(bbox).finally(() => {
    inflightRegions.delete(key);
  });
  inflightRegions.set(key, promise);
  return promise;
}

function setRegionCacheHeaders(res) {
  // Clients keep a region for two minutes and may revalidate in the
  // background. Vary keeps per-user entitlement slices out of shared caches.
  res.set('Cache-Control', 'private, max-age=120, stale-while-revalidate=300');
  res.set('Vary', 'Authorization, Accept-Encoding');
}

function limitCameras(cameras, limits, { applyVisible = true } = {}) {
  const regionMax = limits.regionMaxCameras;
  const visible = limits.visibleCameras;
  const cap = applyVisible && visible != null ? Math.min(visible, regionMax) : regionMax;
  const capped = cameras.slice(0, cap);
  return {
    cameras: capped,
    totalAvailable: cameras.length,
    truncated: capped.length < cameras.length,
  };
}

function tierMetadata(limits, extra = {}) {
  return {
    tier: limits.tier,
    maxRadiusMeters: limits.maxRadiusMeters,
    visibleCameras: limits.visibleCameras,
    adsRequired: limits.adsRequired,
    ...extra,
  };
}

// Region endpoint: the app asks for a tile/bbox once, then filters locally by
// radius and route. The server only enforces the per-tier ceiling.
router.get('/region', optionalAuth, async (req, res, next) => {
  const limits = entitlementLimits(req);
  const bbox = parseBbox(req.query);
  const latitude = readCoordinate(req, 'lat');
  const longitude = readCoordinate(req, 'lng');
  const requestedRadius = numberOrNull(req.query.radius) ?? limits.maxRadiusMeters;
  const radius = clamp(requestedRadius, 100, limits.maxRadiusMeters);

  let area = bbox;
  if (!area && latitude != null && longitude != null) {
    area = bboxAround(latitude, longitude, radius);
  }
  if (!area) {
    return res.status(400).json({
      status: 'error',
      message: 'Provide minLat/maxLat/minLng/maxLng or lat/lng.',
    });
  }

  const sizeError = guardRegionSize(area, limits);
  if (sizeError) {
    return res.status(413).json({
      status: 'error',
      message: sizeError.message,
      code: sizeError.code,
    });
  }

  try {
    const result = await priorityGate.run(limits.tier, async () => {
      const cameras = await loadCamerasInBbox(area);
      const sorted = latitude != null && longitude != null
        ? camerasWithinRadius(cameras, latitude, longitude, Number.MAX_SAFE_INTEGER)
        : cameras;
      return limitCameras(sorted, limits, { applyVisible: false });
    });

    setRegionCacheHeaders(res);
    return res.json({
      status: 'ok',
      source: 'HYBRID',
      bbox: area,
      radiusMeters: radius,
      ...tierMetadata(limits, {
        cameras: result.cameras,
        totalAvailable: result.totalAvailable,
        truncated: result.truncated,
      }),
      generatedAt: nowSeconds(),
    });
  } catch (error) {
    if (error.code === 'REGION_TOO_LARGE') {
      return res.status(413).json({ status: 'error', message: error.message, code: error.code });
    }
    return next(error);
  }
});

// Nearby endpoint kept for the existing app and compatibility. It clamps the
// requested radius to the caller's entitlement instead of trusting the client.
router.get('/nearby', optionalAuth, async (req, res, next) => {
  const limits = entitlementLimits(req);
  const latitude = readCoordinate(req, 'lat');
  const longitude = readCoordinate(req, 'lng');
  const requestedRadius = numberOrNull(req.query.radius) ?? 500;
  if (latitude == null || longitude == null) {
    return res.status(400).json({ status: 'error', message: 'lat and lng are required.' });
  }
  const radius = clamp(requestedRadius, 50, limits.maxRadiusMeters);
  const bbox = bboxAround(latitude, longitude, radius);

  try {
    const result = await priorityGate.run(limits.tier, async () => {
      const cameras = await loadCamerasInBbox(bbox);
      const within = camerasWithinRadius(cameras, latitude, longitude, radius);
      return limitCameras(within, limits);
    });

    setRegionCacheHeaders(res);
    return res.json({
      status: 'ok',
      source: 'HYBRID',
      bbox,
      radiusMeters: radius,
      ...tierMetadata(limits, {
        cameras: result.cameras,
        totalAvailable: result.totalAvailable,
        truncated: result.truncated,
      }),
      generatedAt: nowSeconds(),
    });
  } catch (error) {
    if (error.code === 'REGION_TOO_LARGE') {
      return res.status(413).json({ status: 'error', message: error.message, code: error.code });
    }
    return next(error);
  }
});

function readRoutePoints(body) {
  if (Array.isArray(body?.route) && body.route.length >= 2) {
    return body.route
      .map((point) => ({
        latitude: numberOrNull(point.latitude ?? point.lat),
        longitude: numberOrNull(point.longitude ?? point.lng ?? point.lon),
      }))
      .filter((point) => point.latitude != null && point.longitude != null);
  }
  const originLat = numberOrNull(body?.originLatitude ?? body?.origin?.latitude);
  const originLng = numberOrNull(body?.originLongitude ?? body?.origin?.longitude);
  const destLat = numberOrNull(body?.destinationLatitude ?? body?.destination?.latitude);
  const destLng = numberOrNull(body?.destinationLongitude ?? body?.destination?.longitude);
  if ([originLat, originLng, destLat, destLng].some((value) => value == null)) return null;
  return straightLineRoute(
    { latitude: originLat, longitude: originLng },
    { latitude: destLat, longitude: destLng },
  );
}

// Route endpoint: the app can send its Google Directions polyline so the
// server does not need to compute road geometry. We only index cameras along it.
async function handleRoute(req, res, next) {
  const limits = entitlementLimits(req);
  const route = readRoutePoints(req.body);
  if (!route || route.length < 2) {
    return res.status(400).json({
      status: 'error',
      message: 'Provide route[] or origin/destination coordinates.',
    });
  }
  const requestedCorridor = numberOrNull(req.body?.corridorMeters) ?? 250;
  const corridor = clamp(requestedCorridor, 25, 2000);
  const bbox = bboxAroundPoints(route, corridor);

  try {
    const result = await priorityGate.run(limits.tier, async () => {
      const cameras = await loadCamerasInBbox(bbox);
      const along = camerasAlongRoute(cameras, route, corridor);
      return limitCameras(along, limits);
    });

    setRegionCacheHeaders(res);
    return res.json({
      status: 'ok',
      source: 'HYBRID',
      bbox,
      corridorMeters: corridor,
      routeDistanceMeters: Math.round(routeDistanceMeters(route)),
      ...tierMetadata(limits, {
        cameras: result.cameras,
        totalAvailable: result.totalAvailable,
        truncated: result.truncated,
      }),
      generatedAt: nowSeconds(),
    });
  } catch (error) {
    if (error.code === 'REGION_TOO_LARGE') {
      return res.status(413).json({ status: 'error', message: error.message, code: error.code });
    }
    return next(error);
  }
}

router.post('/route', optionalAuth, handleRoute);
routeAliasRouter.post('/predict', optionalAuth, handleRoute);
routeAliasRouter.post('/avoid', optionalAuth, handleRoute);

function validateReport(body) {
  const latitude = numberOrNull(body?.latitude);
  const longitude = numberOrNull(body?.longitude);
  if (latitude == null || longitude == null) return { error: 'latitude and longitude are required.' };
  if (latitude < -85 || latitude > 85 || longitude < -180 || longitude > 180) {
    return { error: 'Coordinates are out of range.' };
  }
  const direction = numberOrNull(body?.directionDegrees);
  if (direction != null && (direction < 0 || direction > 360)) {
    return { error: 'directionDegrees must be between 0 and 360.' };
  }
  return {
    value: {
      latitude,
      longitude,
      source: String(body?.source || 'USER').slice(0, 40),
      note: body?.note ? String(body.note).slice(0, 500) : null,
      description: body?.description ? String(body.description).slice(0, 1000) : null,
      directionDegrees: direction,
      viewDirection: body?.viewDirection ? String(body.viewDirection).slice(0, 40) : null,
      manufacturer: body?.manufacturer ? String(body.manufacturer).slice(0, 80) : null,
      operator: body?.operator ? String(body.operator).slice(0, 80) : null,
    },
  };
}

// User submissions are queued for moderation. They are visible immediately as
// PENDING so the reporter sees their own contribution, but excluded from
// REJECTED.
router.post('/reports', optionalAuth, (req, res) => {
  const { error, value } = validateReport(req.body);
  if (error) return res.status(400).json({ status: 'error', message: error });

  const userId = req.user ? req.user.id : null;
  const deviceId = String(req.body?.deviceId || 'anonymous-device').slice(0, 120);
  const info = db
    .prepare(
      `INSERT INTO user_cameras
        (user_id, device_id, latitude, longitude, source, note, description,
         direction_degrees, view_direction, manufacturer, operator, status)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')`,
    )
    .run(
      userId,
      deviceId,
      value.latitude,
      value.longitude,
      value.source,
      value.note,
      value.description,
      value.directionDegrees,
      value.viewDirection,
      value.manufacturer,
      value.operator,
    );

  return res.status(201).json({
    status: 'ok',
    cameraId: `user-${Number(info.lastInsertRowid)}`,
    moderationStatus: 'PENDING',
  });
});

router.get('/reports/mine', optionalAuth, (req, res) => {
  if (!req.user) {
    return res.status(401).json({ status: 'error', message: 'Authentication required.' });
  }
  const rows = db
    .prepare(
      `SELECT id, latitude, longitude, source, note, description, direction_degrees,
        view_direction, manufacturer, operator, status, created_at
       FROM user_cameras WHERE user_id = ? ORDER BY id DESC LIMIT 200`,
    )
    .all(req.user.id);
  return res.json({
    status: 'ok',
    reports: rows.map((row) => ({
      id: `user-${row.id}`,
      latitude: row.latitude,
      longitude: row.longitude,
      source: row.source,
      note: row.note,
      description: row.description,
      directionDegrees: row.direction_degrees,
      viewDirection: row.view_direction,
      manufacturer: row.manufacturer,
      operator: row.operator,
      status: row.status,
      createdAt: row.created_at,
    })),
  });
});

// Simple distance helper kept for clients that want a server-side sanity check.
router.get('/distance', (req, res) => {
  const fromLat = numberOrNull(req.query.fromLat);
  const fromLng = numberOrNull(req.query.fromLng);
  const toLat = numberOrNull(req.query.toLat);
  const toLng = numberOrNull(req.query.toLng);
  if ([fromLat, fromLng, toLat, toLng].some((value) => value == null)) {
    return res.status(400).json({ status: 'error', message: 'fromLat/fromLng/toLat/toLng are required.' });
  }
  return res.json({
    status: 'ok',
    distanceMeters: haversineMeters(
      { latitude: fromLat, longitude: fromLng },
      { latitude: toLat, longitude: toLng },
    ),
  });
});

// Legacy POST /api/cameras alias used by the current Android app.
router.post('/', optionalAuth, (req, res) => {
  const { error, value } = validateReport(req.body);
  if (error) return res.status(400).json({ status: 'error', message: error });
  const userId = req.user ? req.user.id : null;
  const deviceId = String(req.body?.deviceId || 'anonymous-device').slice(0, 120);
  const info = db
    .prepare(
      `INSERT INTO user_cameras
        (user_id, device_id, latitude, longitude, source, note, description,
         direction_degrees, view_direction, manufacturer, operator, status)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')`,
    )
    .run(
      userId,
      deviceId,
      value.latitude,
      value.longitude,
      value.source,
      value.note,
      value.description,
      value.directionDegrees,
      value.viewDirection,
      value.manufacturer,
      value.operator,
    );
  return res.json({ status: 'ok', cameraId: Number(info.lastInsertRowid) });
});

module.exports = { router, routeAliasRouter, loadCamerasInBbox, entitlementLimits };
