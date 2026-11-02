# F-Flock-Cam — CLI / Agent Configuration (Service Account, Tokens, API Keys)

This document covers the command-line agent configuration for the F-Flock-Cam project: Google service accounts, tokens, API keys, GitHub secrets and agent (Claude Code / MCP) setup.

## 1. GitHub

- Token used for CLI operations: `gh` authenticated as `techted89` (see token scopes: `repo`, `workflow`, `admin:repo_hook`, etc.).
- Required repository secrets for release automation (`.github/workflows/android-release.yml`):
  - `KEYSTORE_BASE64`
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
  - `PLAY_STORE_SERVICE_ACCOUNT_JSON`

## 2. Google Cloud / Play Console service account

```bash
# Activate service account
gcloud auth activate-service-account --key-file=/path/to/service-account.json

# Set project context if needed
gcloud config set project <project-id>
```

The service account JSON key needs the **Android Publisher** (Google Play Developer API) role and must be added to the target Play Console app under **Setup → API access**.

## 3. fastlane

- Install: `gem install fastlane`
- supply lane: `fastlane android android_play_store` (env `AAB_PATH`, `PLAY_TRACK`).
- supply service account: env `PLAY_STORE_SERVICE_ACCOUNT_JSON` or file-based `json_key`.

## 4. Agent (Claude Code / MCP) configuration

Example MCP server configuration for the Play Console agent (`app-console-mcp`):

```json
{
  "mcpServers": {
    "app-console": {
      "command": "npx",
      "args": ["-y", "@alexkulpa/app-console-mcp"],
      "env": {}
    }
  }
}
```

See the upstream repo: <https://github.com/Alekla0126/app-console-mcp>.

Agent skills for Play Console metadata: <https://github.com/JEuler/google-play-console-skills>.

## 5. Tokens & API keys summary

| Purpose | Location / Secret |
| --- | --- |
| GitHub Actions / `gh` | `ghp_...` token (repo + workflow scopes) |
| Google Play service account | `PLAY_STORE_SERVICE_ACCOUNT_JSON` / `fastlane/google-play-service-account.json` |
| Release keystore | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` |
| Deflock API | `DEFLOCK_API_KEY` (server `server/.env`) |
| Play Console API | Service account key (see above) |
| Playwright | `scripts/playwright` npm deps + local browser install |

## 6. Useful agent / CLI commands

```bash
# GitHub
gh repo view techted89/F-Flock-Cam
gh release list

# Playwright stealth check
cd scripts/playwright && npm install && npx playwright install chromium && npm run stealth

# Fastlane
fastlane android android_play_store

# Google service account
gcloud auth activate-service-account --key-file=service-account.json
```

See also [PLAYWRIGHT_RESEARCH.md](PLAYWRIGHT_RESEARCH.md) and [PLAYSTORE_RELEASE.md](PLAYSTORE_RELEASE.md).
