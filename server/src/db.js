const Database = require('better-sqlite3');
const path = require('path');

const db = new Database(process.env.DB_PATH || path.join(__dirname, '..', 'data', 'flockcam.db'));

db.pragma('journal_mode = WAL');
db.exec(`
  CREATE TABLE IF NOT EXISTS user_cameras (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL DEFAULT 'anonymous-device',
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    source TEXT,
    note TEXT,
    created_at INTEGER NOT NULL DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS deflock_cache (
    cache_key TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    fetched_at INTEGER NOT NULL DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS pending_actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    action TEXT NOT NULL,
    source TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at INTEGER NOT NULL DEFAULT (unixepoch())
  );
`);

module.exports = { db };
