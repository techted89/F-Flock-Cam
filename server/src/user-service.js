const express = require('express');
const config = require('./config');
const { db } = require('./db');
const {
  hashPassword,
  verifyPassword,
  randomToken,
  randomCode,
  sha256,
  normalizeEmail,
  isEmail,
} = require('./security');
const { sendVerificationCode } = require('./mailer');
const { rateLimit } = require('./rate-limit');

const router = express.Router();
const authLimiter = rateLimit({ windowMs: 60_000, max: 30 });

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function limitsFor(tier) {
  const premium = tier === 'premium';
  return {
    maxRadiusMeters: premium ? config.premiumMaxRadiusMeters : config.freeMaxRadiusMeters,
    visibleCameras: premium ? null : config.freeVisibleCameras,
    regionMaxCameras: premium ? config.premiumRegionMaxCameras : config.freeRegionMaxCameras,
    adsRequired: !premium,
  };
}

function publicUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    email: row.email || null,
    emailVerified: Boolean(row.email_verified),
    displayName: row.display_name || null,
    authProvider: row.auth_provider || 'password',
    createdAt: row.created_at || null,
  };
}

function getEntitlement(userId) {
  if (!userId) {
    return {
      tier: 'free',
      source: null,
      expiresAt: null,
      productId: null,
      ...limitsFor('free'),
    };
  }

  const now = nowSeconds();
  const subscription = db
    .prepare(
      `SELECT provider, product_id, expires_at FROM subscriptions
       WHERE user_id = ? AND status = 'ACTIVE' AND expires_at > ?
       ORDER BY expires_at DESC LIMIT 1`,
    )
    .get(userId, now);
  if (subscription) {
    return {
      tier: 'premium',
      source: subscription.provider,
      expiresAt: subscription.expires_at,
      productId: subscription.product_id,
      ...limitsFor('premium'),
    };
  }

  const reward = db
    .prepare(
      `SELECT provider, granted_until FROM ad_rewards
       WHERE user_id = ? AND granted_until > ?
       ORDER BY granted_until DESC LIMIT 1`,
    )
    .get(userId, now);
  if (reward) {
    return {
      tier: 'premium',
      source: `ad:${reward.provider}`,
      expiresAt: reward.granted_until,
      productId: null,
      ...limitsFor('premium'),
    };
  }

  return {
    tier: 'free',
    source: null,
    expiresAt: null,
    productId: null,
    ...limitsFor('free'),
  };
}

function createSession(userId, deviceId) {
  const token = randomToken(32);
  const expiresAt = nowSeconds() + config.sessionTtlSeconds;
  db.prepare(
    'INSERT INTO sessions (user_id, token_hash, device_id, expires_at) VALUES (?, ?, ?, ?)',
  ).run(userId, sha256(token), deviceId || null, expiresAt);
  return { token, expiresAt };
}

function revokeSession(tokenHash) {
  db.prepare('UPDATE sessions SET revoked_at = ? WHERE token_hash = ?').run(nowSeconds(), tokenHash);
}

function revokeAllSessions(userId) {
  db.prepare('UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL')
    .run(nowSeconds(), userId);
}

function readAuth(req) {
  const header = String(req.headers.authorization || '');
  const match = /^Bearer\s+(.+)$/i.exec(header);
  if (!match) return null;
  const tokenHash = sha256(match[1]);
  const row = db
    .prepare(
      `SELECT u.*, s.token_hash, s.expires_at AS session_expires_at
       FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND u.deleted_at IS NULL`,
    )
    .get(tokenHash, nowSeconds());
  if (!row) return null;
  return {
    user: row,
    tokenHash,
    entitlement: getEntitlement(row.id),
  };
}

function optionalAuth(req, res, next) {
  const auth = readAuth(req);
  if (auth) {
    req.user = auth.user;
    req.tokenHash = auth.tokenHash;
    req.entitlement = auth.entitlement;
  } else {
    req.entitlement = getEntitlement(null);
  }
  next();
}

function requireAuth(req, res, next) {
  const auth = readAuth(req);
  if (!auth) {
    return res.status(401).json({ status: 'error', message: 'Authentication required.' });
  }
  req.user = auth.user;
  req.tokenHash = auth.tokenHash;
  req.entitlement = auth.entitlement;
  return next();
}

