const { db } = require('./db');

const TILES_BASE_URL = process.env.DEFLOCK_TILES_BASE_URL || 'https://cdn.deflock.me/regions';
const TILE_SIZE_DEGREES = Number(process.env.DEFLOCK_TILE_SIZE_DEGREES || 20);
const CACHE_TTL_SECONDS = Number(process.env.DEFLOCK_CACHE_TTL_SECONDS || 300);
const INDEX_CACHE_KEY = 'deflock_tiles_index_v1';

function toCamera(entry, bbox) {
  if (!entry || entry.lat == null || entry.lon == null) return null;
  const camera = {
    id: String(entry.id ?? `deflock-${entry.lat}-${entry.lon}`),
    latitude: entry.lat,
    longitude: entry.lon,
    source: 'DEFLOCK',
    manufacturer: entry.tags?.manufacturer ?? null,
    operator: entry.tags?.operator ?? null,
    directionDegrees: entry.tags?.direction ?? null,
  };
  if (bbox) {
    if (camera.latitude < bbox.minLat || camera.latitude > bbox.maxLat) return null;
    if (camera.longitude < bbox.minLng || camera.longitude > bbox.maxLng) return null;
  }
  return camera;
}

async function fetchTilesIndex() {
  const cached = db
    .prepare('SELECT payload, fetched_at FROM deflock_cache WHERE cache_key = ?')
    .get(INDEX_CACHE_KEY);
  const now = Date.now() / 1000;
  if (cached && now - cached.fetched_at < CACHE_TTL_SECONDS) {
    return JSON.parse(cached.payload);
  }
  const response = await fetch(`${TILES_BASE_URL.replace(/\/$/, '')}/index.json`);
  if (!response.ok) {
    throw new Error(`Deflock tiles index responded with status ${response.status}`);
  }
  const index = await response.json();
  db.prepare('INSERT OR REPLACE INTO deflock_cache (cache_key, payload, fetched_at) VALUES (?, ?, ?)')
    .run(INDEX_CACHE_KEY, JSON.stringify(index), now);
  return index;
}

async function fetchDeflockCamerasInBbox(minLat, maxLat, minLng, maxLng) {
  const index = await fetchTilesIndex();
  const sizeDegrees = index.tile_size_degrees || TILE_SIZE_DEGREES;
  const south = Math.max(Number(minLat), -85);
  const north = Math.min(Number(maxLat), 85);
  const west = Math.max(Number(minLng), -180);
  const east = Math.min(Number(maxLng), 180);
  if (north < south || east < west) return [];

  const latKeys = [];
  const lngKeys = [];
  for (let lat = Math.floor(south / sizeDegrees) * sizeDegrees; lat <= Math.ceil(north / sizeDegrees); lat += sizeDegrees) {
    latKeys.push(lat);
  }
  for (let lng = Math.floor(west / sizeDegrees) * sizeDegrees; lng <= Math.ceil(east / sizeDegrees); lng += sizeDegrees) {
    lngKeys.push(lng);
  }

  const cameras = [];
  const seen = new Set();
  const pushCamera = (camera) => {
    const key = `${camera.id}|${camera.latitude}|${camera.longitude}`;
    if (seen.has(key)) return;
    seen.add(key);
    cameras.push(camera);
  };

  for (const lat of latKeys) {
    for (const lng of lngKeys) {
      const url = String(index.tile_url || `${TILES_BASE_URL}/{lat}/{lon}.json`).replace('{lat}/{lon}', `${lat}/${lng}`);
      const response = await fetch(url);
      if (!response.ok) continue;
      const entries = await response.json();
      if (!Array.isArray(entries)) continue;
      for (const entry of entries) {
        const camera = toCamera(entry, { minLat: south, maxLat: north, minLng: west, maxLng: east });
        if (camera) pushCamera(camera);
      }
    }
  }

  return cameras;
}

async function fetchDeflockCameras() {
  // Worldwide bounding box; cached tile index keeps repeat calls cheap.
  const cameras = await fetchDeflockCamerasInBbox(-90, 90, -180, 180);
  return cameras;
}

module.exports = { fetchDeflockCameras, fetchDeflockCamerasInBbox, TILES_BASE_URL };
