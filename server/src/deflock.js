const config = require('./config');
const { db } = require('./db');

const TILES_BASE_URL = config.deflockTilesBaseUrl;
const TILE_SIZE_DEGREES = config.deflockTileSizeDegrees;
const CACHE_TTL_SECONDS = config.deflockCacheTtlSeconds;
const INDEX_CACHE_KEY = 'deflock_tiles_index_v2';
const FETCH_CONCURRENCY = 6;
const MAX_MEMORY_ENTRIES = 192;

// Hot parsed copies live in memory so repeat requests skip SQLite reads and
// JSON.parse. The map is bounded so RAM stays flat under churn.
const memoryCache = new Map();

const CARDINALS = {
  N: 0, NNE: 22.5, NE: 45, ENE: 67.5,
  E: 90, ESE: 112.5, SE: 135, SSE: 157.5,
  S: 180, SSW: 202.5, SW: 225, WSW: 247.5,
  W: 270, WNW: 292.5, NW: 315, NNW: 337.5,
};

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function memoryGet(key) {
  const entry = memoryCache.get(key);
  if (!entry) return null;
  if (entry.expiresAt <= nowSeconds()) {
    memoryCache.delete(key);
    return null;
  }
  return entry.payload;
}

function memorySet(key, payload) {
  memoryCache.delete(key);
  memoryCache.set(key, {
    payload,
    expiresAt: nowSeconds() + CACHE_TTL_SECONDS,
  });
  while (memoryCache.size > MAX_MEMORY_ENTRIES) {
    const oldest = memoryCache.keys().next().value;
    if (oldest == null) break;
    memoryCache.delete(oldest);
  }
}

function cacheGet(key, ttlSeconds) {
  const hot = memoryGet(key);
  if (hot != null) {
    return { payload: hot, fetchedAt: nowSeconds(), stale: false };
  }
  const row = db.prepare('SELECT payload, fetched_at FROM deflock_cache WHERE cache_key = ?').get(key);
  if (!row) return null;
  const payload = JSON.parse(row.payload);
  const stale = nowSeconds() - row.fetched_at > ttlSeconds;
  if (!stale) memorySet(key, payload);
  return { payload, fetchedAt: row.fetched_at, stale };
}

function cacheSet(key, payload) {
  memorySet(key, payload);
  db.prepare('INSERT OR REPLACE INTO deflock_cache (cache_key, payload, fetched_at) VALUES (?, ?, ?)')
    .run(key, JSON.stringify(payload), nowSeconds());
}

function parseDirection(value) {
  if (value == null) return null;
  if (typeof value === 'string') {
    const normalized = value.trim().toUpperCase();
    if (CARDINALS[normalized] != null) return CARDINALS[normalized];
  }
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return null;
  return ((numeric % 360) + 360) % 360;
}

function normalizeCamera(entry, bbox) {
  if (!entry || entry.lat == null || entry.lon == null) return null;
  const latitude = Number(entry.lat);
  const longitude = Number(entry.lon);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  if (bbox) {
    if (latitude < bbox.minLat || latitude > bbox.maxLat) return null;
    if (longitude < bbox.minLng || longitude > bbox.maxLng) return null;
  }
  const tags = entry.tags || {};
  const rawDirection = tags.direction ?? tags.bearing ?? entry.direction ?? null;
  const viewDirection = tags.view_direction
    ?? tags.viewDirection
    ?? tags.facing
    ?? (typeof rawDirection === 'string' ? rawDirection : null)
    ?? null;
  return {
    id: String(entry.id ?? `deflock-${latitude}-${longitude}`),
    latitude,
    longitude,
    source: 'DEFLOCK',
    name: tags.name ?? tags.title ?? null,
    description: tags.description ?? tags.notes ?? tags.note ?? entry.description ?? null,
    manufacturer: tags.manufacturer ?? null,
    operator: tags.operator ?? null,
    directionDegrees: parseDirection(rawDirection),
    viewDirection,
    confidence: tags.confidence != null ? Number(tags.confidence) : null,
  };
}

