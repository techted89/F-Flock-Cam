const crypto = require('crypto');
const { promisify } = require('util');

const scrypt = promisify(crypto.scrypt);
const KEY_LENGTH = 64;
// scrypt is intentionally CPU/memory-hard but runs off the event loop so login
// bursts cannot stall camera reads. 16 MB per hash is safe on the target VPS.
const SCRYPT_OPTIONS = { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };

async function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const derived = await scrypt(String(password), salt, KEY_LENGTH, SCRYPT_OPTIONS);
  return `scrypt$${salt}$${derived.toString('hex')}`;
}

async function verifyPassword(password, stored) {
  const parts = String(stored || '').split('$');
  if (parts.length !== 3 || parts[0] !== 'scrypt') return false;
  const [, salt, hash] = parts;
  const expected = Buffer.from(hash, 'hex');
  if (expected.length !== KEY_LENGTH) return false;
  const actual = await scrypt(String(password), salt, KEY_LENGTH, SCRYPT_OPTIONS);
  return crypto.timingSafeEqual(expected, actual);
}

function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString('base64url');
}

function randomCode() {
  return String(crypto.randomInt(100000, 1000000));
}

function sha256(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

function normalizeEmail(email) {
  return String(email || '').trim().toLowerCase();
}

function isEmail(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value || ''));
}

module.exports = {
  hashPassword,
  verifyPassword,
  randomToken,
  randomCode,
  sha256,
  normalizeEmail,
  isEmail,
};
