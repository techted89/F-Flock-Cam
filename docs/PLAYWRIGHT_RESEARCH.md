# Playwright + Stealth Research Resources (F-Flock-Cam)

This document lists the GitHub research repositories and resources used for the **Playwright web browser with stealth addons** setup, and points to resources for Google Play release configuration and CLI / agent configuration (service account, tokens, API keys).

## Playwright stealth repositories researched

| Repository | Stars | Notes |
| --- | ---: | --- |
| [AtuboDad/playwright_stealth](https://github.com/AtuboDad/playwright_stealth) | ~990 | Most starred Playwright stealth project; init-script based evasions. |
| [Mattwmaster58/playwright_stealth](https://github.com/Mattwmaster58/playwright_stealth) | ~273 | Playwright stealth implementation. |
| [tinyfish-io/tf-playwright-stealth](https://github.com/tinyfish-io/tf-playwright-stealth) | ~227 | Fork of the AtuboDad stealth approach. |
| [Granitosaurus/playwright-stealth](https://github.com/Granitosaurus/playwright-stealth) | ~152 | Community Playwright stealth wrapper. |
| [jonfriesen/playwright-go-stealth](https://github.com/jonfriesen/playwright-go-stealth) | ~53 | Puppeteer-evasion shim for Playwright Go. |
| [kihdev/playwright-stealth-4j](https://github.com/kihdev/playwright-stealth-4j) | ~25 | Kotlin-based stealth for Playwright Java/Kotlin/Groovy. |
| [managedcode/playwright_stealth](https://github.com/managedcode/playwright_stealth) | ~10 | .NET stealth: 31 init-script patches (navigator, WebGL, canvas, audio, CDP). |
| [soenneker/soenneker.playwrights.extensions.stealth](https://github.com/soenneker/soenneker.playwrights.extensions.stealth) | ~9 | Playwright extensions incl. stealth. |
| [Eth3rnit3/playwright_stealth](https://github.com/Eth3rnit3/playwright_stealth) | ~4 | Playwright stealth for Ruby. |
| [xuanyuanli/playwright-stealth-pool](https://github.com/xuanyuanli/playwright-stealth-pool) | ~4 | Java stealth pool for Playwright. |

## Browser-use project

- [browser-use/browser-use](https://github.com/browser-use/browser-use) — open-source AI browser agent library with stealth browsers and hosted agent API.

## Playwright setup used in this repository

- [`scripts/playwright`](../../scripts/playwright) — npm package wrapping `playwright` with a stealth browser launch (`src/stealth.js`).
- Install: `npm install && npx playwright install chromium`.
- Launch: `node -e "const {launchStealthBrowser}=require('./src/stealth'); launchStealthBrowser().then(...)"`.

Stealth measures applied in `scripts/playwright/src/stealth.js`:

- `--disable-blink-features=AutomationControlled`, `--disable-features=AutomationControlled,...`
- `navigator.webdriver` removal via init script.
- `navigator.languages`, `navigator.plugins`, `navigator.permissions` overrides.
- `chrome.runtime` spoof.
- WebGL vendor/device parameter masking.

## Google Play release + CLI / agent configuration resources

- [cjaryou/statra](https://github.com/cjaryou/statra) — fastlane / Play Console stats CLI.
- [JEuler/google-play-console-skills](https://github.com/JEuler/google-play-console-skills) — agent (Claude Code) skills for Play Console metadata.
- [Alekla0126/app-console-mcp](https://github.com/Alekla0126/app-console-mcp) — MCP + CLI + agent plugin for Google Play / App Store / AdMob.
- [browser-use/browser-use](https://github.com/browser-use/browser-use) — browser automation used for scripted release checks.

See [PLAYSTORE_RELEASE.md](PLAYSTORE_RELEASE.md) and [AGENT_CONFIG.md](AGENT_CONFIG.md) for the concrete configuration steps (service account, tokens, API keys, GitHub secrets, fastlane env).

## Microsoft Playwright upstream

- [microsoft/playwright](https://github.com/microsoft/playwright) — browser automation framework; launch args and context options used for stealth.
