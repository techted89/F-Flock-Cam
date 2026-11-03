/**
 * Small in-memory fixed-window limiter. It is intentionally bounded so a
 * burst of unique IPs cannot grow the process without limit.
 */
function rateLimit({
  windowMs = 60_000,
  max = 60,
  maxKeys = 5_000,
  key = (req) => req.ip,
} = {}) {
  const hits = new Map();

  function prune(now) {
    for (const [bucketKey, bucket] of hits) {
      if (bucket.resetAt <= now) hits.delete(bucketKey);
    }
    while (hits.size > maxKeys) {
      const oldest = hits.keys().next().value;
      if (oldest == null) break;
      hits.delete(oldest);
    }
  }

  return (req, res, next) => {
    const now = Date.now();
    const bucketKey = key(req);
    const bucket = hits.get(bucketKey);
    if (!bucket || bucket.resetAt <= now) {
      if (hits.size >= maxKeys) prune(now);
      hits.set(bucketKey, { count: 1, resetAt: now + windowMs });
      return next();
    }
    bucket.count += 1;
    if (bucket.count > max) {
      const retryAfter = Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
      res.set('Retry-After', String(retryAfter));
      return res.status(429).json({ status: 'error', message: 'Too many requests, slow down.' });
    }
    return next();
  };
}

module.exports = { rateLimit };