async function fetchTilesIndex() {
  const cached = cacheGet(INDEX_CACHE_KEY, CACHE_TTL_SECONDS);
  if (cached && !cached.stale) return cached.payload;
  try {
    const response = await fetch(`${TILES_BASE_URL.replace(/\/$/, '')}/index.json`);
    if (!response.ok) {
      throw new Error(`Deflock tiles index responded with status ${response.status}`);
    }
    const index = await response.json();
    cacheSet(INDEX_CACHE_KEY, index);
    return index;
  } catch (error) {
    if (cached) return cached.payload;
    throw error;
  }
}

function tileUrl(index, lat, lng) {
  const template = index.tile_url || `${TILES_BASE_URL}/{lat}/{lon}.json`;
  return String(template)
    .replaceAll('{lat}', String(lat))
    .replaceAll('{lon}', String(lng));
}

async function fetchTile(index, lat, lng) {
  const key = `deflock_tile:${lat}:${lng}`;
  const cached = cacheGet(key, CACHE_TTL_SECONDS);
  if (cached && !cached.stale) return cached.payload;
  try {
    const response = await fetch(tileUrl(index, lat, lng));
    if (!response.ok) throw new Error(`Deflock tile ${lat}/${lng} responded with ${response.status}`);
    const entries = await response.json();
    if (!Array.isArray(entries)) throw new Error(`Deflock tile ${lat}/${lng} is not an array`);
    cacheSet(key, entries);
    return entries;
  } catch (error) {
    if (cached) return cached.payload;
    return [];
  }
}

async function fetchTileBatch(index, tiles) {
  const results = new Array(tiles.length);
  let cursor = 0;
  const workers = Array.from(
    { length: Math.min(FETCH_CONCURRENCY, tiles.length) },
    async () => {
      while (true) {
        const tileIndex = cursor;
        cursor += 1;
        if (tileIndex >= tiles.length) return;
        const [lat, lng] = tiles[tileIndex];
        results[tileIndex] = await fetchTile(index, lat, lng);
      }
    },
  );
  await Promise.all(workers);
  return results;
}

async function fetchDeflockCamerasInBbox(minLat, maxLat, minLng, maxLng, options = {}) {
  const index = await fetchTilesIndex();
  const sizeDegrees = Number(index.tile_size_degrees || TILE_SIZE_DEGREES);
  const south = Math.max(Number(minLat), -85);
  const north = Math.min(Number(maxLat), 85);
  const west = Math.max(Number(minLng), -180);
  const east = Math.min(Number(maxLng), 180);
  if (![south, north, west, east].every(Number.isFinite)) return [];
  if (north < south || east < west) return [];

  const tiles = [];
  for (let lat = Math.floor(south / sizeDegrees) * sizeDegrees; lat <= north; lat += sizeDegrees) {
    for (let lng = Math.floor(west / sizeDegrees) * sizeDegrees; lng <= east; lng += sizeDegrees) {
      tiles.push([lat, lng]);
    }
  }

  const maxTiles = Number(options.maxTiles || config.deflockMaxTiles);
  if (tiles.length > maxTiles) {
    const error = new Error(`Requested region needs ${tiles.length} tiles (max ${maxTiles}).`);
    error.code = 'REGION_TOO_LARGE';
    throw error;
  }

  const batches = await fetchTileBatch(index, tiles);
  const cameras = [];
  const seen = new Set();
  for (const entries of batches) {
    for (const entry of entries) {
      const camera = normalizeCamera(entry, { minLat: south, maxLat: north, minLng: west, maxLng: east });
      if (!camera) continue;
      const key = `${camera.id}|${camera.latitude}|${camera.longitude}`;
      if (seen.has(key)) continue;
      seen.add(key);
      cameras.push(camera);
    }
  }
  return cameras;
}

async function fetchDeflockCameras() {
  return fetchDeflockCamerasInBbox(-85, 85, -180, 180);
}

module.exports = {
  fetchDeflockCameras,
  fetchDeflockCamerasInBbox,
  normalizeCamera,
  parseDirection,
  TILES_BASE_URL,
};
