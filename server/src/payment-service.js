const express = require('express');
const crypto = require('crypto');
const fs = require('fs');
const config = require('./config');
const { db } = require('./db');
const {
  requireAuth,
  grantSubscription,
  getEntitlement,
  nowSeconds,
} = require('./user-service');

const router = express.Router();

let cachedGoogleToken = null;

function base64url(value) {
  return Buffer.from(value).toString('base64url');
}

function loadServiceAccount() {
  if (config.googleServiceAccountJson) {
    return JSON.parse(config.googleServiceAccountJson);
  }
  if (config.googleServiceAccountFile) {
    return JSON.parse(fs.readFileSync(config.googleServiceAccountFile, 'utf8'));
  }
  return null;
}

async function googleAccessToken() {
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > nowSeconds() + 60) {
    return cachedGoogleToken.token;
  }
  const serviceAccount = loadServiceAccount();
  if (!serviceAccount) {
    throw new Error('Google Play service account is not configured.');
  }
  const issuedAt = nowSeconds();
  const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = base64url(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: 'https://oauth2.googleapis.com/token',
    iat: issuedAt,
    exp: issuedAt + 3600,
  }));
  const signingInput = `${header}.${payload}`;
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(signingInput);
  signer.end();
  const signature = signer.sign(serviceAccount.private_key).toString('base64url');
  const assertion = `${signingInput}.${signature}`;

  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`Google token exchange failed (${response.status}): ${await response.text()}`);
  }
  const data = await response.json();
  cachedGoogleToken = {
    token: data.access_token,
    expiresAt: nowSeconds() + Number(data.expires_in || 3600),
  };
  return cachedGoogleToken.token;
}

async function verifyGooglePurchase(productId, purchaseToken) {
  const plan = config.plans[productId];
  if (!plan) throw new Error('Unknown product.');
  if (!purchaseToken) throw new Error('purchaseToken is required.');

  if (!loadServiceAccount()) {
    if (config.allowUnverifiedPurchases) {
      const start = nowSeconds();
      return {
        startsAt: start,
        expiresAt: start + plan.durationSeconds,
        raw: { unverified: true, productId },
      };
    }
    throw new Error('Google Play verification is not configured.');
  }

  const accessToken = await googleAccessToken();
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(config.googlePlayPackageName)}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;
  const response = await fetch(base, {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) {
    throw new Error(`Google Play verification failed (${response.status}): ${await response.text()}`);
  }
  const data = await response.json();
  const expiresAt = Math.floor(Number(data.expiryTimeMillis) / 1000);
  if (!Number.isFinite(expiresAt) || expiresAt <= nowSeconds()) {
    throw new Error('Google Play subscription is expired.');
  }
  if (Number(data.paymentState) === 0) {
    throw new Error('Google Play payment is still pending.');
  }
  if (Number(data.acknowledgementState) === 0) {
    await fetch(`${base}:acknowledge`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${accessToken}`,
        'content-type': 'application/json',
      },
      body: '{}',
    });
  }
  return {
    startsAt: nowSeconds(),
    expiresAt,
    raw: data,
  };
}

async function paypalAccessToken() {
  if (!config.paypalClientId || !config.paypalClientSecret) {
    throw new Error('PayPal is not configured.');
  }
  const basic = Buffer.from(`${config.paypalClientId}:${config.paypalClientSecret}`).toString('base64');
  const response = await fetch(`${config.paypalBaseUrl}/v1/oauth2/token`, {
    method: 'POST',
    headers: {
      authorization: `Basic ${basic}`,
      'content-type': 'application/x-www-form-urlencoded',
    },
    body: 'grant_type=client_credentials',
  });
  if (!response.ok) {
    throw new Error(`PayPal token failed (${response.status}): ${await response.text()}`);
  }
  const data = await response.json();
  return data.access_token;
}

async function createPayPalOrder(user, plan) {
  const accessToken = await paypalAccessToken();
  const response = await fetch(`${config.paypalBaseUrl}/v2/checkout/orders`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      intent: 'CAPTURE',
      purchase_units: [
        {
          custom_id: String(user.id),
          description: plan.label,
          amount: {
            currency_code: plan.currency,
            value: (plan.priceCents / 100).toFixed(2),
          },
        },
      ],
      application_context: {
        brand_name: 'F-Flock-Cam',
        user_action: 'PAY_NOW',
        return_url: `${config.publicBaseUrl}/api/payments/paypal/return`,
        cancel_url: `${config.publicBaseUrl}/api/payments/paypal/cancel`,
      },
    }),
  });
  if (!response.ok) {
    throw new Error(`PayPal create order failed (${response.status}): ${await response.text()}`);
  }
  const data = await response.json();
  const approvalUrl = data.links?.find((link) => link.rel === 'approve')?.href;
  if (!data.id || !approvalUrl) {
    throw new Error('PayPal did not return an approval URL.');
  }
  db.prepare(
    `INSERT INTO payment_orders (provider, order_id, user_id, product_id, amount_cents, currency)
     VALUES ('PAYPAL', ?, ?, ?, ?, ?)`,
  ).run(data.id, user.id, plan.id, plan.priceCents, plan.currency);
  return { orderId: data.id, approvalUrl };
}

async function capturePayPalOrder(orderId) {
  const order = db
    .prepare("SELECT * FROM payment_orders WHERE order_id = ? AND provider = 'PAYPAL'")
    .get(orderId);
  if (!order) throw new Error('Unknown PayPal order.');
  if (order.status === 'COMPLETED') {
    return { entitlement: getEntitlement(order.user_id), alreadyCaptured: true };
  }
  const plan = config.plans[order.product_id];
  if (!plan) throw new Error('Unknown plan on PayPal order.');

  const accessToken = await paypalAccessToken();
  const response = await fetch(`${config.paypalBaseUrl}/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json',
    },
    body: '{}',
  });
  if (!response.ok) {
    throw new Error(`PayPal capture failed (${response.status}): ${await response.text()}`);
  }
  const data = await response.json();
  if (data.status !== 'COMPLETED') {
    throw new Error(`PayPal order is not completed (status: ${data.status}).`);
  }
  const capture = data.purchase_units?.[0]?.payments?.captures?.[0];
  const customId = data.purchase_units?.[0]?.custom_id;
  if (String(customId) !== String(order.user_id)) {
    throw new Error('PayPal order user mismatch.');
  }
  const capturedCents = Math.round(Number(capture?.amount?.value || 0) * 100);
  if (capturedCents < order.amount_cents) {
    throw new Error('PayPal captured amount is less than the plan price.');
  }

  const entitlement = grantSubscription({
    userId: order.user_id,
    provider: 'PAYPAL',
    productId: order.product_id,
    startsAt: nowSeconds(),
    expiresAt: nowSeconds() + plan.durationSeconds,
    orderId,
    raw: data,
  });
  db.prepare("UPDATE payment_orders SET status = 'COMPLETED', updated_at = ? WHERE order_id = ?")
    .run(nowSeconds(), orderId);
  return { entitlement, alreadyCaptured: false };
}

