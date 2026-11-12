'use strict';
// T-ML37 — the gateway connect LADDER (P0-1, owner order "10x10s retry")
// and its interplay with the 15 RPM limiter (P0-2).
//
// Field evidence (2026-11-12, upload/logcat-2026-11-12-094328.txt): a
// minimized window killed the VPN path for 15-60 s while the quick
// in-fetch retries only tolerate ~13 s — turn 89 of a 2.47 M-token run
// died three attempts in, taking the whole run with it. These tests pin
// the wrapper-level ladder that keeps such a run alive.
//
// This file runs at the DEFAULT 15 RPM window (each test acquires a
// handful of slots, far under the limit); the limiter-pacing behaviour
// itself is pinned separately in retry-pacing.test.js with a tiny window.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');

const { startMockGateway } = require('./mock-gateway');

// Isolated env for the server under test (same pattern as server.test.js).
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-res-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';

/** Point the apinex provider at a mock gateway; reset limiter state. */
function useGateway(gw) {
  const { config } = require('../config');
  config.providers.apinex.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();
  return config;
}

/** A TCP port that refuses every connection (a dead VPN path). */
function deadPort() {
  const srv = http.createServer(() => {});
  return new Promise((resolve) => {
    srv.listen(0, '127.0.0.1', () => resolve({ port: srv.address().port, close: () => new Promise((r) => srv.close(r)) }));
  });
}

test('T-ML37 ladder: connect-phase exhaustion is retried by the wrapper and heals', async (t) => {
  // 3 black-holed connects burn the quick in-fetch retries; the ladder's
  // next WHOLE call lands on the healthy 4th attempt.
  const gw = await startMockGateway([{ kind: 'text', text: 'pulih lewat tangga' }], { hangChat: 3 });
  t.after(() => gw.close());
  const config = useGateway(gw);
  const origCt = config.connectTimeoutMs;
  const origLadder = config.gatewayConnectRetries;
  const origDelay = config.gatewayRetryDelayMs;
  config.connectTimeoutMs = 200;
  config.gatewayConnectRetries = 1;
  config.gatewayRetryDelayMs = 100;
  t.after(() => {
    config.connectTimeoutMs = origCt;
    config.gatewayConnectRetries = origLadder;
    config.gatewayRetryDelayMs = origDelay;
  });

  const { runAgent } = require('../agent');
  const events = [];
  const added = await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'x',
    messages: [{ role: 'user', content: 'hai' }],
    emit: (e) => events.push(e),
  });

  assert.strictEqual(added[added.length - 1].content, 'pulih lewat tangga');
  assert.strictEqual(gw.chatAttempts, 4, '3 quick attempts + 1 ladder round');
  const retries = events.filter((e) => e.type === 'netretry');
  assert.strictEqual(retries.length, 3, '2 quick hints + 1 ladder hint');
  assert.strictEqual(retries[0].attempt, 1);
  assert.strictEqual(retries[0].max, 3, 'quick phase reports its own budget');
  assert.strictEqual(retries[2].attempt, 1);
  assert.strictEqual(retries[2].max, 1, 'ladder hint reports the ladder budget');
  assert.strictEqual(retries[2].provider, 'APInex');
});

test('T-ML37 ladder: exhaustion after N rounds fails with the total patience said out loud', async (t) => {
  // The path never comes back: the ladder must stop after
  // gatewayConnectRetries rounds and the final error must say how many
  // retries were spent (not just "3 percobaan").
  const gw = await startMockGateway([{ kind: 'text', text: 'tak pernah sampai' }], { hangChat: 99 });
  t.after(() => gw.close());
  const config = useGateway(gw);
  const origCt = config.connectTimeoutMs;
  const origLadder = config.gatewayConnectRetries;
  const origDelay = config.gatewayRetryDelayMs;
  config.connectTimeoutMs = 200;
  config.gatewayConnectRetries = 2;
  config.gatewayRetryDelayMs = 100;
  t.after(() => {
    config.connectTimeoutMs = origCt;
    config.gatewayConnectRetries = origLadder;
    config.gatewayRetryDelayMs = origDelay;
  });

  const { runAgent } = require('../agent');
  const events = [];
  await assert.rejects(
    runAgent({
      apiKey: 'sk-test-123', providerId: 'apinex', model: 'x',
      messages: [{ role: 'user', content: 'hai' }],
      emit: (e) => events.push(e),
    }),
    (e) => e.message.includes('gateway timeout ke APInex') &&
      e.message.includes('sudah dicoba ulang 2×') &&
      e.message.includes('percobaan'),
    'final error names the gateway and the spent ladder'
  );
  // 3 whole calls × 3 quick attempts each — the ladder is bounded.
  assert.strictEqual(gw.chatAttempts, 9);
  const retries = events.filter((e) => e.type === 'netretry');
  assert.strictEqual(retries.length, 8, '2 quick hints per call × 3 calls + 2 ladder hints');
});

