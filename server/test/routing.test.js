process.env.DB_PATH = ':memory:';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  haversineMeters,
  bboxAroundPoints,
  camerasAlongRoute,
  camerasWithinRadius,
  planRouteAvoiding,
} = require('../src/routing');
const { db } = require('../src/db');

test('haversineMeters is close to one degree of latitude', () => {
  const distance = haversineMeters(
    { latitude: 0, longitude: 0 },
    { latitude: 1, longitude: 0 },
  );
  assert.ok(distance > 110000 && distance < 112500, `unexpected distance ${distance}`);
});

test('bboxAroundPoints covers every route point', () => {
  const bbox = bboxAroundPoints(
    [
      { latitude: 40.1, longitude: -74.2 },
      { latitude: 40.9, longitude: -73.4 },
    ],
    200,
  );
  assert.ok(bbox.minLat < 40.1);
  assert.ok(bbox.maxLat > 40.9);
  assert.ok(bbox.minLng < -74.2);
  assert.ok(bbox.maxLng > -73.4);
});

test('camerasAlongRoute keeps cameras inside the corridor only', () => {
  const route = [
    { latitude: 0, longitude: 0 },
    { latitude: 0, longitude: 0.1 },
  ];
  const cameras = [
    { id: 'near', latitude: 0.0001, longitude: 0.05 },
    { id: 'far', latitude: 0.05, longitude: 0.05 },
  ];
  const result = camerasAlongRoute(cameras, route, 50);
  assert.equal(result.length, 1);
  assert.equal(result[0].id, 'near');
  assert.ok(result[0].distanceFromRouteMeters <= 50);
});

test('camerasWithinRadius sorts by distance', () => {
  const result = camerasWithinRadius(
    [
      { id: 'far', latitude: 0, longitude: 0.002 },
      { id: 'near', latitude: 0, longitude: 0.001 },
    ],
    0,
    0,
    500,
  );
  assert.deepEqual(result.map((camera) => camera.id), ['near', 'far']);
});

test('planRouteAvoiding detours around a camera on the straight line', () => {
  const result = planRouteAvoiding(
    { latitude: 0, longitude: 0 },
    { latitude: 0, longitude: 0.01 },
    [{ id: 'blocker', latitude: 0, longitude: 0.005 }],
    { radiusMeters: 60 },
  );
  assert.equal(result.avoided, true);
  assert.ok(result.route.length > 2);
});

test('user camera reports are stored and queried by bbox', () => {
  db.prepare(
    `INSERT INTO user_cameras (device_id, latitude, longitude, source, note, status)
     VALUES ('test-device', 10.5, 20.5, 'USER', 'corner cam', 'PENDING')`,
  ).run();
  const { listUserCamerasInBbox } = require('../src/routing');
  const rows = listUserCamerasInBbox(10, 11, 20, 21);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].name, 'Reported camera (corner cam)');
  assert.equal(rows[0].status, 'PENDING');
});
