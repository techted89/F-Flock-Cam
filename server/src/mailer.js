const config = require('./config');

async function postJson(url, payload) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Notification webhook failed (${response.status}): ${text}`);
  }
}

async function sendVerificationCode({ email, phone, code, purpose }) {
  const subject = purpose === 'reset_password'
    ? 'Reset your F-Flock-Cam password'
    : 'Verify your F-Flock-Cam email';
  const text = `Your F-Flock-Cam code is ${code}. It expires in 10 minutes.`;

  if (phone && config.smsWebhookUrl) {
    await postJson(config.smsWebhookUrl, { to: phone, text, purpose, code });
    return { delivered: true, channel: 'sms' };
  }

  if (email && config.mailWebhookUrl) {
    await postJson(config.mailWebhookUrl, {
      to: email,
      from: config.mailFrom,
      subject,
      text,
      purpose,
      code,
    });
    return { delivered: true, channel: 'email' };
  }

  // Development fallback. Never expose the code in production responses.
  // eslint-disable-next-line no-console
  console.log(`[mail:dev] ${subject} -> ${email || phone}: ${code}`);
  return { delivered: false, channel: 'log' };
}

module.exports = { sendVerificationCode };