function findUserByEmail(email) {
  return db.prepare('SELECT * FROM users WHERE email = ? AND deleted_at IS NULL').get(normalizeEmail(email));
}

function findUserById(id) {
  return db.prepare('SELECT * FROM users WHERE id = ? AND deleted_at IS NULL').get(id);
}

function createUser({ email, passwordHash, displayName, authProvider, providerId, emailVerified }) {
  const info = db
    .prepare(
      `INSERT INTO users (email, email_verified, password_hash, auth_provider, provider_id, display_name)
       VALUES (?, ?, ?, ?, ?, ?)`,
    )
    .run(
      normalizeEmail(email),
      emailVerified ? 1 : 0,
      passwordHash || null,
      authProvider || 'password',
      providerId || null,
      displayName || null,
    );
  return findUserById(Number(info.lastInsertRowid));
}

async function issueCode(user, purpose) {
  const code = randomCode();
  const expiresAt = nowSeconds() + config.codeTtlSeconds;
  db.prepare(
    'INSERT INTO verification_codes (user_id, purpose, code_hash, expires_at) VALUES (?, ?, ?, ?)',
  ).run(user.id, purpose, sha256(code), expiresAt);
  const delivery = await sendVerificationCode({
    email: user.email,
    phone: user.phone,
    code,
    purpose,
  });
  return { code, delivery };
}

function consumeCode(userId, purpose, code) {
  const row = db
    .prepare(
      `SELECT * FROM verification_codes
       WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > ?
       ORDER BY id DESC LIMIT 1`,
    )
    .get(userId, purpose, nowSeconds());
  if (!row) return { ok: false, reason: 'missing' };

  if (row.attempts >= config.codeMaxAttempts) {
    db.prepare('UPDATE verification_codes SET consumed_at = ? WHERE id = ?').run(nowSeconds(), row.id);
    return { ok: false, reason: 'attempts' };
  }

  db.prepare('UPDATE verification_codes SET attempts = attempts + 1 WHERE id = ?').run(row.id);
  if (row.code_hash !== sha256(code)) {
    return { ok: false, reason: 'invalid' };
  }
  db.prepare('UPDATE verification_codes SET consumed_at = ? WHERE id = ?').run(nowSeconds(), row.id);
  return { ok: true };
}

function grantSubscription({
  userId,
  provider,
  productId,
  startsAt,
  expiresAt,
  purchaseToken,
  orderId,
  raw,
}) {
  const now = nowSeconds();
  const rawHash = raw ? sha256(JSON.stringify(raw)) : null;

  // Idempotency: the same Play purchase token (or PayPal order) must never
  // stack entitlements, and a token cannot be transferred between accounts.
  let existing = null;
  if (purchaseToken) {
    existing = db
      .prepare('SELECT id, user_id FROM subscriptions WHERE purchase_token = ?')
      .get(purchaseToken);
  } else if (orderId) {
    existing = db
      .prepare("SELECT id, user_id FROM subscriptions WHERE order_id = ? AND provider = 'PAYPAL'")
      .get(orderId);
  }

  if (existing) {
    if (Number(existing.user_id) !== Number(userId)) {
      throw new Error('This purchase is already linked to another account.');
    }
    db.prepare(
      `UPDATE subscriptions
       SET status = 'ACTIVE', product_id = ?, starts_at = ?, expires_at = ?,
           raw_hash = ?, updated_at = ?
       WHERE id = ?`,
    ).run(productId, startsAt || now, expiresAt, rawHash, now, existing.id);
  } else {
    db.prepare(
      `INSERT INTO subscriptions
        (user_id, provider, product_id, status, starts_at, expires_at, purchase_token, order_id, raw_hash, updated_at)
       VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)`,
    ).run(
      userId,
      provider,
      productId,
      startsAt || now,
      expiresAt,
      purchaseToken || null,
      orderId || null,
      rawHash,
      now,
    );
  }
  return getEntitlement(userId);
}

