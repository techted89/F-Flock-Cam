# AGENTS.md — F-Flock-Cam

Kotlin Android app + Node/Express hybrid backend for Flock/ALPR camera awareness.
Read `docs/IMPLEMENTATION_PLAN.md` before changing product behavior: it is the source of truth for the requested feature set, current gaps, and acceptance criteria.

## Current state (verify before trusting README/docs)

- The repo is no longer a bare scaffold, but nothing Android-side is verified: no CI run or device test has confirmed the Compose code compiles.
- Android has a Compose map with markers, radius slider, destination search + Google Maps hand-off, route polyline and route camera list, camera detail, auth/account/paywall screens, Play Billing, rewarded ads, and Google sign-in. The session token is still stored in plain SharedPreferences.
- Server is CommonJS Express + `better-sqlite3` with migrations, separate `user-service` / `payment-service` / `ad-service` / `camera-service` modules, Play + PayPal verification, ad rewards, entitlement caps, priority gating, rate limits, maintenance, and tests. `npm test` passes locally (11/11).
- The app fetches one entitlement-capped camera region and filters by radius/route locally; the server is not in the per-pan or per-route query path.
- Ubuntu deploy artifacts exist under `server/deploy/` (systemd, nginx, env template, install script) plus a multi-stage `server/Dockerfile`.
- Fastlane metadata and GitHub Actions exist, but the Play release pipeline is incomplete (see blockers). The canonical build/release route is a **GitHub-hosted `ubuntu-latest` runner**; do not rely on Termux for Android builds.
- Playwright currently only launches a stealth browser; Play Console / Cloud Console login/configuration automation still must be written and run.

## Commands

### Android builds — use GitHub Actions, not Termux

The build/release route is a remote GitHub-hosted Linux runner:

- `.github/workflows/android-build.yml` runs on push/PR to `main` (debug APK + tests).
- `.github/workflows/android-release.yml` runs on `v*` tags (or manual dispatch) to build the signed AAB and publish it.

```bash
git push origin HEAD
gh run list --workflow=android-build.yml
gh run watch
gh run download <run-id> -n app-debug-apk
```

Local Gradle commands are for IDE/inspection only. They require JDK 17 + Android SDK; this Termux checkout does not have them.

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew bundleRelease   # needs KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
```

Gradle wrapper is 8.7. Do not claim a build or release succeeded unless the GitHub Actions run is green and the artifact/track upload is verified.

### Server (Node >= 20)

```bash
cd server
cp .env.example .env       # canonical secrets file, already gitignored
npm install                # better-sqlite3 is native: needs python3/make/g++
npm test                   # node --test: unit + API smoke path
npm run check              # syntax check of the main modules
npm start                  # src/index.js requires ./env, so .env is loaded
curl -s localhost:8080/api/health
```

`better-sqlite3` is pinned to `^12` so it builds against Node 20 (CI) and newer local Node releases. On Termux, `npm install` may need `npm install-scripts approve better-sqlite3` before the native build runs.

### Playwright — required for Play/Cloud console setup

The agent must use Playwright to perform the Google Cloud Console and Play Console setup, not just document it:

```bash
cd scripts/playwright
npm install
npx playwright install chromium
set -a; . ../../server/.env; set +a   # loads SERVICE_EMAIL / SERVICE_PASS
```

Required automation behavior:

- Log in to `console.cloud.google.com` and `play.google.com/console` with `SERVICE_EMAIL` / `SERVICE_PASS`.
- Use a persistent browser profile at `scripts/playwright/.auth/` (gitignored) so the session survives between steps.
- Add setup scripts under `scripts/playwright/src/` for: Cloud project/API enablement, OAuth consent, service account creation/key download, Play Console app/API access, app content declarations, subscriptions, and track upload/verification.
- When Google shows 2FA/CAPTCHA or a security challenge, pause and ask the user to approve it manually, then continue. Never bypass the challenge.
- Run headed or via a visible remote-debug browser when headless login is rejected. Use `gcloud`/Play Developer API only for verification or steps Playwright cannot perform.
- Do not mark console setup complete until Playwright has executed the configuration and the resulting state is verified by reading it back.
- If this environment cannot run Playwright, install Node/Chromium or ask the user for a Playwright-capable host. Do not substitute a manual checklist for execution.

`npm run stealth` still only launches the browser; the console setup scripts must be added.

### Release — GitHub-hosted runner

```bash
git tag vX.Y.Z && git push --tags      # triggers .github/workflows/android-release.yml
gh run watch
gh run download <run-id> -n app-release-aab
```

The release workflow builds the signed AAB and publishes it with Fastlane on `ubuntu-latest`. Local Fastlane is only a fallback and still needs `AAB_PATH` set explicitly because the Fastfile default is relative:

```bash
gem install fastlane
AAB_PATH=app/build/outputs/bundle/release/app-release.aab PLAY_TRACK=internal \
  fastlane android android_play_store
