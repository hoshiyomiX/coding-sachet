'use strict';
// Unit tests for the client-side RPM limiter. Runs in its own process
// (node --test spawns one per file), so the fast-window env overrides
// below never leak into the integration suite.

process.env.SACHET_TEST_RPM = '3';
process.env.SACHET_TEST_WINDOW_MS = '300';

const test = require('node:test');
const assert = require('node:assert');
const { acquire, peekWaitMs, pauseFor, limitFor, abortableSleep, reset } = require('../ratelimit');

test('limitFor: the test override wins over provider config', () => {
  assert.strictEqual(limitFor('apinex'), 3);
  assert.strictEqual(limitFor('nvidia'), 3);
  assert.strictEqual(limitFor('unknown-provider'), 3);
});

test('acquire: slots inside the window are free, the next one waits', async () => {
  reset();
  const t0 = Date.now();
  await acquire('apinex'); // slot 1
  await acquire('apinex'); // slot 2
  await acquire('apinex'); // slot 3 — full now
  assert.ok(Date.now() - t0 < 100, 'first three acquires must be immediate');
  assert.ok(peekWaitMs('apinex') > 0, 'window is full — next acquire must wait');
  await acquire('apinex'); // waits for the oldest start to age out
  const waited = Date.now() - t0;
  assert.ok(waited >= 250, `fourth acquire waited ${waited}ms — expected ~window`);
});

test('acquire: providers have independent buckets', async () => {
  reset();
  await acquire('apinex');
  await acquire('apinex');
  await acquire('apinex');
  assert.strictEqual(peekWaitMs('apinex') > 0, true);
  assert.strictEqual(peekWaitMs('nvidia'), 0, 'nvidia bucket is still empty');
});

test('pauseFor: a 429 backpressure delays the next acquire', async () => {
  reset();
  pauseFor('apinex', 250);
  assert.ok(peekWaitMs('apinex') >= 200);
  const t0 = Date.now();
  await acquire('apinex');
  assert.ok(Date.now() - t0 >= 200, 'acquire honored the pause');
  // The pause is spent — the next acquire is free again.
  assert.strictEqual(peekWaitMs('apinex'), 0);
});

test('acquire: abort while waiting rejects immediately', async () => {
  reset();
  await acquire('apinex');
  await acquire('apinex');
  await acquire('apinex');
  const ac = new AbortController();
  const pending = acquire('apinex', ac.signal);
  ac.abort();
  await assert.rejects(pending, (e) => e.name === 'AbortError');
});

test('abortableSleep: resolves after the delay, rejects on abort', async () => {
  const t0 = Date.now();
  await abortableSleep(80, null);
  assert.ok(Date.now() - t0 >= 70);
  const ac = new AbortController();
  const p = abortableSleep(5000, ac.signal);
  ac.abort();
  await assert.rejects(p, (e) => e.name === 'AbortError');
});

test('reset: clears windows and pauses', () => {
  reset();
  pauseFor('apinex', 60000);
  reset();
  assert.strictEqual(peekWaitMs('apinex'), 0);
});
