const express = require('express');
const cors = require('cors');
const { db } = require('./db');
const { fetchDeflockCamerasInBbox } = require('./deflock');
const { planRouteAvoiding, listUserCamerasWithinRadius, haversineMeters } = require('./routing');
const actionsApi = require('./actions');

const app = express();
app.use(cors());
app.use(express.json());

const PORT = Number(process.env.PORT || 8080);
const DEVICE_ID = process.env.DEVICE_ID || 'anonymous-device';

function bboxAround(latitude, longitude, radiusMeters) {
  const latDelta = (radiusMeters / 111_320) + 1;
  const lngDelta = (radiusMeters / (111_320 * Math.max(Math.cos((Number(latitude) * Math.PI) / 180), 0.01))) + 1;
  return {
    minLat: Number(latitude) - latDelta,
    maxLat: Number(latitude) + latDelta,
    minLng: Number(longitude) - lngDelta,
    maxLng: Number(longitude) + lngDelta,
  };
}

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', service: 'F-Flock-Cam hybrid server' });
});

app.get('/api/cameras/nearby', async (req, res) => {
  const latitude = Number(req.query.lat);
  const longitude = Number(req.query.lng);
  const radius = Number(req.query.radius || 500);
  if (Number.isNaN(latitude) || Number.isNaN(longitude)) {
    return res.status(400).json({ status: 'error', message: 'lat and lng are required' });
  }

  const bbox = bboxAround(latitude, longitude, radius);
  const deflockCameras = await fetchDeflockCamerasInBbox(bbox.minLat, bbox.maxLat, bbox.minLng, bbox.maxLng);
  const userCameras = listUserCamerasWithinRadius(latitude, longitude, radius);
  const merged = [...userCameras];
  for (const camera of deflockCameras) {
    if (camera.latitude == null || camera.longitude == null) continue;
    const distance = haversineMeters(camera, { latitude, longitude });
    if (distance > radius) continue;
    if (merged.some((existing) => existing.id === camera.id)) continue;
    merged.push({ ...camera, distanceMeters: distance });
  }
  merged.sort((a, b) => (a.distanceMeters ?? Number.MAX_SAFE_INTEGER) - (b.distanceMeters ?? Number.MAX_SAFE_INTEGER));

  return res.json({ cameras: merged, source: 'HYBRID', bbox });
});

app.post('/api/cameras', (req, res) => {
  const { deviceId, latitude, longitude, source, note } = req.body || {};
  if (latitude == null || longitude == null) {
    return res.status(400).json({ status: 'error', message: 'latitude and longitude are required' });
  }
  const info = db
    .prepare('INSERT INTO user_cameras (device_id, latitude, longitude, source, note) VALUES (?, ?, ?, ?, ?)')
    .run(deviceId || DEVICE_ID, latitude, longitude, source || 'USER', note || null);
  return res.json({ status: 'ok', cameraId: Number(info.lastInsertRowid) });
});

app.post('/api/routes/predict', async (req, res) => {
  const { originLatitude, originLongitude, destinationLatitude, destinationLongitude, radiusMeters } = req.body || {};
  const radius = Number(radiusMeters || 500);
  const bbox = bboxAround(originLatitude, destinationLatitude, radius * 2);
  const deflockCameras = await fetchDeflockCamerasInBbox(bbox.minLat, bbox.maxLat, bbox.minLng, bbox.maxLng);
  const userCameras = listUserCamerasWithinRadius(originLatitude, destinationLatitude, radius * 2);
  const avoidCameras = [...userCameras, ...deflockCameras]
    .filter((camera) => camera.latitude != null && camera.longitude != null)
    .slice(0, 50);

  const result = planRouteAvoiding(
    { latitude: originLatitude, longitude: originLongitude },
    { latitude: destinationLatitude, longitude: destinationLongitude },
    avoidCameras,
    { radiusMeters: radius },
  );

  return res.json({ route: result.route, cameras: result.cameras });
});

app.post('/api/routes/avoid', async (req, res) => {
  const { originLatitude, originLongitude, destinationLatitude, destinationLongitude, radiusMeters } = req.body || {};
  const radius = Number(radiusMeters || 500);
  const bbox = bboxAround(originLatitude, destinationLatitude, radius * 2);
  const deflockCameras = await fetchDeflockCamerasInBbox(bbox.minLat, bbox.maxLat, bbox.minLng, bbox.maxLng);
  const userCameras = listUserCamerasWithinRadius(originLatitude, originLongitude, radius * 2);
  const avoidCameras = [...userCameras, ...deflockCameras]
    .filter((camera) => camera.latitude != null && camera.longitude != null)
    .slice(0, 50);

  const result = planRouteAvoiding(
    { latitude: originLatitude, longitude: originLongitude },
    { latitude: destinationLatitude, longitude: destinationLongitude },
    avoidCameras,
    { radiusMeters: radius },
  );

  return res.json({ route: result.route, cameras: result.cameras, avoided: result.avoided });
});

app.get('/api/actions/pending', (req, res) => {
  const deviceId = req.query.deviceId || DEVICE_ID;
  return res.json({ actions: actionsApi.listPending(deviceId) });
});

app.post('/api/actions', (req, res) => {
  const { deviceId, action, source } = req.body || {};
  const created = actionsApi.createPending(deviceId || DEVICE_ID, action, source);
  return res.json({ status: 'QUEUED', actionId: created.id });
});

app.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`F-Flock-Cam server listening on port ${PORT}`);
});

module.exports = app;
