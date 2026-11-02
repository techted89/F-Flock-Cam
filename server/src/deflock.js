const { db } = require('./db');

const DEFLOCK_API_BASE_URL = process.env.DEFLOCK_API_BASE_URL || 'https://api.deflock.cc';
const DEFLOCK_API_KEY = process.env.DEFLOCK_API_KEY || '';
const DEFLOCK_CACHE_TTL_SECONDS = Number(process.env.DEFLOCK_CACHE_TTL_SECONDS || 300);

function toCamera(cam) {
  if (!cam) return null;
  const latitude = cam.latitude ?? cam.lat ?? cam.latitude;
  const longitude = cam.longitude ?? cam.lng ?? cam.lon ?? cam.longitude;
  return {
    id: String(cam.id ?? cam.camera_id ?? `${latitude}-${longitude}`),
    latitude,
    longitude,
    name: cam.name ?? cam.camera_name ?? null,
    source: cam.source ?? 'DEFLOCK',
  };
}

async function fetchDeflockCameras() {
  const cacheKey = 'deflock_cameras_v1';
  const cached = db
    .prepare('SELECT payload, fetched_at FROM deflock_cache WHERE cache_key = ?')
    .get(cacheKey);
  const now = Date.now() / 1000;
  if (cached && now - cached.fetched_at < DEFLOCK_CACHE_TTL_SECONDS) {
    return JSON.parse(cached.payload);
  }

  if (!DEFLOCK_API_KEY) {
    // Without a key we serve an empty catalog; the server still merges user data.
    return [];
  }

  const url = `${DEFLOCK_API_BASE_URL.replace(/\/$/, '')}${process.env.DEFLOCK_CAMERAS_PATH || '/cameras'}`;
  const response = await fetch(url, {
    headers: {
      'X-API-Key': DEFLOCK_API_KEY,
      Accept: 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error(`Deflock API responded with status ${response.status}`);
  }
  const payload = await response.json();
  const cameras = (Array.isArray(payload) ? payload : payload.cameras || []).map(toCamera).filter(Boolean);
  db.prepare('INSERT OR REPLACE INTO deflock_cache (cache_key, payload, fetched_at) VALUES (?, ?, ?)')
    .run(cacheKey, JSON.stringify(cameras), now);
  return cameras;
}

module.exports = { fetchDeflockCameras };
