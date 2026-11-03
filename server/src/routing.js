const { db } = require('./db');

function haversineMeters(a, b) {
  const R = 6371000;
  const toRad = (value) => (value * Math.PI) / 180;
  const dLat = toRad(b.latitude - a.latitude);
  const dLng = toRad(b.longitude - a.longitude);
  const lat1 = toRad(a.latitude);
  const lat2 = toRad(b.latitude);
  const h =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
  const c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  return R * c;
}

function isWithinRange(camera, latitude, longitude, radiusMeters) {
  if (camera.latitude == null || camera.longitude == null) return false;
  return haversineMeters(camera, { latitude, longitude }) <= radiusMeters;
}

function bboxAround(latitude, longitude, radiusMeters) {
  const latDelta = radiusMeters / 111_320;
  const lngDelta = radiusMeters / (111_320 * Math.max(Math.cos((Number(latitude) * Math.PI) / 180), 0.01));
  return {
    minLat: Number(latitude) - latDelta,
    maxLat: Number(latitude) + latDelta,
    minLng: Number(longitude) - lngDelta,
    maxLng: Number(longitude) + lngDelta,
  };
}

function bboxAroundPoints(points, radiusMeters) {
  const latitudes = points.map((point) => Number(point.latitude));
  const longitudes = points.map((point) => Number(point.longitude));
  const minLat = Math.min(...latitudes);
  const maxLat = Math.max(...latitudes);
  const midLat = (minLat + maxLat) / 2;
  const latPad = radiusMeters / 111_320;
  const lngPad = radiusMeters / (111_320 * Math.max(Math.cos((midLat * Math.PI) / 180), 0.01));
  return {
    minLat: minLat - latPad,
    maxLat: maxLat + latPad,
    minLng: Math.min(...longitudes) - lngPad,
    maxLng: Math.max(...longitudes) + lngPad,
  };
}

function distancePointToSegmentMeters(latitude, longitude, startLat, startLng, endLat, endLng) {
  const midLat = ((startLat + endLat) / 2) * (Math.PI / 180);
  const metersPerDegreeLat = 111_320;
  const metersPerDegreeLng = 111_320 * Math.max(Math.cos(midLat), 0.01);

  const ax = startLng * metersPerDegreeLng;
  const ay = startLat * metersPerDegreeLat;
  const bx = endLng * metersPerDegreeLng;
  const by = endLat * metersPerDegreeLat;
  const px = longitude * metersPerDegreeLng;
  const py = latitude * metersPerDegreeLat;

  const abx = bx - ax;
  const aby = by - ay;
  const lengthSquared = abx * abx + aby * aby;
  const t = lengthSquared === 0 ? 0 : ((px - ax) * abx + (py - ay) * aby) / lengthSquared;
  const clamped = Math.max(0, Math.min(1, t));
  const dx = px - (ax + clamped * abx);
  const dy = py - (ay + clamped * aby);
  return Math.sqrt(dx * dx + dy * dy);
}

function distanceToRouteMeters(camera, routePoints) {
  if (!Array.isArray(routePoints) || routePoints.length === 0) return Number.POSITIVE_INFINITY;
  if (routePoints.length === 1) {
    return haversineMeters(camera, routePoints[0]);
  }
  let best = Number.POSITIVE_INFINITY;
  for (let i = 1; i < routePoints.length; i += 1) {
    const start = routePoints[i - 1];
    const end = routePoints[i];
    const distance = distancePointToSegmentMeters(
      camera.latitude,
      camera.longitude,
      start.latitude,
      start.longitude,
      end.latitude,
      end.longitude,
    );
    if (distance < best) best = distance;
  }
  return best;
}

function camerasAlongRoute(cameras, routePoints, corridorMeters) {
  if (!Array.isArray(cameras) || !Array.isArray(routePoints) || routePoints.length < 2) return [];
  return cameras
    .filter((camera) => camera.latitude != null && camera.longitude != null)
    .map((camera) => ({
      ...camera,
      distanceFromRouteMeters: distanceToRouteMeters(camera, routePoints),
    }))
    .filter((camera) => camera.distanceFromRouteMeters <= corridorMeters)
    .sort((a, b) => a.distanceFromRouteMeters - b.distanceFromRouteMeters);
}

