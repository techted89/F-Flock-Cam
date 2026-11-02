# F-Flock-Cam

**F-Flock-Cam** is a Kotlin Android application (with a hybrid cloud backend) that helps users avoid flock surveillance camera locations.

## What it does

- Uses the **Deflock** camera data API (or the available camera location data) through a hybrid backend server.
- Serves users’ **flock camera locations** based on a small range or along a user’s mapped direction route.
- Lets users **change / avoid routes** to dodge known flock camera locations.
- Lets users **add locations of known flock cameras**.
- Runs a **remote action runner** (WorkManager + foreground service) that starts / stops monitoring based on actions queued from the backend.

## Repository layout

```
F-Flock-Cam/
├── .github/workflows/          # Android build + release workflows
├── app/                         # Kotlin Android app (Compose + Maps + WorkManager)
├── server/                      # Hybrid backend (Express + SQLite + Deflock proxy + routing)
├── fastlane/                    # Google Play release (supply)
├── scripts/playwright/         # Playwright + stealth browser setup
├── docs/                        # Release, Playwright research, Deflock API, agent config
└── README.md
```

## Tech stack

- **Android app**: Kotlin, Jetpack Compose, Google Maps Compose, Fused Location, Retrofit + kotlinx.serialization, WorkManager (`RemoteActionWorker`), foreground service (`FlockMonitoringService`).
- **Backend**: Node.js, Express, SQLite, Deflock API proxy, route planning / avoidance.
- **Release**: GitHub Actions (build AAB) → Fastlane (`supply`) → Google Play.
- **Browser automation**: Playwright with stealth addons.

## Quick start

### 1. Hybrid backend

```bash
cd server
cp .env.example .env   # set DEFLOCK_API_KEY, PORT, DB_PATH
npm install
npm start
```

### 2. Android app

- Backend base URL is configured via `BuildConfig.SERVER_BASE_URL` (`http://10.0.2.2:8080/` for local emulator / `http://<host>:8080/` for device).
- Build: `./gradlew assembleDebug` (CI: GitHub Actions `Android Build` workflow).

### 3. Release

- Tag a release: `git tag v0.1.0 && git push --tags`.
- GitHub Actions builds a signed AAB and uploads via Fastlane to Google Play.

### 4. Playwright + stealth

```bash
cd scripts/playwright
npm install
npx playwright install chromium
npm run stealth
```

## GitHub remote

```bash
git remote add origin https://github.com/techted89/F-Flock-Cam.git
git push -u origin main
```
