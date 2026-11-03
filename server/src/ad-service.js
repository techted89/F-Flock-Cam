const express = require('express');
const config = require('./config');
const { db } = require('./db');
const { requireAuth, grantAdReward, nowSeconds } = require('./user-service');

const router = express.Router();

router.post('/reward', requireAuth, (req, res) => {
  const provider = String(req.body?.provider || 'admob').slice(0, 40);
  const proof = req.body?.proof ? String(req.body.proof).slice(0, 500) : null;
  const last = db
    .prepare(
      'SELECT created_at, granted_until FROM ad_rewards WHERE user_id = ? ORDER BY id DESC LIMIT 1',
    )
    .get(req.user.id);
  const now = nowSeconds();
  const nextAllowed = last
    ? Math.max(last.created_at + config.adRewardCooldownSeconds, last.granted_until)
    : 0;
  if (last && now < nextAllowed) {
    const retryAfter = nextAllowed - now;
    res.set('Retry-After', String(retryAfter));
    return res.status(429).json({
      status: 'error',
      message: 'Ad reward is still active. Try again when it expires.',
      retryAfterSeconds: retryAfter,
    });
  }
  const entitlement = grantAdReward({
    userId: req.user.id,
    provider,
    proof,
    grantedUntil: now + config.adRewardSeconds,
  });
  return res.json({
    status: 'ok',
    entitlement,
    grantedUntil: entitlement.expiresAt,
  });
});

module.exports = { router };
