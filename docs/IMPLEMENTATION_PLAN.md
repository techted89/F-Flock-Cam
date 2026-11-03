# F-Flock-Cam Implementation Plan

This is the working plan for turning the current scaffold into a production Android app plus a deployable Ubuntu backend. Update statuses as work lands. Do not mark a phase complete until its acceptance criteria are verified.

Status: `TODO` | `IN PROGRESS` | `DONE` | `BLOCKED`

Last verified: server unit/API tests pass locally (`npm test`, 11/11); Android build and console automation are **not** verified.

## Architecture

The product is split so the server never carries per-user camera filtering work:

```
Android app ──HTTPS──> nginx ──> Node/Express
                                  ├── /api/auth, /api/users      user-service.js
                                  ├── /api/payments              payment-service.js
                                  ├── /api/ads                   ad-service.js
                                  └── /api/cameras, /api/routes  camera-service.js
                                                                  └── deflock.js (tile cache)
                                                                  └── routing.js (geometry)
                                      SQLite (WAL) via db.js + migrations.js
```

- **User management is isolated from camera serving.** `user-service.js` owns identity, sessions, recovery, entitlement, and account deletion. It never imports the camera index.
- **The camera service is a location index.** It returns a compact, entitlement-capped region; the app filters that region locally by radius and route. Route filtering only falls back to `/api/cameras/route` when the route bbox is too large for a region response.
- **Entitlement is server-authoritative.** Every camera response is capped by the caller's tier; the app's local filtering is a UX optimization, not a security boundary.

### Why this is cheap to run

| Concern | Mechanism |
| --- | --- |
| RAM | Single Node process, bounded in-memory tile cache (192 entries), SQLite 16 MB page cache, `MemoryMax=900M` in systemd. |
| CPU | scrypt runs off the event loop; identical region requests are coalesced; Deflock tiles are fetched 6-at-a-time and cached; compact JSON avoids re-serializing nulls. |
| Bandwidth | gzip at 1 KB+, compact camera JSON, `ETag`/`Cache-Control: private, max-age=120`, client HTTP cache, app-side region cache (5 min). |
| Contention | `PriorityGate` reserves concurrency for premium and makes free work yield while premium requests are active. |
| Data growth | Hourly maintenance prunes expired sessions/codes/rewards, finished actions, and week-old cached tiles. |

## Verified baseline

| Area | Current state |
| --- | --- |
| Android UI | Compose map with markers, radius slider, destination search + Google Maps hand-off, route polyline and route camera list, camera detail sheet, account, auth, paywall, ad/CPA entry points. Not yet compiled/run. |
| Android data | Retrofit API, session store, auth/camera/directions repositories, Play Billing, AdMob rewarded ads, Credential Manager Google sign-in, region cache + local filtering. |
| Android build | Kotlin 2.0.21, AGP 8.5.2, Gradle 8.7; Maps/AdMob placeholders and `BuildConfig` fields wired. Needs real keys, a keystore, and a green CI run. |
| Server | Express + SQLite with migrations, auth/sessions/recovery, Play + PayPal verification, ad rewards, entitlement caps, Deflock tile cache, region/nearby/route queries, moderation queue, rate limits, priority gate, tests, systemd/nginx/Docker deploy. |
| Release | GitHub Actions build/release workflows exist; publish still needs real keystore/Play secrets and a Play app with API access. |
| Console automation | Playwright stealth launcher only. No Play/Cloud login or configuration scripts yet. |

## Phase 0 — Stabilize the build and release skeleton — `IN PROGRESS`

**Goal:** a green build and a release path that can actually upload an AAB.

0. Use **GitHub-hosted `ubuntu-latest` runners** for all Android builds/releases. Do not treat Termux as a build host. The debug workflow runs unit tests + `assembleDebug`; the release workflow builds/publishes the AAB.
1. Android compile blockers: `ENABLE_REMOTE_ACTION_RUNNER` is declared, `viewModel()` is imported, and `FlockMapScreen` uses `Polyline(points = ...)` with `MarkerState`. Remaining risk: unverified Compose/Kotlin compile errors.
2. Maps API key: read from `MAPS_API_KEY` (Gradle property or env) and injected as the manifest placeholder; CI must pass it.
3. CI: `android-build.yml` runs unit tests + `assembleDebug`; add `lintDebug` once the build is green.
4. Server startup: `src/index.js` requires `./env` first, so `npm start` loads `server/.env`. `engines.node >= 20`. `/api/health` is covered by `test/api.test.js`.
5. Release workflow: `build-release` uploads the AAB; `publish-play` downloads it, writes the service-account JSON, and passes it via `--json_key`; `workflow_dispatch` supports track selection (default `internal`).
6. `.gitignore` covers service-account JSON, Playwright auth state, `server/data/`, SQLite WAL/SHM, `node_modules/`, and `.env`.