function grantAdReward({ userId, provider, proof, grantedUntil }) {
  const until = grantedUntil || nowSeconds() + config.adRewardSeconds;
  db.prepare(
    'INSERT INTO ad_rewards (user_id, provider, proof, granted_until) VALUES (?, ?, ?, ?)',
  ).run(userId, provider, proof || null, until);
  return getEntitlement(userId);
}

function authPayload(user, deviceId) {
  const session = createSession(user.id, deviceId);
  return {
    token: session.token,
    user: publicUser(user),
    entitlement: getEntitlement(user.id),
    verificationRequired: !user.email_verified,
  };
}

router.post('/register', authLimiter, async (req, res, next) => {
  try {
    const email = normalizeEmail(req.body?.email);
    const password = String(req.body?.password || '');
    const displayName = String(req.body?.displayName || '').trim() || null;
    const deviceId = req.body?.deviceId || null;

    if (!isEmail(email)) {
      return res.status(400).json({ status: 'error', message: 'A valid email is required.' });
    }
    if (password.length < 8) {
      return res.status(400).json({ status: 'error', message: 'Password must be at least 8 characters.' });
    }
    if (findUserByEmail(email)) {
      return res.status(409).json({ status: 'error', message: 'An account with that email already exists.' });
    }

    const user = createUser({
      email,
      passwordHash: await hashPassword(password),
      displayName,
      authProvider: 'password',
      emailVerified: false,
    });
    const issued = await issueCode(user, 'verify_email');
    const payload = authPayload(user, deviceId);
    return res.status(201).json({
      ...payload,
      status: 'ok',
      message: 'Account created. Verify your email to unlock purchases.',
      devCode: config.devReturnCodes ? issued.code : undefined,
    });
  } catch (error) {
    return next(error);
  }
});

router.post('/login', authLimiter, async (req, res, next) => {
  try {
    const email = normalizeEmail(req.body?.email);
    const password = String(req.body?.password || '');
    const user = findUserByEmail(email);
    if (!user || !user.password_hash || !(await verifyPassword(password, user.password_hash))) {
      return res.status(401).json({ status: 'error', message: 'Invalid email or password.' });
    }
    return res.json({
      status: 'ok',
      ...authPayload(user, req.body?.deviceId),
    });
  } catch (error) {
    return next(error);
  }
});

