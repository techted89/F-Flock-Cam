const test = require('node:test');
const assert = require('node:assert/strict');
const {
  hashPassword,
  verifyPassword,
  randomToken,
  randomCode,
  sha256,
  normalizeEmail,
  isEmail,
} = require('../src/security');

test('password hashing verifies the original and rejects others', async () => {
  const stored = await hashPassword('correct horse battery staple');
  assert.match(stored, /^scrypt\$[a-f0-9]+\$[a-f0-9]+$/);
  assert.equal(await verifyPassword('correct horse battery staple', stored), true);
  assert.equal(await verifyPassword('wrong password', stored), false);
  assert.equal(await verifyPassword('correct horse battery staple', 'not-a-hash'), false);
});

test('tokens are random and hashable', () => {
  const first = randomToken(16);
  const second = randomToken(16);
  assert.notEqual(first, second);
  assert.equal(sha256(first).length, 64);
});

test('verification codes are six digits', () => {
  for (let i = 0; i < 20; i += 1) {
    assert.match(randomCode(), /^\d{6}$/);
  }
});

test('email normalization and validation', () => {
  assert.equal(normalizeEmail('  User@Example.COM '), 'user@example.com');
  assert.equal(isEmail('user@example.com'), true);
  assert.equal(isEmail('not-an-email'), false);
  assert.equal(isEmail(''), false);
});