function camerasWithinRadius(cameras, latitude, longitude, radiusMeters) {
  return cameras
    .filter((camera) => camera.latitude != null && camera.longitude != null)
    .map((camera) => ({
      ...camera,
      distanceMeters: haversineMeters(camera, { latitude, longitude }),
    }))
    .filter((camera) => camera.distanceMeters <= radiusMeters)
    .sort((a, b) => a.distanceMeters - b.distanceMeters);
}

function userCameraRowToCamera(row) {
  return {
    id: `user-${row.id}`,
    latitude: row.latitude,
    longitude: row.longitude,
    name: row.note ? `Reported camera (${row.note})` : 'Reported flock camera',
    source: row.source || 'USER',
    description: row.description || row.note || null,
    directionDegrees: row.direction_degrees != null ? Number(row.direction_degrees) : null,
    viewDirection: row.view_direction || null,
    manufacturer: row.manufacturer || null,
    operator: row.operator || null,
    status: row.status || 'PENDING',
  };
}

function listUserCamerasWithinRadius(latitude, longitude, radiusMeters) {
  const rows = db
    .prepare(
      `SELECT id, latitude, longitude, source, note, description, direction_degrees,
        view_direction, manufacturer, operator, status
       FROM user_cameras
       WHERE latitude IS NOT NULL AND longitude IS NOT NULL AND status != 'REJECTED'`,
    )
    .all();
  return camerasWithinRadius(rows.map(userCameraRowToCamera), latitude, longitude, radiusMeters);
}

function listUserCamerasInBbox(minLat, maxLat, minLng, maxLng) {
  const rows = db
    .prepare(
      `SELECT id, latitude, longitude, source, note, description, direction_degrees,
        view_direction, manufacturer, operator, status
       FROM user_cameras
       WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? AND status != 'REJECTED'`,
    )
    .all(minLat, maxLat, minLng, maxLng);
  return rows.map(userCameraRowToCamera);
}

function straightLineRoute(origin, destination) {
  return [
    { latitude: origin.latitude, longitude: origin.longitude },
    { latitude: destination.latitude, longitude: destination.longitude },
  ];
}

function routeDistanceMeters(routePoints) {
  let total = 0;
  for (let i = 1; i < routePoints.length; i += 1) {
    total += haversineMeters(routePoints[i - 1], routePoints[i]);
  }
  return total;
}

class MinHeap {
  constructor() {
    this.items = [];
  }

  push(item) {
    this.items.push(item);
    this.items.sort((a, b) => a.cost - b.cost);
  }

  pop() {
    return this.items.shift();
  }

  get size() {
    return this.items.length;
  }
}

