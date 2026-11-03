const fs = require('fs');
const path = require('path');
const Database = require('better-sqlite3');
const { dbPath } = require('./config');
const { migrate } = require('./migrations');

if (dbPath !== ':memory:') {
  fs.mkdirSync(path.dirname(dbPath), { recursive: true });
}

const db = new Database(dbPath);

// Tuned for a small VPS: WAL keeps readers non-blocking, a bounded page cache
// prevents the process from growing without limit, and NORMAL sync is a safe
// tradeoff for cache-style data.
db.pragma('journal_mode = WAL');
db.pragma('synchronous = NORMAL');
db.pragma('foreign_keys = ON');
db.pragma('cache_size = -16000'); // ~16 MB page cache
db.pragma('temp_store = MEMORY');
db.pragma('busy_timeout = 5000');

migrate(db);

module.exports = { db };