**Acceptance:** GitHub Actions `Android Build` is green on `ubuntu-latest`, unit tests run, server starts with `.env`, and the release workflow can upload a test AAB to the internal track using the service-account secret.

## Phase 1 — Backend foundation — `IN PROGRESS`

**Goal:** a deployable, secure backend that can serve free and paid users.

### 1.1 Persistence and migrations — `DONE`

- `migrations.js` is the schema source; `db.js` only opens the connection and applies migrations.
- Tables: `users`, `sessions`, `verification_codes`, `user_cameras`, `subscriptions`, `payment_orders`, `ad_rewards`, `pending_actions`, `deflock_cache`, `schema_migrations`.
- WAL, foreign keys, `synchronous=NORMAL`, 16 MB cache, and indices on session expiry, camera lat/lng, and subscription expiry.

### 1.2 Auth and privacy — `DONE` (code), `BLOCKED` (delivery/2FA verification)

- Email/password with async `scrypt` (`N=16384, r=8, p=1`), minimum length, normalized email.
- Google sign-in verified server-side against Google tokeninfo; the client email is never trusted.
- Opaque session tokens; only SHA-256 hashes are stored; per-device sessions; logout revokes one session, password reset revokes all.
- Recovery: six-digit, single-use, attempt-limited, hashed codes; email or SMS delivery via webhook; codes returned in responses only when `DEV_RETURN_CODES=true`.
- Profile update, entitlement read, logout, and full account deletion (sessions, codes, subscriptions, rewards, payment orders; camera reports are anonymized).
- Rate limits on auth, payments, ads, and camera routes; input validation on every body/query.

### 1.3 Camera data service — `DONE`

- Deflock tile index/cache in SQLite plus a bounded in-memory hot cache; parallel tile fetch (concurrency 6); stale-cache fallback during upstream outages.
- Normalized camera model: id, source (`DEFLOCK`/`USER`), lat/lng, description, `directionDegrees`, `viewDirection`, manufacturer, operator, confidence.
- `/api/cameras/region` (bbox or lat/lng), `/api/cameras/nearby`, `/api/cameras/route` (and `POST /api/routes/predict|avoid` aliases) with corridor filtering.
- User submissions are validated and stored `PENDING`; `/api/cameras/reports/mine` shows the reporter their own queue.
- Compact JSON, gzip, ETag/`Cache-Control`, and identical-region request coalescing.

### 1.4 Free/premium service policy — `DONE`

- Server is authoritative for tier and entitlements; the client cannot raise its own caps.
- Free: 2 km radius, 25 visible cameras, 250 cameras per region, and a bbox span guard (`maxRadius*2.5 + 5 km`) so a modified client cannot page out the paid dataset.
- Premium: 50 km radius, unlimited visible cameras, 5000 cameras per region, no ads.
- `PriorityGate` reserves concurrency for premium and makes free requests yield while premium work is active.
- Ad rewards are server-granted, non-stacking, cooldown-limited, and expire.

### 1.5 Payments — `DONE` (code), `BLOCKED` (real credentials)

- Google Play: service-account JWT → Android Publisher `purchases.subscriptionsv2.get`; validates package, product, and expiry; idempotent by purchase token.
- PayPal: create/capture orders, webhook signature verification, order idempotency.
- Products: `premium_4_months` ($3.00) and `premium_1_year` ($9.00), defined once in `config.js` and served by `/api/payments/plans`.
- `ALLOW_UNVERIFIED_PURCHASES=true` is a local-only bypass; production must leave it false.

### 1.6 Deployment — `DONE` (artifacts), `BLOCKED` (host verification)