// Coarse-grid Dijkstra. Cameras are mapped to grid cells so avoidance actually
// blocks the cells a route would otherwise cross.
function planRouteAvoiding(origin, destination, avoidCameras, options = {}) {
  const radiusMeters = options.radiusMeters || 1000;
  const straight = straightLineRoute(origin, destination);
  const alongStraight = camerasAlongRoute(avoidCameras || [], straight, radiusMeters);
  if (!avoidCameras || avoidCameras.length === 0) {
    return { route: straight, cameras: alongStraight, avoided: false };
  }

  const latPad = radiusMeters / 110_000 + 0.01;
  const lngPad = radiusMeters / (111_320 * Math.max(Math.cos((Number(origin.latitude) * Math.PI) / 180), 0.01)) + 0.01;
  const minLat = Math.min(origin.latitude, destination.latitude) - latPad;
  const maxLat = Math.max(origin.latitude, destination.latitude) + latPad;
  const minLng = Math.min(origin.longitude, destination.longitude) - lngPad;
  const maxLng = Math.max(origin.longitude, destination.longitude) + lngPad;

  const cellDeg = 25 / 111_320;
  const cols = Math.max(2, Math.ceil((maxLng - minLng) / cellDeg));
  const rows = Math.max(2, Math.ceil((maxLat - minLat) / cellDeg));

  const key = (row, col) => `${row},${col}`;
  const cellFor = (latitude, longitude) => ({
    row: Math.floor((maxLat - latitude) / cellDeg),
    col: Math.floor((longitude - minLng) / cellDeg),
  });
  const inBounds = (row, col) => row >= 0 && row < rows && col >= 0 && col < cols;

  const blocked = new Set();
  for (const camera of avoidCameras) {
    if (camera.latitude == null || camera.longitude == null) continue;
    const cell = cellFor(camera.latitude, camera.longitude);
    if (inBounds(cell.row, cell.col)) blocked.add(key(cell.row, cell.col));
  }

  const startCell = cellFor(origin.latitude, origin.longitude);
  const goalCell = cellFor(destination.latitude, destination.longitude);
  if (!inBounds(startCell.row, startCell.col) || !inBounds(goalCell.row, goalCell.col)) {
    return { route: straight, cameras: alongStraight, avoided: false };
  }
  blocked.delete(key(startCell.row, startCell.col));
  blocked.delete(key(goalCell.row, goalCell.col));

  const distances = new Map();
  const previous = new Map();
  const visited = new Set();
  distances.set(key(startCell.row, startCell.col), 0);
  const queue = new MinHeap();
  queue.push({ row: startCell.row, col: startCell.col, cost: 0 });

  const neighbors = [
    [-1, -1], [-1, 0], [-1, 1],
    [0, -1], [0, 1],
    [1, -1], [1, 0], [1, 1],
  ];

  while (queue.size > 0) {
    const current = queue.pop();
    const currentKey = key(current.row, current.col);
    if (visited.has(currentKey)) continue;
    visited.add(currentKey);
    if (current.row === goalCell.row && current.col === goalCell.col) break;

    for (const [dr, dc] of neighbors) {
      const row = current.row + dr;
      const col = current.col + dc;
      const neighborKey = key(row, col);
      if (!inBounds(row, col) || visited.has(neighborKey) || blocked.has(neighborKey)) continue;
      const cost = Math.sqrt(cellDeg * cellDeg * (dr * dr + dc * dc));
      const newCost = current.cost + cost;
      const existing = distances.get(neighborKey);
      if (existing == null || newCost < existing) {
        distances.set(neighborKey, newCost);
        previous.set(neighborKey, { row: current.row, col: current.col });
        queue.push({ row, col, cost: newCost });
      }
    }
  }

  const goalKey = key(goalCell.row, goalCell.col);
  if (!distances.has(goalKey)) {
    return { route: straight, cameras: alongStraight, avoided: false };
  }

  const reversed = [];
  let cursor = goalKey;
  while (cursor != null) {
    const [row, col] = cursor.split(',').map(Number);
    reversed.push({ row, col });
    const previousCell = previous.get(cursor);
    cursor = previousCell ? key(previousCell.row, previousCell.col) : null;
  }
  reversed.reverse();
  const path = reversed.map((cell) => ({
    latitude: maxLat - (cell.row + 0.5) * cellDeg,
    longitude: minLng + (cell.col + 0.5) * cellDeg,
  }));
  const route = path.length > 1 ? path : straight;
  return {
    route,
    cameras: camerasAlongRoute(avoidCameras, route, radiusMeters),
    avoided: true,
  };
}

module.exports = {
  haversineMeters,
  isWithinRange,
  bboxAround,
  bboxAroundPoints,
  distancePointToSegmentMeters,
  distanceToRouteMeters,
  camerasAlongRoute,
  camerasWithinRadius,
  listUserCamerasWithinRadius,
  listUserCamerasInBbox,
  straightLineRoute,
  routeDistanceMeters,
  planRouteAvoiding,
  userCameraRowToCamera,
};
