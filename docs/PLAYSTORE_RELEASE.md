# F-Flock-Cam — Google Play Release Setup

This document describes how the **F-Flock-Cam** Android app is built, signed and released to the Google Play Store.

## Overview

1. A Git tag matching `v*` triggers the [`Android Release`](.github/workflows/android-release.yml) GitHub Actions workflow on a GitHub-hosted `ubuntu-latest` runner (manual `workflow_dispatch` is also supported).
2. The workflow decodes the release keystore from the `KEYSTORE_BASE64` secret and runs `./gradlew bundleRelease` to produce an Android App Bundle (`.aab`).
3. The workflow installs Fastlane and runs `fastlane supply` to upload the AAB to the Google Play Console.
4. The release is placed on the configured Play track (default: `internal`).

Console setup is not a manual-only checklist: the agent must execute the Cloud/Play configuration through Playwright using `SERVICE_EMAIL` / `SERVICE_PASS` from `server/.env`, pausing for manual 2FA. The steps below are the target state the automation must complete and verify.

## Prerequisites

- Google Play Console developer account.
- An application created under the developer account (`com.techted89.flockcam`).
- An upload keystore (or use Play App Signing and upload the generated key).

## Release keystore

The release build expects the following environment variables (wired in `app/build.gradle.kts` via the `release` signing config and in CI via the `Android Release` workflow):

| Variable         | Description                          |
| ---------------- | ------------------------------------ |
| `KEYSTORE_FILE` | Path to the keystore (CI: `release.keystore`) |
| `KEYSTORE_PASSWORD` | Keystore password                |
| `KEY_ALIAS`      | Key alias                           |
| `KEY_PASSWORD`   | Key password                        |

GitHub repository secrets required by `.github/workflows/android-release.yml`:

- `KEYSTORE_BASE64` — base64 of the binary keystore file.
- `KEYSTORE_PASSWORD` — keystore password.
- `KEY_ALIAS` — key alias.
- `KEY_PASSWORD` — key password.
- `PLAY_STORE_SERVICE_ACCOUNT_JSON` — Google Play service account JSON key.

## Google Play service account (CLI / agent configuration)

For `fastlane supply` and for headless CLI / agent based release tooling (see [AGENT_CONFIG.md](AGENT_CONFIG.md)):

1. In Google Cloud Console, enable the **Google Play Developer API**.
2. Create a service account, grant it the **Android Publisher** role (or equivalent Play Console API role).
3. Create and download a JSON key for that service account.
4. In the Google Play Console, open **Setup → API access** and add the service account email as a tester / app user as needed.

`fastlane supply` consumes the service account through the `json_key` file (default `fastlane/google-play-service-account.json`) or the `PLAY_STORE_SERVICE_ACCOUNT_JSON` environment variable.

## Fastlane

- `fastlane/Fastfile` — contains the `android_play_store` lane using `supply`.
- `fastlane/Appfile` — app identifier `com.techted89.flockcam`.

Local usage:

```bash
gem install fastlane
fastlane run add_play_store_service_json  # or place the JSON manually
fastlane android android_play_store
```

CI usage is handled by the release workflow.

## Release checklist

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Commit, tag (`git tag vX.Y.Z && git push --tags`).
3. GitHub Actions builds the signed AAB and uploads it.
4. Roll out via Play Console (internal → closed testing → production) or via `fastlane supply --track internal`.

## Automation resources (GitHub research repos)

- [`cjaryou/statra`](https://github.com/cjaryou/statra) — open-source CLI for App Store Connect + Google Play Console stats.
- [`JEuler/google-play-console-skills`](https://github.com/JEuler/google-play-console-skills) — Claude Code skills for Google Play Console ASO / metadata sync (works with fastlane supply).
- [`Alekla0126/app-console-mcp`](https://github.com/Alekla0126/app-console-mcp) — MCP servers + CLI + agent plugins for Google Play Console / App Store Connect / AdMob.
- [`browser-use/browser-use`](https://github.com/browser-use/browser-use) — AI browser agents with stealth browsers.

See [PLAYWRIGHT_RESEARCH.md](PLAYWRIGHT_RESEARCH.md) and [AGENT_CONFIG.md](AGENT_CONFIG.md).