- `server/deploy/flockcam.service`: systemd unit with `MemoryMax=900M`, `CPUQuota=150%`, `ProtectSystem=strict`, `NoNewPrivileges`, and a read-write path limited to `/var/lib/flockcam`.
- `server/deploy/flockcam.env.example`: production env template (loopback bind, persistent DB, Play service-account file, PayPal live URL).
- `server/deploy/nginx.conf`: TLS termination, HTTP→HTTPS redirect, per-IP rate limit, buffering, double-gzip disabled.
- `server/deploy/install.sh`: idempotent Ubuntu provisioning (Node 20, system user, `npm ci --omit=dev`, unit + nginx install, health check).
- `server/Dockerfile`: multi-stage build, unprivileged `node` user, persistent `/data` volume, health check; `server/.dockerignore` keeps secrets/tests out of the image.
- `server/deploy/README.md`: first install, TLS, redeploy, Docker alternative, resource profile, and post-deploy checks.

**Acceptance:** fresh Ubuntu host can deploy from docs; migrations run; auth/recovery works; nearby/route queries return normalized cameras; free/premium limits enforced; test purchases from Play and PayPal activate and expire correctly.

## Phase 2 — Android product — `IN PROGRESS`

**Goal:** production-ready Compose UI covering the full user journey.

### 2.1 Navigation and screens — `DONE` (code)

- App shell: Map, Camera detail sheet, Account/Profile, Auth, Premium/Paywall.
- Map: location, destination search, Google Maps hand-off, route polyline, route camera list, radius slider, markers.
- Camera detail: source, description, direction/view direction, distance, report action.
- Account: sign in/register, Google sign-in, recovery, profile, entitlement, restore purchases, delete account.
- Paywall: plan cards ($3 / 4 months, $9 / year), benefits, Play + PayPal, restore, renewal notice.
- Ad gate: rewarded ad entry point with the "why ads exist" notice.

### 2.2 Map behavior — `IN PROGRESS`

- Progressive location permission with denial handling; foreground monitoring service.
- Markers colored by source; camera direction shown in the detail sheet.
- Radius slider debounced through region refetch; free tier clamps the maximum and shows the upsell.
- Destination → Google Maps directions hand-off; route cameras filtered locally from the cached region.
- Recenter only after 250 m of movement so the camera does not fight the user.
- TODO: offline/empty-state polish, rotation state retention audit, direction cone rendering on the map.

### 2.3 Free/premium and ads — `DONE` (code)

- Entitlement persisted locally but re-checked on launch/resume.
- Free users get a capped radius/count and a rewarded-ad path to a 30-minute premium window; the ad-reason notice is shown.
- Premium unlocks unlimited radius, no ads, route list, directions, and camera metadata.
- Ad failure never blocks the app: the UI falls back to the upgrade path.

### 2.4 Accounts and payments — `DONE` (code)

- Google Sign-In (Credential Manager) with server ID-token exchange.
- Email/password registration/login, verification, and recovery screens.
- Play Billing for Play purchases; PayPal via browser redirect and manual capture confirmation.
- Restore purchases, pending/cancelled states surfaced, expiry shown in Account.
- TODO: move the session token from SharedPreferences to EncryptedSharedPreferences/Keystore.

### 2.5 Hardening — `TODO`

- Android 13 notification permission and Android 14 foreground-service type rules.
- Battery/location accuracy controls; clean monitoring shutdown.
- Accessibility pass: content descriptions, touch targets, contrast, font scaling.
- Unit tests for radius/gating/route math; Compose UI tests for paywall/ad gate; device matrix.

**Acceptance:** a user can install, sign in (Google or password), recover access, use free mode with ad gate, buy either plan, get premium features, see route cameras/directions, adjust radius, and delete the account.

## Phase 3 — Google Cloud / Play Console release automation — `BLOCKED`

**Goal:** complete the console setup and publish through Play.

### 3.1 Cloud project and APIs

- Create/select the Google Cloud project; record the project ID in gitignored env.
- Enable Google Play Android Developer API and Maps SDK for Android.
- Configure OAuth consent (app name, support email, privacy policy, scopes).
- Create the Android Maps API key, restrict it to `com.techted89.flockcam` + release SHA-1.
- Create a service account, grant Android Publisher access, download JSON to a gitignored path, and add the service account email in Play Console → Setup → API access.

### 3.2 Play Console app

