require('./env');
const express = require('express');
const cors = require('cors');
const compression = require('compression');
const config = require('./config');
const { db } = require('./db');
const { rateLimit } = require('./rate-limit');
const { startMaintenance } = require('./maintenance');
const actionsApi = require('./actions');
const userService = require('./user-service');
const paymentService = require('./payment-service');
const adService = require('./ad-service');
const cameraService = require('./camera-service');

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', config.trustProxy);
app.use(compression({ threshold: 1024 }));
app.use(cors());
app.use(express.json({ limit: '256kb' }));

// Cheap, dependency-free hardening. The API is JSON-only and never framed.
app.use((req, res, next) => {
  res.set('X-Content-Type-Options', 'nosniff');
  res.set('X-Frame-Options', 'DENY');
  res.set('Referrer-Policy', 'no-referrer');
  res.set('Permissions-Policy', 'geolocation=(), microphone=(), camera=()');
  next();
});

// Broad abuse ceiling. Route-specific limiters below are tighter.
app.use('/api', rateLimit({ windowMs: 60_000, max: 600, maxKeys: 10_000 }));

const DEVICE_ID = process.env.DEVICE_ID || 'anonymous-device';

app.get('/api/health', (req, res) => {
  res.set('Cache-Control', 'no-store');
  res.json({
    status: 'ok',
    service: 'F-Flock-Cam hybrid server',
    version: require('../package.json').version,
    now: userService.nowSeconds(),
  });
});

// User management is deliberately isolated from the camera index. Auth and
// entitlement routes never read or write camera location tables.
app.use('/api/auth', userService.router);
app.use('/api/users', userService.router);
app.use('/api/payments', paymentService.router);
app.use('/api/ads', adService.router);

// Camera index: region fetch, nearby compatibility, route corridors, reports.
const cameraLimiter = rateLimit({ windowMs: 60_000, max: 120 });
app.use('/api/cameras', cameraLimiter, cameraService.router);
app.use('/api/routes', cameraLimiter, cameraService.routeAliasRouter);

// Legacy remote-action queue used by the Android foreground service.
app.get('/api/actions/pending', (req, res) => {
  const deviceId = req.query.deviceId || DEVICE_ID;
  return res.json({ actions: actionsApi.listPending(deviceId) });
});

app.post('/api/actions', (req, res) => {
  const { deviceId, action, source } = req.body || {};
  if (!action) {
    return res.status(400).json({ status: 'error', message: 'action is required.' });
  }
  const created = actionsApi.createPending(deviceId || DEVICE_ID, action, source);
  return res.json({ status: 'QUEUED', actionId: created.id });
});

app.use((req, res) => {
  res.status(404).json({ status: 'error', message: 'Not found.' });
});

// Central error handler. Never leak stack traces or secrets to clients.
// eslint-disable-next-line no-unused-vars
app.use((error, req, res, next) => {
  // eslint-disable-next-line no-console
  console.error(`[error] ${req.method} ${req.originalUrl}: ${error.message}`);
  const status = error.status && Number.isInteger(error.status) ? error.status : 500;
  res.status(status).json({
    status: 'error',
    message: status === 500 ? 'Internal server error.' : error.message,
  });
});

if (require.main === module) {
  startMaintenance(db);
  const server = app.listen(config.port, config.host, () => {
    // eslint-disable-next-line no-console
    console.log(`F-Flock-Cam server listening on ${config.host}:${config.port}`);
  });
  // Bound slow clients so one connection cannot pin a worker indefinitely.
  server.keepAliveTimeout = 65_000;
  server.headersTimeout = 66_000;
  server.requestTimeout = 30_000;

  let shuttingDown = false;
  const shutdown = () => {
    if (shuttingDown) return;
    shuttingDown = true;
    const forceExit = setTimeout(() => process.exit(1), 10_000);
    if (typeof forceExit.unref === 'function') forceExit.unref();
    server.close(() => {
      try {
        db.close();
      } catch (error) {
        // ignore close errors during shutdown
      }
      process.exit(0);
    });
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

module.exports = app;
