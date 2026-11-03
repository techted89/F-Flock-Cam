# F-Flock-Cam — Deflock API & Hybrid Server

The F-Flock-Cam backend is a **hybrid server**: it merges the public Deflock flock camera catalog with user-reported flock camera locations, and serves route prediction / avoidance to the Android app.

## Deflock data source (discovered)

The F-Flock-Cam server pulls live flock camera data from the Deflock public CDN:

- Tiles index: `GET https://cdn.deflock.me/regions/index.json`
  - `{ expiration_utc, regions: string[], tile_url: "https://cdn.deflock.me/regions/{lat}/{lon}.json?v=...", tile_size_degrees: 20 }`
- Camera tiles: `GET https://cdn.deflock.me/regions/{lat}/{lon}.json` → JSON array of cameras:
  - `{ id: number, lat: number, lon: number, tags: { direction, manufacturer, operator, ... } }`
- Camera counts: `GET https://cdn.deflock.me/alpr-counts.json`

The server caches the tiles index in SQLite (`deflock_cache`) and fetches camera tiles covering a bounding box derived from the query point / route.

## Server

- Stack: Node.js + Express + SQLite (`better-sqlite3`), containerized via `server/Dockerfile`.
- Config: `server/.env` (see `server/.env.example`).

## Endpoints

| Method | Path                     | Purpose |
| ------ | ------------------------- | ------- |
| GET    | `/api/health`             | Health check. |
| GET    | `/api/cameras/nearby`    | Cameras within `radius` meters of `lat`/`lng` (Deflock tiles + user cameras). |
| POST   | `/api/cameras`            | Submit / store a user-reported flock camera location. |
| POST   | `/api/routes/predict`    | Camera list along a route within radius. |
| POST   | `/api/routes/avoid`      | Compute a route avoiding known flock camera locations. |
| GET    | `/api/actions/pending`   | Pending remote actions for a device. |
| POST   | `/api/actions`            | Queue a remote action for a device. |

## Deflock integration

- `server/src/deflock.js` fetches camera tiles via the Deflock CDN index + tiles, normalizes entries to `{ id, latitude, longitude, source, manufacturer, operator, directionDegrees }`, and caches.
- `server/src/routing.js` provides haversine distance, point-to-segment distance, within-range checks, route distance, straight-line routes and grid-based route avoidance (Dijkstra over a coarse grid with camera cells blocked).
- User cameras are stored in SQLite (`user_cameras`) and merged with Deflock data per request.

## App integration

- Android app (`app/`) exposes the same operations through the `FlockApi` Retrofit interface.
- The app background feature uses the **remote action runner** (`RemoteActionWorker` + `FlockMonitoringService`) to start/stop monitoring based on actions queued via `/api/actions`.