async function verifyPayPalWebhook(req) {
  if (!config.paypalWebhookId) return true;
  const accessToken = await paypalAccessToken();
  const response = await fetch(`${config.paypalBaseUrl}/v1/notifications/verify-webhook-signature`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      transmission_id: req.headers['paypal-transmission-id'],
      transmission_time: req.headers['paypal-transmission-time'],
      cert_url: req.headers['paypal-cert-url'],
      auth_algo: req.headers['paypal-auth-algo'],
      transmission_sig: req.headers['paypal-transmission-sig'],
      webhook_id: config.paypalWebhookId,
      webhook_event: req.body,
    }),
  });
  if (!response.ok) return false;
  const data = await response.json();
  return data.verification_status === 'SUCCESS';
}

router.get('/plans', (req, res) => {
  res.set('Cache-Control', 'public, max-age=3600');
  return res.json({ status: 'ok', plans: Object.values(config.plans) });
});

router.post('/google/verify', requireAuth, async (req, res, next) => {
  try {
    if (!req.user.email_verified) {
      return res.status(403).json({ status: 'error', message: 'Verify your email before purchasing.' });
    }
    const productId = String(req.body?.productId || '');
    const purchaseToken = String(req.body?.purchaseToken || '');
    const verified = await verifyGooglePurchase(productId, purchaseToken);
    const entitlement = grantSubscription({
      userId: req.user.id,
      provider: 'GOOGLE_PLAY',
      productId,
      startsAt: verified.startsAt,
      expiresAt: verified.expiresAt,
      purchaseToken,
      raw: verified.raw,
    });
    return res.json({ status: 'ok', entitlement });
  } catch (error) {
    return next(error);
  }
});

router.post('/paypal/create-order', requireAuth, async (req, res, next) => {
  try {
    if (!req.user.email_verified) {
      return res.status(403).json({ status: 'error', message: 'Verify your email before purchasing.' });
    }
    const plan = config.plans[String(req.body?.productId || '')];
    if (!plan) {
      return res.status(400).json({ status: 'error', message: 'Unknown plan.' });
    }
    const order = await createPayPalOrder(req.user, plan);
    return res.json({ status: 'ok', ...order });
  } catch (error) {
    return next(error);
  }
});

router.post('/paypal/capture', requireAuth, async (req, res, next) => {
  try {
    const orderId = String(req.body?.orderId || '');
    const order = db.prepare('SELECT * FROM payment_orders WHERE order_id = ?').get(orderId);
    if (!order || order.user_id !== req.user.id) {
      return res.status(404).json({ status: 'error', message: 'Payment order not found.' });
    }
    const result = await capturePayPalOrder(orderId);
    return res.json({ status: 'ok', ...result });
  } catch (error) {
    return next(error);
  }
});

router.get('/paypal/return', async (req, res) => {
  const orderId = String(req.query.token || '');
  try {
    await capturePayPalOrder(orderId);
    return res.redirect(`${config.appReturnUrl}?status=success&orderId=${encodeURIComponent(orderId)}`);
  } catch (error) {
    return res.redirect(`${config.appReturnUrl}?status=failed&message=${encodeURIComponent(error.message)}`);
  }
});

router.get('/paypal/cancel', (req, res) => {
  return res.redirect(`${config.appReturnUrl}?status=cancelled`);
});

router.post('/paypal/webhook', async (req, res) => {
  try {
    const verified = await verifyPayPalWebhook(req);
    if (!verified) return res.status(401).json({ status: 'error', message: 'Invalid webhook signature.' });
    const event = req.body || {};
    if (event.event_type === 'CHECKOUT.ORDER.APPROVED' && event.resource?.id) {
      await capturePayPalOrder(event.resource.id).catch(() => {});
    }
    if (event.event_type === 'PAYMENT.CAPTURE.COMPLETED') {
      const orderId = event.resource?.supplemental_data?.related_ids?.order_id;
      if (orderId) await capturePayPalOrder(orderId).catch(() => {});
    }
    return res.json({ status: 'ok' });
  } catch (error) {
    // PayPal retries non-2xx responses; acknowledge only after handling.
    return res.status(500).json({ status: 'error', message: error.message });
  }
});

module.exports = {
  router,
  verifyGooglePurchase,
  createPayPalOrder,
  capturePayPalOrder,
};
