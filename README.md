# F-Flock-Cam

**F-Flock-Cam** is a Kotlin Android app plus a small self-hosted backend that helps drivers see Flock/ALPR camera locations near them or along a route.

## What it does

- Serves **Flock camera locations** for a region, a radius, or a route corridor, sourced from Deflock plus moderated user reports.
- Lets users **search a destination and hand off to Google Maps directions**, then shows cameras along that route.
- Lets users **adjust the awareness radius** with a slider; the app filters the cached region locally so panning and radius changes do not re-hit the server.
- Shows **camera detail**: source, description, manufacturer/operator, and view direction.
- Runs a **remote action runner** (WorkManager + foreground service) that starts/stops monitoring from backend-queued actions.
- Supports **free and premium tiers**: free gets a short range, a reduced camera count, and a rewarded-ad/CPA path; premium ($3 / 4 months or $9 / year) removes ads and raises the range to 50 km with priority server capacity.
- Supports **accounts** (Google sign-in or email/password), verification, recovery, purchase restore, and account deletion.

## Repository layout

```
F-Flock-Cam/
├── .github/workflows/          # Android build + release workflows
├── app/                         # Kotlin Android app (Compose + Maps + Billing + Ads)
├── server/                      # Hybrid backend (Express + SQLite + Deflock proxy + routing)
│   └── deploy/                  # systemd unit, nginx config, env template, install script
├── fastlane/                    # Google Play release (supply)
├── scripts/playwright/         # Playwright + stealth browser setup
├── docs/                        # Implementation plan, release, Playwright, Deflock API notes
└── README.md
```

## Architecture

The backend is split into independent services so identity work never touches camera data:

| Module | Responsibility |
| --- | --- |
| `server/src/user-service.js` | Accounts, sessions, verification/recovery, entitlement, account deletion |
| `server/src/payment-service.js` | Google Play purchase verification, PayPal orders/webhooks, plans |
| `server/src/ad-service.js` | Rewarded-ad/CPA grants with cooldown and expiry |
| `server/src/camera-service.js` | Region/nearby/route camera queries, submission moderation, entitlement caps |
| `server/src/deflock.js` | Deflock tile index + SQLite/in-memory cache + normalization |
| `server/src/routing.js` | Haversine, bbox, route corridor, and avoidance helpers |

The app asks for a **region** (bbox or center+radius) once, caches it for five minutes, and filters by radius and route locally. The server only enforces the tier ceiling, so a single request replaces per-pan and per-route queries.

The server is tuned for a small Ubuntu VPS (4 cores / 8 GB): one Node process, bounded memory caches, gzip + ETag caching, request coalescing, hourly SQLite maintenance, and a `PriorityGate` that reserves capacity for premium users. See `server/deploy/README.md`.

## Quick start

### 1. Backend

```bash
cd server
cp .env.example .env      # fill in secrets; .env is gitignored
npm install               # better-sqlite3 is native (needs python3/make/g++)
npm test                  # unit + API smoke tests
npm start                 # src/index.js loads .env itself
curl -s localhost:8080/api/health
```

Local-only conveniences: `DEV_RETURN_CODES=true` returns verification codes in API responses, and `ALLOW_UNVERIFIED_PURCHASES=true` skips Play/PayPal verification. Never enable either in production.

### 2. Android app

- `SERVER_BASE_URL`, `MAPS_API_KEY`, `GOOGLE_WEB_CLIENT_ID`, `ADMOB_APP_ID`, and `ADMOB_REWARDED_UNIT_ID` come from Gradle properties or environment variables; defaults target an emulator (`http://10.0.2.2:8080/`) with Google's sample AdMob units.
- Release builds require HTTPS; debug builds allow cleartext only for `10.0.2.2`, `127.0.0.1`, and `localhost`.
- Canonical build route: push to GitHub and let the `Android Build` workflow run on `ubuntu-latest`; download the debug APK artifact from the run.
- Local Gradle (`./gradlew assembleDebug`) is optional and requires JDK 17 + Android SDK.

### 3. Deploy the server

```bash
sudo bash server/deploy/install.sh     # Ubuntu: Node 20, systemd, nginx, health check
sudo editor /etc/flockcam/flockcam.env
sudo systemctl restart flockcam
```

Details, TLS, Docker, and the resource profile are in `server/deploy/README.md`.

### 4. Release

- Tag a release: `git tag v0.1.0 && git push --tags`.
- GitHub Actions builds a signed AAB on a GitHub-hosted `ubuntu-latest` runner and uploads it with Fastlane to the selected Play track (default `internal`).

### 5. Playwright + stealth

```bash
cd scripts/playwright
npm install
npx playwright install chromium
npm run stealth
```

## Status

This repository is under active construction. `docs/IMPLEMENTATION_PLAN.md` is the source of truth for what is implemented, what is blocked, and the acceptance criteria for each phase.

## GitHub remote

```bash
git remote add origin https://github.com/techted89/F-Flock-Cam.git
git push -u origin main
```
