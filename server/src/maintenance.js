/**
 * Periodic SQLite housekeeping. Keeping the database small keeps the page
 * cache and backups small, which matters on a 4-core/8 GB VPS.
 */
function startMaintenance(db, { intervalMs = 60 * 60 * 1000, now = () => Math.floor(Date.now() / 1000) } = {}) {
  const statements = {
    sessions: db.prepare(
      `DELETE FROM sessions
       WHERE expires_at < ?
          OR (revoked_at IS NOT NULL AND revoked_at < ?)`,
    ),
    codes: db.prepare(
      `DELETE FROM verification_codes
       WHERE expires_at < ? OR (consumed_at IS NOT NULL AND consumed_at < ?)`,
    ),
    rewards: db.prepare('DELETE FROM ad_rewards WHERE granted_until < ?'),
    actions: db.prepare(
      "DELETE FROM pending_actions WHERE status != 'PENDING' AND created_at < ?",
    ),
    deflock: db.prepare('DELETE FROM deflock_cache WHERE fetched_at < ?'),
  };

  function run() {
    const current = now();
    const dayAgo = current - 24 * 60 * 60;
    const weekAgo = current - 7 * 24 * 60 * 60;
    db.transaction(() => {
      statements.sessions.run(current, dayAgo);
      statements.codes.run(current, dayAgo);
      statements.rewards.run(current);
      statements.actions.run(dayAgo);
      // Cached tiles are refreshed long before a week, but keep the option to
      // serve stale data during a Deflock outage.
      statements.deflock.run(weekAgo);
    })();
  }

  run();
  const timer = setInterval(run, intervalMs);
  if (typeof timer.unref === 'function') timer.unref();
  return () => clearInterval(timer);
}

module.exports = { startMaintenance };
