'use strict';
// T-ML37 P0-2 — every gateway arrival holds a 15 RPM slot, including
// connect retries.
//
// Field evidence (2026-11-12): the 60 s window before each self-inflicted
// 429 held 16-17 REAL arrivals while the client had only counted 13-14 —
// acquire() ran once per turn, so the quick connect retries inside
// fetchWithHeaderBudget were invisible to the limiter. The fix paces each
// retry through acquire() before its fetch fires.
//
// This file needs a tiny limiter window, so it runs in its own process
// (node --test gives each file one): RPM=1, window=600 ms. A flaky first
// connect then FORCES the retry's acquire to wait out the window —
// without the fix the retry fires ~500 ms earlier, un-paced.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

// MUST be set before any server module loads (RPM/WINDOW_MS are read once).
process.env.SACHET_TEST_RPM = '1';
process.env.SACHET_TEST_WINDOW_MS = '600';

const { startMockGateway } = require('./mock-gateway');

const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-pace-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';

test('T-ML37 P0-2: a connect retry is paced through the rate limiter (real arrival, real slot)', async (t) => {
  const gw = await startMockGateway([{ kind: 'text', text: 'terpacu' }], { flakyChat: 1 });
  t.after(() => gw.close());
  const { config } = require('../config');
  config.providers.apinex.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();

  const { runAgent } = require('../agent');
  const events = [];
  const t0 = Date.now();
  const added = await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'x',
    messages: [{ role: 'user', content: 'hai' }],
    emit: (e) => events.push(e),
  });
  const elapsed = Date.now() - t0;

  assert.strictEqual(added[added.length - 1].content, 'terpacu');
  assert.strictEqual(gw.chatAttempts, 2, 'first connect destroyed, retry delivered');

  // The pacing proof: with RPM=1 / window=600 ms the retry's acquire must
  // wait for the initial slot to age out (~600 ms) BEFORE its 500 ms
  // quick-retry delay — the second arrival cannot land under ~1.1 s.
  // Un-paced (the bug), the whole exchange finishes in ~600 ms.
  assert.ok(elapsed >= 1000, `retry was paced through the limiter (elapsed ${elapsed}ms ≥ 1000ms)`);

  // The user saw WHY it was waiting: a ratelimit hint precedes the paced
  // acquire, so the silence is never unexplained.
  const hints = events.filter((e) => e.type === 'ratelimit');
  assert.ok(hints.length >= 1, 'a ratelimit hint was emitted before the paced wait');
  assert.ok(hints.some((h) => (h.waitMs || 0) >= 400), `hint carries the real wait (${hints.map((h) => h.waitMs).join(',')}ms)`);
});