```

## Architecture

- App entrypoint: `MainActivity` → `FlockCamApp` → `FlockMapScreen`. `FlockCamApplication` owns `FlockLocationMonitor`, `FlockApi`, `SessionStore`, repositories, `BillingManager`, `RewardedAdManager`, and `RemoteActionRepository`; there is no DI framework.
- API: Retrofit interface in `data/remote/FlockApi.kt`, DTOs in `data/model/CameraModels.kt`, base URL from `BuildConfig.SERVER_BASE_URL` (Gradle property/env `SERVER_BASE_URL`, default emulator loopback).
- App data flow: `CameraRepository` fetches `/api/cameras/region` once per radius/location change (5-minute snapshot cache), then filters locally by radius and route. `/api/cameras/route` is only a fallback for oversized route bboxes.
- Background: `RemoteActionWorker` polls `/api/actions/pending`; `FlockMonitoringService` is a foreground location service.
- Server entrypoint: `server/src/index.js` mounts `/api/auth`, `/api/users`, `/api/payments`, `/api/ads`, `/api/cameras`, `/api/routes`, `/api/actions`, and `/api/health`.
  - `user-service.js` = accounts, sessions, recovery, entitlement, deletion (does not import the camera index).
  - `camera-service.js` = region/nearby/route queries, moderation queue, entitlement caps, request coalescing.
  - `payment-service.js` = Play service-account verification + PayPal orders/webhooks.
  - `ad-service.js` = rewarded-ad grants; `priority.js` = premium-reserved concurrency; `rate-limit.js` = bounded limiter.
  - `deflock.js` = tile index/cache + normalization; `routing.js` = haversine/bbox/corridor/avoidance; `maintenance.js` = hourly pruning.
  - `db.js` = connection + WAL + migrations; `migrations.js` = schema source; `env.js` = `.env` loader.
- Deploy: `server/deploy/flockcam.service` (systemd, memory/CPU caps, hardening), `server/deploy/nginx.conf` (TLS + rate limit), `server/deploy/install.sh`, `server/deploy/flockcam.env.example`, and a multi-stage `server/Dockerfile`.
- Release: tag → GitHub-hosted `ubuntu-latest` runner → signed AAB → Fastlane `supply`; store text lives in `fastlane/metadata/en-US/`.
- Playwright: `scripts/playwright/src/stealth.js` only launches a browser; console login/configuration scripts still need to be written and executed.

## Conventions

- Kotlin package `com.techted89.flockcam`; Jetpack Compose + Material3 only. Keep API DTOs `@Serializable` in `data/model`.
- Server is plain CommonJS, 2-space indent, no TypeScript. Add server tests before refactoring routing/priority logic.
- Keep Play Store text in `fastlane/metadata/` consistent with shipped behavior.
- Do not add analytics/tracking or collect user identity beyond what login, recovery, payment, and abuse prevention require.

## Secrets and Google account

- Google account: `theodoredrach@gmail.com` (`SERVICE_EMAIL`).
- `SERVICE_PASS` lives **only** in gitignored `server/.env`. It is used for Play Console, Cloud Console, and any Google OAuth login that prompts for 2FA. Never copy it into tracked files, CI logs, docs, or commit history.
- If `server/.env` is missing, ask the user for the credential; do not guess.
- 2FA: when Google presents a verification dialog, pause and ask the user to approve it manually. Never bypass 2FA/CAPTCHA, and never reuse another account's cookies.
- The agent must use Playwright with a persistent profile to perform the Cloud/Play console configuration. Use `gcloud`, the Play Developer API, and Fastlane only for verification or steps Playwright cannot perform. Stealth is compatibility only, not a security bypass.

## Required Google/Play setup (must be completed, not just documented)

- The agent is responsible for **executing** this setup through Playwright using `SERVICE_EMAIL` / `SERVICE_PASS`, pausing for manual 2FA. Do not hand this back as a list of manual instructions.
- Google Cloud project with **Google Play Android Developer API** and **Maps SDK for Android** enabled; OAuth consent configured.
- Service account with Android Publisher access, JSON key stored only as a gitignored secret; service account added under Play Console → Setup → API access.
- Play Console app `com.techted89.flockcam`, API access, app content, privacy policy, data safety, content rating, target audience, ads declaration, and subscription products ($3.00 / 4 months; $9.00 / year).
- Internal → closed → production track release with a signed AAB. Do not declare release complete until the AAB is on a Play track, the service account/API access is verified, store listing/privacy policy are live, and purchase/restore flows are verified end-to-end.

## Product requirements (must ship)

- **Map**: current location, destination search + Google Maps directions, camera markers on the current route, camera detail with description and view/vision direction, Flock/ALPR source.
- **Radius mode**: user-adjustable radius via slider; free tier capped, premium unlimited.
- **Free tier**: reduced visible camera count/range and a short ad or CPA task for a temporary premium session. Include a small UI explanation of why ads exist.
- **Premium tier**: $3.00/4 months or $9.00/year; unlimited range, no ads, route camera list, Google Maps integration, camera description/direction, and priority server access.
- **Accounts**: Google OAuth or username/password; minimal PII; secure password hashing; email or mobile 2FA recovery; profile/logout/account deletion.
- **Payments**: PayPal and Google Pay/Play Billing, with server-side purchase verification, entitlement sync, restore, and expiry.
- **Server**: Ubuntu VPS service (systemd or Docker), Deflock retrieval/caching, validated user camera submissions/moderation, fast nearby and route queries, paid users prioritized under load, ad/CPA reward verification.
- **Packaging**: server must run on Ubuntu as a service or a functional single-file Linux binary; document the deploy path and env requirements.

## Known blockers (fix before feature work)

- Android has never been compiled in this workspace. Run `android-build.yml` and fix compile/lint failures; do not assume the Compose/Kotlin code is correct.
- The session token is stored in plain SharedPreferences; move it to EncryptedSharedPreferences/Keystore before release.
- Camera direction is shown in the detail sheet but not rendered as a map cone.
- `.github/workflows/android-release.yml` needs the real keystore/Play secrets and a Play app with API access before it can publish.
- No real Maps API key, AdMob IDs, Google web client ID, PayPal credentials, or Play subscription products are configured.
- No Playwright console scripts exist; Cloud/Play setup has not been executed.
- No device/E2E test, load test, or store-compliance read-back has been done.

## Definition of done

- GitHub Actions `Android Build` is green on `ubuntu-latest`; no local/Termux build is used as the release proof.
- Server starts from `.env`, `/api/health` is OK, `npm test` passes, and the Ubuntu deploy path is verified on a real host.
- Free/premium gating, ads/CPA, auth/recovery, PayPal + Play purchases, route cameras, directions, radius slider, and camera-direction UI are verified on a device.
- Playwright has executed and verified the Cloud/Play console configuration, including the service account, API access, app content, subscriptions, and track upload.
- Release is live on a Play track with verified API access, store compliance, subscriptions, and privacy policy.

See `docs/IMPLEMENTATION_PLAN.md` for phased work and per-feature acceptance criteria.
