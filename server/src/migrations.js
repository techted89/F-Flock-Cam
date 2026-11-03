const migrations = [
  {
    id: 1,
    up(db) {
      db.exec(`
        CREATE TABLE IF NOT EXISTS users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          email TEXT UNIQUE,
          email_verified INTEGER NOT NULL DEFAULT 0,
          password_hash TEXT,
          auth_provider TEXT NOT NULL DEFAULT 'password',
          provider_id TEXT,
          display_name TEXT,
          phone TEXT,
          created_at INTEGER NOT NULL DEFAULT (unixepoch()),
          deleted_at INTEGER
        );

        CREATE TABLE IF NOT EXISTS sessions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          token_hash TEXT NOT NULL UNIQUE,
          device_id TEXT,
          expires_at INTEGER NOT NULL,
          created_at INTEGER NOT NULL DEFAULT (unixepoch()),
          revoked_at INTEGER
        );

        CREATE TABLE IF NOT EXISTS verification_codes (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          purpose TEXT NOT NULL,
          code_hash TEXT NOT NULL,
          expires_at INTEGER NOT NULL,
          consumed_at INTEGER,
          attempts INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL DEFAULT (unixepoch())
        );

        CREATE TABLE IF NOT EXISTS subscriptions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          provider TEXT NOT NULL,
          product_id TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'ACTIVE',
          starts_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL,
          purchase_token TEXT,
          order_id TEXT,
          raw_hash TEXT,
          created_at INTEGER NOT NULL DEFAULT (unixepoch()),
          updated_at INTEGER NOT NULL DEFAULT (unixepoch())
        );

        CREATE TABLE IF NOT EXISTS payment_orders (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          provider TEXT NOT NULL,
          order_id TEXT NOT NULL UNIQUE,
          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          product_id TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'CREATED',
          amount_cents INTEGER NOT NULL,
          currency TEXT NOT NULL,
          created_at INTEGER NOT NULL DEFAULT (unixepoch()),
          updated_at INTEGER NOT NULL DEFAULT (unixepoch())
        );

        CREATE TABLE IF NOT EXISTS ad_rewards (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          provider TEXT NOT NULL,
          proof TEXT,
          granted_until INTEGER NOT NULL,
          created_at INTEGER NOT NULL DEFAULT (unixepoch())
        );

        CREATE TABLE IF NOT EXISTS user_cameras (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER,
          device_id TEXT NOT NULL DEFAULT 'anonymous-device',
          latitude REAL NOT NULL,
          longitude REAL NOT NULL,
          source TEXT,
          note TEXT,
          description TEXT,
          direction_degrees REAL,
          view_direction TEXT,
          manufacturer TEXT,
          operator TEXT,
          status TEXT NOT NULL DEFAULT 'PENDING',
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

        CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token_hash);
        CREATE INDEX IF NOT EXISTS idx_subscriptions_user ON subscriptions(user_id, expires_at);
        CREATE INDEX IF NOT EXISTS idx_ad_rewards_user ON ad_rewards(user_id, granted_until);
        CREATE INDEX IF NOT EXISTS idx_user_cameras_geo ON user_cameras(latitude, longitude);
        CREATE INDEX IF NOT EXISTS idx_user_cameras_status ON user_cameras(status);
      `);
    },
  },
  {
    id: 2,
    up(db) {
      const columns = db.prepare('PRAGMA table_info(user_cameras)').all().map((column) => column.name);
      const additions = [
        ['user_id', 'INTEGER'],
        ['description', 'TEXT'],
        ['direction_degrees', 'REAL'],
        ['view_direction', 'TEXT'],
        ['manufacturer', 'TEXT'],
        ['operator', 'TEXT'],
        ['status', "TEXT NOT NULL DEFAULT 'PENDING'"],
      ];
      for (const [name, type] of additions) {
        if (!columns.includes(name)) {
          db.exec(`ALTER TABLE user_cameras ADD COLUMN ${name} ${type}`);
        }
      }
      const userColumns = db.prepare('PRAGMA table_info(users)').all().map((column) => column.name);
      if (!userColumns.includes('provider_id')) {
        db.exec('ALTER TABLE users ADD COLUMN provider_id TEXT');
      }
      if (!userColumns.includes('phone')) {
        db.exec('ALTER TABLE users ADD COLUMN phone TEXT');
      }
    },
  },
];

function migrate(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      id INTEGER PRIMARY KEY,
      applied_at INTEGER NOT NULL DEFAULT (unixepoch())
    );
  `);
  const applied = new Set(db.prepare('SELECT id FROM schema_migrations').all().map((row) => row.id));
  for (const migration of migrations) {
    if (applied.has(migration.id)) continue;
    db.transaction(() => {
      migration.up(db);
      db.prepare('INSERT INTO schema_migrations (id) VALUES (?)').run(migration.id);
    })();
  }
}

module.exports = { migrate };
