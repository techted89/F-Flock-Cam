# F-Flock-Cam — Deflock API & Hybrid Server

The F-Flock-Cam backend is a **hybrid server**: it merges the public Deflock flock camera catalog with user-reported flock camera locations, and serves route prediction / avoidance to the Android app.

## Server

- Stack: Node.js + Express + SQLite (`better-sqlite3`), containerized via `server/Dockerfile`.
- Config: `server/.env` (see `server/.env.example`).

## Endpoints

| Method | Path                     | Purpose |
| ------ | ------------------------- | ------- |
| GET    | `/api/health`             | Health check. |
| GET    | `/api/cameras/nearby`    | Cameras within `radius` meters of `lat`/`lng`. |
| POST   | `/api/cameras`            | Submit / store a user-reported flock camera location. |
| POST   | `/api/routes/predict`    | Camera list along a straight route within radius. |
| POST   | `/api/routes/avoid`      | Compute a route avoiding known flock camera locations. |
| GET    | `/api/actions/pending`   | Pending remote actions for a device. |
| POST   | `/api/actions`            | Queue a remote action for a device. |

## Deflock integration

- `server/src/deflock.js` proxies the Deflock camera catalog.
- Optional API key via `DEFLOCK_API_KEY`; cache TTL via `DEFLOCK_CACHE_TTL_SECONDS` (SQLite cache table `deflock_cache`).
- Camera mapping normalizes common field names (`latitude`/`lat`, `longitude`/`lng`/`lon`).

## Hybrid logic

- `server/src/routing.js` provides haversine distance, point-to-segment distance, within-range checks, route distance, straight-line routes and grid-based route avoidance (Dijkstra over a coarse grid with camera cells blocked).
- User cameras are stored in SQLite (`user_cameras`) and merged with Deflock data per request.

## App integration

- Android app (`app/`) exposes the same operations through the `FlockApi` Retrofit interface.
- The app background feature uses the **remote action runner** (`RemoteActionWorker` + `FlockMonitoringService`) to start/stop monitoring based on actions queued via `/api/actions`.