router.post('/google', authLimiter, async (req, res, next) => {
  try {
    const idToken = String(req.body?.idToken || '');
    if (!idToken) {
      return res.status(400).json({ status: 'error', message: 'idToken is required.' });
    }
    if (!config.googleClientId) {
      return res.status(503).json({ status: 'error', message: 'Google sign-in is not configured on this server.' });
    }
    const response = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`);
    if (!response.ok) {
      return res.status(401).json({ status: 'error', message: 'Invalid Google token.' });
    }
    const profile = await response.json();
    if (profile.aud !== config.googleClientId) {
      return res.status(401).json({ status: 'error', message: 'Google token audience mismatch.' });
    }
    if (profile.email_verified !== 'true' || !isEmail(profile.email)) {
      return res.status(401).json({ status: 'error', message: 'Google account email is not verified.' });
    }

    let user = findUserByEmail(profile.email);
    if (!user) {
      user = createUser({
        email: profile.email,
        displayName: profile.name || null,
        authProvider: 'google',
        providerId: profile.sub || null,
        emailVerified: true,
      });
    } else if (!user.provider_id || user.auth_provider === 'google') {
      db.prepare('UPDATE users SET provider_id = ?, auth_provider = ?, email_verified = 1 WHERE id = ?')
        .run(profile.sub || null, 'google', user.id);
      user = findUserById(user.id);
    }

    return res.json({
      status: 'ok',
      ...authPayload(user, req.body?.deviceId),
    });
  } catch (error) {
    return next(error);
  }
});

router.post('/verify-email', authLimiter, (req, res) => {
  const email = normalizeEmail(req.body?.email);
  const code = String(req.body?.code || '');
  const user = findUserByEmail(email);
  if (!user) {
    return res.status(400).json({ status: 'error', message: 'Invalid verification request.' });
  }
  const result = consumeCode(user.id, 'verify_email', code);
  if (!result.ok) {
    return res.status(400).json({ status: 'error', message: 'Invalid or expired verification code.' });
  }
  db.prepare('UPDATE users SET email_verified = 1 WHERE id = ?').run(user.id);
  const updated = findUserById(user.id);
  return res.json({
    status: 'ok',
    ...authPayload(updated, req.body?.deviceId),
  });
});

router.post('/resend-verification', authLimiter, async (req, res, next) => {
  try {
    const user = findUserByEmail(req.body?.email);
    if (user && !user.email_verified) {
      const issued = await issueCode(user, 'verify_email');
      return res.json({
        status: 'ok',
        message: 'Verification code sent.',
        devCode: config.devReturnCodes ? issued.code : undefined,
      });
    }
    return res.json({ status: 'ok', message: 'If the account needs verification, a code was sent.' });
  } catch (error) {
    return next(error);
  }
});

router.post('/recover', authLimiter, async (req, res, next) => {
  try {
    const user = findUserByEmail(req.body?.email);
    let devCode;
    if (user) {
      const issued = await issueCode(user, 'reset_password');
      if (config.devReturnCodes) devCode = issued.code;
    }
    return res.json({
      status: 'ok',
      message: 'If the account exists, a recovery code was sent.',
      devCode,
    });
  } catch (error) {
    return next(error);
  }
});

router.post('/reset-password', authLimiter, async (req, res, next) => {
  try {
    const user = findUserByEmail(req.body?.email);
    const code = String(req.body?.code || '');
    const newPassword = String(req.body?.newPassword || '');
    if (!user) {
      return res.status(400).json({ status: 'error', message: 'Invalid recovery request.' });
    }
    if (newPassword.length < 8) {
      return res.status(400).json({ status: 'error', message: 'Password must be at least 8 characters.' });
    }
    const result = consumeCode(user.id, 'reset_password', code);
    if (!result.ok) {
      return res.status(400).json({ status: 'error', message: 'Invalid or expired recovery code.' });
    }
    db.prepare('UPDATE users SET password_hash = ? WHERE id = ?')
      .run(await hashPassword(newPassword), user.id);
    revokeAllSessions(user.id);
    return res.json({
      status: 'ok',
      ...authPayload(findUserById(user.id), req.body?.deviceId),
    });
  } catch (error) {
    return next(error);
  }
});

router.get('/me', requireAuth, (req, res) => {
  return res.json({
    status: 'ok',
    user: publicUser(req.user),
    entitlement: req.entitlement,
  });
});

router.patch('/me', requireAuth, (req, res) => {
  const displayName = String(req.body?.displayName || '').trim().slice(0, 80) || null;
  db.prepare('UPDATE users SET display_name = ? WHERE id = ?').run(displayName, req.user.id);
  return res.json({
    status: 'ok',
    user: publicUser(findUserById(req.user.id)),
    entitlement: getEntitlement(req.user.id),
  });
});

router.post('/me/logout', requireAuth, (req, res) => {
  revokeSession(req.tokenHash);
  return res.json({ status: 'ok' });
});

router.delete('/me', requireAuth, (req, res) => {
  const userId = req.user.id;
  db.transaction(() => {
    revokeAllSessions(userId);
    db.prepare('DELETE FROM sessions WHERE user_id = ?').run(userId);
    db.prepare('DELETE FROM verification_codes WHERE user_id = ?').run(userId);
    db.prepare('DELETE FROM subscriptions WHERE user_id = ?').run(userId);
    db.prepare('DELETE FROM ad_rewards WHERE user_id = ?').run(userId);
    db.prepare('DELETE FROM payment_orders WHERE user_id = ?').run(userId);
    db.prepare('UPDATE user_cameras SET user_id = NULL WHERE user_id = ?').run(userId);
    db.prepare(
      `UPDATE users SET email = NULL, password_hash = NULL, display_name = NULL,
        phone = NULL, provider_id = NULL, deleted_at = ? WHERE id = ?`,
    ).run(nowSeconds(), userId);
  })();
  return res.json({ status: 'ok' });
});

module.exports = {
  router,
  requireAuth,
  optionalAuth,
  getEntitlement,
  grantSubscription,
  grantAdReward,
  publicUser,
  findUserById,
  limitsFor,
  nowSeconds,
};
