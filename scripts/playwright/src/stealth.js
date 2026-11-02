const { chromium } = require('playwright');

const DEFAULT_USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const stealthInitScript = `
(() => {
  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
  Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
  Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5, 6, 7, 8] });
  Object.defineProperty(navigator, 'permissions', { get: () => ({}) });
  window.chrome = window.chrome || {};
  Object.defineProperty(window.chrome, 'runtime', { get: () => ({ id: undefined }) });
  const getParameter = WebGLRenderingContext.prototype.getParameter;
  WebGLRenderingContext.prototype.getParameter = function (parameter) {
    if (parameter === 0x8B8C) return 'Google Inc. (NVIDIA)';
    if (parameter === 0x8B8D) return 'Google Inc. (NVIDIA)';
    return getParameter ? getParameter.call(this, parameter) : null;
  };
  const canvasToDataURL = HTMLCanvasElement.prototype.toDataURL;
  HTMLCanvasElement.prototype.toDataURL = function () { return canvasToDataURL.call(this); };
})();

window.addEventListener('load', () => {
  try { delete window.__stealthflag; } catch (e) {}
});
`;

async function launchStealthBrowser(options = {}) {
  const browser = await chromium.launch({
    headless: true,
    args: [
      '--disable-blink-features=AutomationControlled',
      '--disable-features=AutomationControlled,Translate,IntensiveWakeUpThrottling',
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-blink-features=AutomationControlled',
      ...(options.args || []),
    ],
  });

  const context = await browser.newContext({
    userAgent: options.userAgent || DEFAULT_USER_AGENT,
    viewport: options.viewport || { width: 1366, height: 768 },
    locale: options.locale || 'en-US',
  });

  await context.addInitScript(stealthInitScript);

  return { browser, context };
}

module.exports = { launchStealthBrowser, DEFAULT_USER_AGENT };