test('T-ML37 ladder: a mid-run outage does not kill the run — it resumes on the next round', async (t) => {
  // The field shape: round 1 answers with tools, then the path DIES (VPN
  // minimize), the ladder keeps retrying, the path comes back and the run
  // delivers its final answer. A port swap simulates the death/recovery:
  // round 2 starts against a refusing port, and mid-ladder the provider is
  // pointed back at a healthy gateway.
  const gwAlive = await startMockGateway([{ kind: 'text', text: 'akhirnya sampai' }]);
  t.after(() => gwAlive.close());
  const dead = await deadPort();
  t.after(() => dead.close());

  const config = useGateway(gwAlive);
  const script = [
    { kind: 'tools', calls: [{ id: 'c1', name: 'bash', args: { command: 'echo hidup' } }] },
  ];
  // Round 1 must be served by a gateway whose FIRST reply is the tool call:
  // point at a dedicated first gateway, then swap.
  const gwRound1 = await startMockGateway(script);
  t.after(() => gwRound1.close());
  config.providers.apinex.baseUrl = `http://127.0.0.1:${gwRound1.port}/v1`;

  const origCt = config.connectTimeoutMs;
  const origLadder = config.gatewayConnectRetries;
  const origDelay = config.gatewayRetryDelayMs;
  config.connectTimeoutMs = 200;
  config.gatewayConnectRetries = 5;
  config.gatewayRetryDelayMs = 300;
  t.after(() => {
    config.connectTimeoutMs = origCt;
    config.gatewayConnectRetries = origLadder;
    config.gatewayRetryDelayMs = origDelay;
  });

  // Kill the path DETERMINISTICALLY the moment round 1's tool result is in
  // (the emit fires synchronously before round 2's gateway call — a timer
  // races a sub-10 ms round on a fast machine), and bring it back while
  // the ladder is still trying: the quick retries inside one gateway call
  // burn ~2 s (500 + 1500 ms delays), the first ladder round fires after
  // that, so swapping in at 2.4 s lands between its first (dead) fetch and
  // the second (healed) one.
  const aliveUrl = `http://127.0.0.1:${gwAlive.port}/v1`;
  const deadUrl = `http://127.0.0.1:${dead.port}/v1`;
  let killedPath = false;
  const swapIn = setTimeout(() => {
    config.providers.apinex.baseUrl = aliveUrl;
  }, 2400);
  t.after(() => clearTimeout(swapIn));

  const { runAgent } = require('../agent');
  const events = [];
  const added = await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'x',
    messages: [{ role: 'user', content: 'kerja lalu jawab' }],
    emit: (e) => {
      if (e.type === 'toolresult' && !killedPath) {
        killedPath = true;
        config.providers.apinex.baseUrl = deadUrl;
      }
      events.push(e);
    },
  });

  // The run SURVIVED: tool round persisted, final answer delivered.
  assert.strictEqual(added.filter((m) => m.role === 'assistant').length, 2);
  assert.strictEqual(added[added.length - 1].content, 'akhirnya sampai');
  assert.ok(events.some((e) => e.type === 'toolend' && e.ok), 'tool round completed before the outage');
  const retries = events.filter((e) => e.type === 'netretry');
  assert.ok(retries.some((r) => r.max === 5), 'the ladder hint surfaced during the outage');
  // The recovery gateway served exactly the round-2 call.
  assert.strictEqual(gwAlive.chatAttempts, 1);
});
