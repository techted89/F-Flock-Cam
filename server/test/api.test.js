const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { once } = require('node:events');

process.env.DB_PATH = ':memory:';
process.env.DEV_RETURN_CODES = 'true';
process.env.SESSION_TTL_SECONDS = '3600';

let mockDeflock;
let mockPort;

function startMockDeflock() {
  return new Promise((resolve) => {
    mockDeflock = http.createServer((req, res) => {
      res.setHeader('content-type', 'application/json');
      if (req.url === '/index.json') {
        res.end(JSON.stringify({
          tile_size_degrees: 20,
          tile_url: `http://127.0.0.1:${mockPort}/{lat}/{lon}.json`,
        }));
        return;
      }
      res.end(JSON.stringify([
        {
          id: 'cam-1',
          lat: 0.001,
          lon: 0.001,
          tags: { description: 'Flock camera', direction: 'N' },
        },
        { id: 'cam-2', lat: 0.5, lon: 0.5, tags: {} },
      ]));
    });
    mockDeflock.listen(0, '127.0.0.1', () => {
      mockPort = mockDeflock.address().port;
      resolve();
    });
  });
}

async function jsonFetch(base, path, options = {}) {
  const response = await fetch(`${base}${path}`, {
    ...options,
    headers: {
      'content-type': 'application/json',
      ...(options.headers || {}),
    },
  });
  const body = await response.json();
  return { status: response.status, body };
}

test('server API smoke path', async (t) => {
  await startMockDeflock();
  process.env.DEFLOCK_TILES_BASE_URL = `http://127.0.0.1:${mockPort}`;

  const app = require('../src/index');
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;

  t.after(() => {
    server.close();
    mockDeflock.close();
  });

  const health = await jsonFetch(base, '/api/health');
  assert.equal(health.status, 200);
  assert.equal(health.body.status, 'ok');

  const email = `smoke-${Date.now()}@example.com`;
  const register = await jsonFetch(base, '/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password: 'long-enough-password' }),
  });
  assert.equal(register.status, 201);
  assert.equal(register.body.user.emailVerified, false);
  assert.match(register.body.devCode, /^\d{6}$/);

  const verify = await jsonFetch(base, '/api/auth/verify-email', {
    method: 'POST',
    body: JSON.stringify({ email, code: register.body.devCode }),
  });
  assert.equal(verify.status, 200);
  assert.equal(verify.body.user.emailVerified, true);
  const token = verify.body.token;
  const authHeaders = { authorization: `Bearer ${token}` };

  const me = await jsonFetch(base, '/api/auth/me', { headers: authHeaders });
  assert.equal(me.status, 200);
  assert.equal(me.body.user.email, email);
  assert.equal(me.body.entitlement.tier, 'free');
  assert.equal(me.body.entitlement.maxRadiusMeters, 2000);

  const region = await jsonFetch(
    base,
    '/api/cameras/region?minLat=-0.04&maxLat=0.04&minLng=-0.04&maxLng=0.04',
    { headers: authHeaders },
  );
  assert.equal(region.status, 200);
  assert.equal(region.body.tier, 'free');
  assert.equal(region.body.cameras.length, 1);
  assert.equal(region.body.cameras[0].id, 'cam-1');
  assert.equal(region.body.cameras[0].directionDegrees, 0);

  const clamped = await jsonFetch(
    base,
    '/api/cameras/nearby?lat=0&lng=0&radius=999999',
    { headers: authHeaders },
  );
  assert.equal(clamped.status, 200);
  assert.equal(clamped.body.radiusMeters, 2000);

  const report = await jsonFetch(base, '/api/cameras/reports', {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({ latitude: 0.01, longitude: 0.01, note: 'new camera' }),
  });
  assert.equal(report.status, 201);
  assert.equal(report.body.moderationStatus, 'PENDING');

  const mine = await jsonFetch(base, '/api/cameras/reports/mine', { headers: authHeaders });
  assert.equal(mine.status, 200);
  assert.equal(mine.body.reports.length, 1);

  const predict = await jsonFetch(base, '/api/routes/predict', {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({
      originLatitude: 0,
      originLongitude: 0,
      destinationLatitude: 0,
      destinationLongitude: 0.002,
      radiusMeters: 200,
    }),
  });
  assert.equal(predict.status, 200);
  assert.ok(Array.isArray(predict.body.cameras));

  const plans = await jsonFetch(base, '/api/payments/plans');
  assert.equal(plans.status, 200);
  assert.equal(plans.body.plans.length, 2);

  const reward = await jsonFetch(base, '/api/ads/reward', {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({ provider: 'admob' }),
  });
  assert.equal(reward.status, 200);
  assert.equal(reward.body.entitlement.tier, 'premium');

  const secondReward = await jsonFetch(base, '/api/ads/reward', {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({ provider: 'admob' }),
  });
  assert.equal(secondReward.status, 429);

  const logout = await jsonFetch(base, '/api/auth/me/logout', {
    method: 'POST',
    headers: authHeaders,
    body: '{}',
  });
  assert.equal(logout.status, 200);

  const afterLogout = await jsonFetch(base, '/api/auth/me', { headers: authHeaders });
  assert.equal(afterLogout.status, 401);
});
