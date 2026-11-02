const express = require('express');
const cors = require('cors');
const { db } = require('./db');
const { fetchDeflockCameras } = require('./deflock');
const { planRouteAvoiding, straightLineRoute, camerasAlongRoute, haversineMeters } = require('./routing');
const actionsApi = require('./actions');

const app = express();
app.use(cors());
app.use(express.json());

const PORT = Number(process.env.PORT || 8080);
const DEVICE_ID = process.env.DEVICE_ID || 'anonymous-device';

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', service: 'F-Flock-Cam hybrid server' });
});

app.get('/api/cameras/nearby', async (req, res) => {
  const latitude = Number(req.query.lat);
  const longitude = Number(req.query.lng);
  const radius = Number(req.query.radius || 500);
  if (Number.isNaN(latitude) || Number.isNaN(longitude)) {
    return res.status(400).json({ status: 'error', message: 'lat and lng are required' });
  }

  const deflockCameras = await fetchDeflockCameras();
  const userCameras = listUserCamerasWithinRadius(latitude, longitude, radius);
  const merged = [...userCameras];
  for (const camera of deflockCameras) {
    if (camera.latitude == null || camera.longitude == null) continue;
    if (haversineMeters(camera, { latitude, longitude }) > radius) continue;
    if (merged.some((existing) => existing.id === camera.id)) continue;
    merged.push({
      ...camera,
      distanceMeters: haversineMeters(camera, { latitude, longitude }),
    });
  }
  merged.sort((a, b) => (a.distanceMeters ?? Number.MAX_SAFE_INTEGER) - (b.distanceMeters ?? Number.MAX_SAFE_INTEGER));

  return res.json({ cameras: merged, source: 'HYBRID', cached: true });
});

app.post('/api/cameras', (req, res) => {
  const { deviceId, latitude, longitude, source, note } = req.body || {};
  if (latitude == null || longitude == null) {
    return res.status(400).json({ status: 'error', message: 'latitude and longitude are required' });
  }
  const info = db
    .prepare('INSERT INTO user_cameras (device_id, latitude, longitude, source, note) VALUES (?, ?, ?, ?, ?)')
    .run(deviceId || DEVICE_ID, latitude, longitude, source || 'USER', note || null);
  return res.json({ status: 'ok', cameraId: Number(info.lastInsertRowid) });
});

app.post('/api/routes/predict', (req, res) => {
  const { originLatitude, originLongitude, destinationLatitude, destinationLongitude, radiusMeters } = req.body || {};
  const result = planRouteAvoiding(
    { latitude: originLatitude, longitude: originLongitude },
    { latitude: destinationLatitude, longitude: destinationLongitude },
    [],
    { radiusMeters: radiusMeters || 500 },
  );
  return res.json({ route: result.route, cameras: result.cameras });
});

app.post('/api/routes/avoid', async (req, res) => {
  const { originLatitude, originLongitude, destinationLatitude, destinationLongitude, radiusMeters } = req.body || {};
  const deflockCameras = await fetchDeflockCameras();
  const userCameras = listUserCamerasWithinRadius(originLatitude, originLongitude, radiusMeters || 500);
  const avoidCameras = [...userCameras, ...deflockCameras]
    .filter((camera) => camera.latitude != null && camera.longitude != null)
    .slice(0, 50);

  const result = planRouteAvoiding(
    { latitude: originLatitude, longitude: originLongitude },
    { latitude: destinationLatitude, longitude: destinationLongitude },
    avoidCameras,
    { radiusMeters: radiusMeters || 500 },
  );

  await new Promise((resolve) => setTimeout(resolve, 0));
  return res.json({ route: result.route, cameras: result.cameras, avoided: result.avoided });
});

app.get('/api/actions/pending', (req, res) => {
  const deviceId = req.query.deviceId || DEVICE_ID;
  return res.json({ actions: actionsApi.listPending(deviceId) });
});

app.post('/api/actions', (req, res) => {
  const { deviceId, action, source } = req.body || {};
  const created = actionsApi.createPending(deviceId || DEVICE_ID, action, source);
  return res.json({ status: 'QUEUED', actionId: created.id });
});

app.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`F-Flock-Cam server listening on port ${PORT}`);
});

module.exports = app;