- Create/verify app `com.techted89.flockcam`.
- Complete app content: privacy policy, data safety, content rating, target audience, ads declaration, news/app type, government/health declarations as applicable.
- Create subscription products `premium_4_months` and `premium_1_year` with the required prices and base plans.
- Set up internal testing, license testers, and a closed track if needed.
- Upload a signed AAB; verify the Play Developer API can read/write the track.

### 3.3 Playwright console automation (required)

The agent must execute the console setup with Playwright, not just document it.

- Add scripts under `scripts/playwright/src/` for:
  - Google login with `SERVICE_EMAIL` + `SERVICE_PASS` from `server/.env`.
  - Persistent context at `scripts/playwright/.auth/` (gitignored).
  - Cloud Console configuration: project selection, API enablement, OAuth consent, service account creation/key download.
  - Play Console configuration: app creation/verification, API access, app content declarations, subscriptions, testing tracks, AAB upload.
- On any 2FA/CAPTCHA/security prompt: stop, ask the user to approve it manually, then resume. Never bypass or automate the challenge.
- Use `gcloud`/Play Developer API/Fastlane only for verification or steps Playwright cannot perform.
- Add a verification step that reads back the resulting state (API enabled, service account present, subscriptions present, track/AAB present) and records it in a run log without secrets.
- Do not mark console setup complete until the Playwright run and read-back verification succeed.

### 3.4 Release

- Bump `versionCode`/`versionName`; tag `vX.Y.Z`.
- The GitHub-hosted `ubuntu-latest` release workflow builds the signed AAB and uploads it; do not use a local/Termux build as release proof.
- Upload to internal, run license-tester purchase/restore checks, promote to closed/production.
- Keep Fastlane metadata/screenshots current and consistent with actual features.

**Acceptance:** Playwright executed the Cloud/Play console setup and read-back verification; Cloud project has required APIs/service account/OAuth; Play app is compliant; subscriptions exist; signed AAB is on a track from the GitHub runner; Play Developer API access is verified; purchase and restore work from a license tester.

## Phase 4 — QA, launch, operations — `TODO`

- End-to-end tests: register → free session → ad/CPA → pay → premium → route → expiry.
- Load test region/nearby/route endpoints with mixed free/premium traffic; verify paid priority and free caps.
- Security review: authz on every user/report/payment endpoint, webhook signatures, rate limits, SQL injection, secret handling.
- Privacy review: data minimization, retention, deletion, data-safety accuracy.
- Operations: uptime/health monitoring, error logs, DB backups, restore drill, incident runbook, dependency updates.
- Launch checklist: store listing, screenshots, privacy policy URL, support contact, pricing, refund policy, release notes.

## Feature acceptance checklist

- [ ] Map loads with current location and camera markers.
- [ ] Destination search + Google Maps directions work.
- [ ] Current-route camera list is accurate and distance-sorted.
- [ ] Radius slider changes results and respects free-tier cap.
- [ ] Camera detail shows source, description, direction/view direction.
- [ ] Free mode shows ad/CPA gate and ad-reason notice.
- [ ] Premium removes ads, unlocks unlimited radius/route features, and survives restart.
- [ ] Google OAuth and username/password login work.
- [ ] Email/SMS recovery and 2FA work.
- [ ] PayPal and Google Play purchases activate, restore, and expire correctly.
- [ ] Account deletion removes/minimizes stored data.
- [ ] Server deploys on Ubuntu via Docker/systemd with TLS and backups.
- [ ] Deflock retrieval/caching and user submissions are validated and moderated.
- [ ] Paid users are prioritized under load; free limits are enforced.
- [ ] Play release is live with verified API access, compliance, and subscriptions.

## Known defects / remaining work

| Area | Problem |
| --- | --- |
| Android build | No local JDK/Android SDK; compile correctness must be proven by GitHub Actions. |
| Android security | Session token is stored in plain SharedPreferences. |
| Android map | Camera direction is shown in the detail sheet but not yet as a map cone. |
| Release | Workflows need the real keystore/Play secrets and a Play app with API access. |
| Console | Playwright login/configuration scripts do not exist yet. |
| Payments | No real Play subscription products, PayPal credentials, or Maps/AdMob keys configured. |
| Verification | No end-to-end device test, load test, or store-compliance read-back yet. |
