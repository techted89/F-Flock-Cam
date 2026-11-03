const path = require('path');

const DAY_SECONDS = 24 * 60 * 60;
const MONTH_SECONDS = 30 * DAY_SECONDS;

const PLANS = {
  premium_4_months: {
    id: 'premium_4_months',
    label: 'Premium — 4 months',
    priceCents: 300,
    currency: 'USD',
    durationSeconds: 4 * MONTH_SECONDS,
  },
  premium_1_year: {
    id: 'premium_1_year',
    label: 'Premium — 1 year',
    priceCents: 900,
    currency: 'USD',
    durationSeconds: 12 * MONTH_SECONDS,
  },
};

function numberFromEnv(name, fallback) {
  const value = Number(process.env[name]);
  return Number.isFinite(value) ? value : fallback;
}

module.exports = {
  port: numberFromEnv('PORT', 8080),
  host: process.env.HOST || '0.0.0.0',
  trustProxy: numberFromEnv('TRUST_PROXY', 1),
  publicBaseUrl: process.env.PUBLIC_BASE_URL || 'http://localhost:8080',
  dbPath: process.env.DB_PATH || path.join(__dirname, '..', 'data', 'flockcam.db'),

  sessionTtlSeconds: numberFromEnv('SESSION_TTL_SECONDS', 30 * DAY_SECONDS),
  codeTtlSeconds: numberFromEnv('CODE_TTL_SECONDS', 10 * 60),
  codeMaxAttempts: numberFromEnv('CODE_MAX_ATTEMPTS', 5),
  adRewardCooldownSeconds: numberFromEnv('AD_REWARD_COOLDOWN_SECONDS', 15 * 60),
  adRewardSeconds: numberFromEnv('AD_REWARD_SECONDS', 30 * 60),
  devReturnCodes: process.env.DEV_RETURN_CODES === 'true',

  freeMaxRadiusMeters: numberFromEnv('FREE_MAX_RADIUS_METERS', 2000),
  freeVisibleCameras: numberFromEnv('FREE_VISIBLE_CAMERAS', 25),
  freeRegionMaxCameras: numberFromEnv('FREE_REGION_MAX_CAMERAS', 250),
  premiumMaxRadiusMeters: numberFromEnv('PREMIUM_MAX_RADIUS_METERS', 50000),
  premiumRegionMaxCameras: numberFromEnv('PREMIUM_REGION_MAX_CAMERAS', 5000),

  deflockTilesBaseUrl: process.env.DEFLOCK_TILES_BASE_URL || 'https://cdn.deflock.me/regions',
  deflockTileSizeDegrees: numberFromEnv('DEFLOCK_TILE_SIZE_DEGREES', 20),
  deflockCacheTtlSeconds: numberFromEnv('DEFLOCK_CACHE_TTL_SECONDS', 300),
  deflockMaxTiles: numberFromEnv('DEFLOCK_MAX_TILES', 24),

  googleClientId: process.env.GOOGLE_CLIENT_ID || '',
  googlePlayPackageName: process.env.PLAY_PACKAGE_NAME || 'com.techted89.flockcam',
  googleServiceAccountJson: process.env.PLAY_STORE_SERVICE_ACCOUNT_JSON || '',
  googleServiceAccountFile: process.env.PLAY_STORE_SERVICE_ACCOUNT_FILE || '',
  allowUnverifiedPurchases: process.env.ALLOW_UNVERIFIED_PURCHASES === 'true',

  paypalBaseUrl: process.env.PAYPAL_BASE_URL || 'https://api-m.sandbox.paypal.com',
  paypalClientId: process.env.PAYPAL_CLIENT_ID || '',
  paypalClientSecret: process.env.PAYPAL_CLIENT_SECRET || '',
  paypalWebhookId: process.env.PAYPAL_WEBHOOK_ID || '',
  appReturnUrl: process.env.APP_RETURN_URL || 'flockcam://payment/complete',

  mailWebhookUrl: process.env.MAIL_WEBHOOK_URL || '',
  mailFrom: process.env.MAIL_FROM || 'no-reply@flockcam.app',
  smsWebhookUrl: process.env.SMS_WEBHOOK_URL || '',

  plans: PLANS,
};
