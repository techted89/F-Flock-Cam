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

function listUserCamerasWithinRadius(latitude, longitude, radiusMeters) {
  const rows = db
    .prepare(
      'SELECT id, latitude, longitude, source, note FROM user_cameras WHERE latitude IS NOT NULL AND longitude IS NOT NULL',
    )
    .all();
  const result = rows
    .map((row) => ({
      id: `user-${row.id}`,
      latitude: row.latitude,
      longitude: row.longitude,
      name: row.note ? `Reported camera (${row.note})` : 'Reported flock camera',
      source: row.source || 'USER',
      distanceMeters: haversineMeters({ latitude: row.latitude, longitude: row.longitude }, { latitude, longitude }),
    }))
    .filter((camera) => isWithinRange(camera, latitude, longitude, radiusMeters))
    .sort((a, b) => (a.distanceMeters ?? 0) - (b.distanceMeters ?? 0));
  return result;
}

function camerasAlongRoute(routePoints, radiusMeters, excludeIds) {
  if (!Array.isArray(routePoints) || routePoints.length < 2) return [];
  const cameras = [];
  for (const camera of routePoints) {
    if (excludeIds?.has?.(camera.id)) continue;
    if (!isWithinRange(camera, camera.latitude, camera.longitude, radiusMeters)) continue;
    cameras.push(camera);
  }
  return cameras;
}

function straightLineRoute(origin, destination) {
  return [
    { latitude: origin.latitude, longitude: origin.longitude },
    { latitude: destination.latitude, longitude: destination.longitude },
  ];
}

function routeDistanceMeters(origin, destination, routePoints) {
  let total = 0;
  for (let i = 1; i < routePoints.length; i += 1) {
    total += haversineMeters(
      { latitude: routePoints[i - 1].latitude, longitude: routePoints[i].longitude },
      { latitude: routePoints[i].latitude, longitude: routePoints[i].longitude },
    );
  }
  return total;
}

// Coarse grid Dijkstra with 8-directional movement; camera locations are treated as blocked cells.
function planRouteAvoiding(origin, destination, avoidCameras, options = {}) {
  const radiusMeters = options.radiusMeters || 1000;
  const straight = straightLineRoute(origin, destination);
  const alongStraight = camerasAlongRoute(straight, radiusMeters, avoidCameras?.map((camera) => camera.id));
  if (!avoidCameras || avoidCameras.length === 0) {
    return { route: straight, cameras: alongStraight, avoided: false };
  }

  const blocked = new Set();
  for (const camera of avoidCameras) {
    if (camera.latitude == null || camera.longitude == null) continue;
    blocked.add(`${camera.latitude.toFixed(6)},${camera.longitude.toFixed(6)}`);
  }

  const latPad = radiusMeters / 110_000 + 0.01;
  const lngPad = radiusMeters / (111_320 * Math.cos((Number(origin.latitude) * Math.PI) / 180)) + 0.01;
  const minLat = Math.min(origin.latitude, destination.latitude) - latPad;
  const maxLat = Math.max(origin.latitude, destination.latitude) + latPad;
  const minLng = Math.min(origin.longitude, destination.longitude) - lngPad;
  const maxLng = Math.max(origin.longitude, destination.longitude) + lngPad;

  const cellDeg = 25 / 111_320;
  const cols = Math.max(2, Math.ceil((maxLng - minLng) / cellDeg));
  const rows = Math.max(2, Math.ceil((maxLat - minLat) / cellDeg));

  const inBounds = (lat, lng) => lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
  const cellKey = (lat, lng) => `${lat.toFixed(6)},${lng.toFixed(6)}`;

  const startKey = cellKey(origin.latitude, origin.longitude);
  const goalKey = cellKey(destination.latitude, destination.longitude);
  if (blocked.has(startKey) || blocked.has(goalKey)) {
    return { route: straight, cameras: alongStraight, avoided: false };
  }

  const distances = new Map();
  const previous = new Map();
  const visited = new Set();
  const startCell = { row: Math.floor((maxLat - origin.latitude) / cellDeg), col: Math.floor((origin.longitude - minLng) / cellDeg) };
  const goalCell = { row: Math.floor((maxLat - destination.latitude) / cellDeg), col: Math.floor((destination.longitude - minLng) / cellDeg) };
  const key = (row, col) => `${row},${col}`;
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
    const currentLat = maxLat - (current.row + 0.5) * cellDeg;
    const currentLng = minLng + (current.col + 0.5) * cellDeg;
    for (const [dr, dc] of neighbors) {
      const row = current.row + dr;
      const col = current.col + dc;
      const nKey = key(row, col);
      if (visited.has(nKey)) continue;
      const nLat = maxLat - (row + 0.5) * cellDeg;
      const nLng = minLng + (col + 0.5) * cellDeg;
      if (!inBounds(nLat, nLng)) continue;
      if (blocked.has(cellKey(nLat, nLng))) continue;
      const cost = Math.sqrt(cellDeg * cellDeg * (dr * dr + dc * dc));
      const newCost = current.cost + cost;
      const existing = distances.get(nKey);
      if (existing == null || newCost < existing) {
        distances.set(nKey, newCost);
        previous.set(nKey, { row: current.row, col: current.col });
        queue.push({ row, col, cost: newCost });
      }
    }
  }

  const goalKey2 = key(goalCell.row, goalCell.col);
  if (!distances.has(goalKey2)) {
    // No detour found; fall back to the straight line.
    return { route: straight, cameras: alongStraight, avoided: false };
  }

  const path = [];
  let cursor = goalKey2;
  const reversed = [];
  while (cursor != null) {
    const [r, c] = cursor.split(',').map(Number);
    reversed.push({ row: r, col: c });
    cursor = previous.has(cursor) ? `${previous.get(cursor).row},${previous.get(cursor).col}` : null;
  }
  reversed.reverse();
  for (const cell of reversed) {
    path.push({ latitude: maxLat - (cell.row + 0.5) * cellDeg, longitude: minLng + (cell.col + 0.5) * cellDeg });
  }

  const route = path.length > 1 ? path : straight;
  const alongRoute = camerasAlongRoute(route, radiusMeters, avoidCameras.map((camera) => camera.id));
  return { route, cameras: alongRoute, avoided: true };
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

module.exports = { haversineMeters, isWithinRange, listUserCamerasWithinRadius, camerasAlongRoute, straightLineRoute, routeDistanceMeters, planRouteAvoiding };
