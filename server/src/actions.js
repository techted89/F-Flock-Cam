const { db } = require('./db');

function listPending(deviceId) {
  const rows = db
    .prepare(
      'SELECT id, device_id, action, source, status, created_at FROM pending_actions WHERE device_id = ? AND status = ? ORDER BY id DESC',
    )
    .all(deviceId, 'PENDING');
  return rows.map((row) => ({
    action: row.action,
    source: row.source,
    createdAtMillis: row.created_at ? row.created_at * 1000 : null,
  }));
}

function createPending(deviceId, action, source) {
  const info = db
    .prepare('INSERT INTO pending_actions (device_id, action, source, status) VALUES (?, ?, ?, ?)')
    .run(deviceId, action, source || null, 'PENDING');
  return { id: Number(info.lastInsertRowid), status: 'PENDING', action };
}

function complete(id) {
  db.prepare("UPDATE pending_actions SET status = 'COMPLETED' WHERE id = ?").run(id);
}

module.exports = { listPending, createPending, complete };
