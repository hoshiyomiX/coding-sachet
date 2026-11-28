'use strict';
// T-ML52 — provider ketiga "seek" (100milionseek.top). Isolasi fitur dari
// server.test.js (lesson-T40: fitur baru dites di file sendiri):
//   1. /api/models menyaring model non-chat (JEV judge, endpoint khusus)
//   2. /api/quota membaca header x-quota-* (tidak ada endpoint kuota)
//   3. sweep probe menandai free:true (freeByProbe — bersama NIM)

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway, getJson } = require('./mock-gateway');

// Isolated env for the server under test (same header as server.test.js —
// node --test runs each file in its own process, so the env is file-local).
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-seek-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';
// Fast probe budgets so a "dead" model costs milliseconds, not 15 s
// (same convention as verify.test.js).
process.env.SACHET_VERIFY_FIRST_CHUNK_MS = '150';
process.env.SACHET_CONNECT_TIMEOUT_MS = '700';

/** Point the seek (+ optionally nvidia) provider at the given mock gateway. */
function useSeekGateway({ seek, nvidia }) {
  const { config } = require('../config');
  if (seek) config.providers.seek.baseUrl = `http://127.0.0.1:${seek.port}/v1`;
  if (nvidia) config.providers.nvidia.baseUrl = `http://127.0.0.1:${nvidia.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();
  return config;
}

/** Boot the local server on an ephemeral port; resolves the port. */
async function bootServer(t) {
  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  return srv.address().port;
}

test('seek: /api/models lists only chat-path models (JEV judge filtered)', async (t) => {
  const gw = await startMockGateway([], {
    key: 'hms-test-1',
    models: [
      'deepseek-v4.1-flash-free',                        // chat, no endpoint field
      { id: 'jev-1.13-free', endpoint: '/v1/systemone' }, // judge — NOT chat
      { id: 'x-via-chat', endpoint: '/v1/chat/completions' }, // explicit chat
    ],
  });
  t.after(() => gw.close());
  useSeekGateway({ seek: gw });
  const port = await bootServer(t);

  const r = await getJson(port, '/api/models', {
    'x-sachet-key': 'hms-test-1',
    'x-sachet-provider': 'seek',
  });
  assert.strictEqual(r.status, 200);
  assert.deepStrictEqual(r.body.models, ['deepseek-v4.1-flash-free', 'x-via-chat']);
});

test('seek: /api/quota reads the x-quota-* response headers (no endpoint, cache + fresh)', async (t) => {
  const gw = await startMockGateway([], {
    key: 'hms-test-2',
    models: ['deepseek-v4.1-flash-free'],
    quotaHeaders: {
      'x-quota-limit': '400000000',
      'x-quota-used': '1292',
      'x-quota-remaining': '399998708',
      'x-quota-reset': '2026-11-21T00:00:00.000Z',
    },
  });
  t.after(() => gw.close());
  useSeekGateway({ seek: gw });
  const port = await bootServer(t);
  const H = { 'x-sachet-key': 'hms-test-2', 'x-sachet-provider': 'seek' };

  const r = await getJson(port, '/api/quota', H);
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body.supported, true);
  assert.strictEqual(r.body.source, 'headers');
  assert.strictEqual(r.body.unlimited, false);
  assert.strictEqual(r.body.tokensRemaining, 399998708);
  assert.strictEqual(r.body.tokensUsed, 1292);
  assert.strictEqual(r.body.tokenLimit, 400000000);
  assert.strictEqual(r.body.resetsAt, '2026-11-21T00:00:00.000Z');
  // mode stays null → the T-ML49b "locked" chip hint (APInex free-plan)
  // can never fire for seek: a 0 here is a genuinely spent allowance.
  assert.strictEqual(r.body.mode, null);
  // the header probe IS GET /models — exactly one gateway hit per fetch
  assert.strictEqual(gw.modelRequests.length, 1);

  // cached within the TTL — no extra gateway hits
  const r2 = await getJson(port, '/api/quota', H);
  assert.strictEqual(r2.status, 200);
  assert.strictEqual(r2.body.cached, true);
  assert.strictEqual(r2.body.tokensRemaining, 399998708);
  assert.strictEqual(gw.modelRequests.length, 1);

  // ?fresh=1 skips the cache read
  const r3 = await getJson(port, '/api/quota?fresh=1', H);
  assert.strictEqual(r3.status, 200);
  assert.notStrictEqual(r3.body.cached, true);
  assert.strictEqual(gw.modelRequests.length, 2);

  // a rejected key surfaces the gateway error
  const bad = await getJson(port, '/api/quota', {
    'x-sachet-key': 'hms-salah',
    'x-sachet-provider': 'seek',
  });
  assert.strictEqual(bad.status, 502);
  assert.ok(bad.body.error.includes('Invalid token'));
});

test('seek: /api/quota fails loudly when the gateway stops sending quota headers', async (t) => {
  // Shape change: the gateway answers 200 but with no x-quota-* headers —
  // fabricating zeros would trip the T-ML49b "locked" chip hint.
  const gw = await startMockGateway([], { key: 'hms-test-3', models: ['deepseek-v4.1-flash-free'] });
  t.after(() => gw.close());
  useSeekGateway({ seek: gw });
  const port = await bootServer(t);

  const r = await getJson(port, '/api/quota', {
    'x-sachet-key': 'hms-test-3',
    'x-sachet-provider': 'seek',
  });
  assert.strictEqual(r.status, 502);
  assert.ok(r.body.error.includes('header kuota'));
});

test('seek: sweep probe marks answering models free (freeByProbe, same as NIM)', async (t) => {
  // One model answers, one hangs past the first-chunk budget → ok:false
  // (hangStream is a byModel kind — script-array entries always answer).
  const gw = await startMockGateway([], {
    key: 'hms-test-4',
    models: ['deepseek-v4.1-flash-free', 'jev-ish'],
    byModel: {
      'deepseek-v4.1-flash-free': { kind: 'sse' },
      'jev-ish': { kind: 'hangStream' },
    },
  });
  t.after(() => gw.close());
  useSeekGateway({ seek: gw });

  const { verifyModels } = require('../verify');
  const events = [];
  await verifyModels({
    apiKey: 'hms-test-4',
    providerId: 'seek',
    models: ['deepseek-v4.1-flash-free', 'jev-ish'],
    signal: new AbortController().signal,
    emit: (ev) => events.push(ev),
  });

  const results = events.filter((e) => e.type === 'result');
  assert.strictEqual(results.length, 2);
  const alive = results.find((e) => e.model === 'deepseek-v4.1-flash-free');
  assert.strictEqual(alive.ok, true);
  assert.strictEqual(alive.free, true, 'model seek yang menjawab = gratis (seluruh layanan free)');
  const dead = results.find((e) => e.model === 'jev-ish');
  assert.strictEqual(dead.ok, false);
  assert.strictEqual(dead.free, null, 'ok:false tidak boleh diklaim free maupun tidak');
  const done = events.find((e) => e.type === 'done');
  assert.strictEqual(done.ok, 1);
  assert.strictEqual(done.dead, 1);
});

test('seek: NIM regression — probe still marks free:true after the freeByProbe generalization', async (t) => {
  const gw = await startMockGateway([{ kind: 'text', text: 'ok' }], {
    key: 'nvapi-test',
    models: ['nvidia/nemotron-3.5-bolt-e4b'],
  });
  t.after(() => gw.close());
  useSeekGateway({ nvidia: gw });

  const { verifyModels } = require('../verify');
  const events = [];
  await verifyModels({
    apiKey: 'nvapi-test',
    providerId: 'nvidia',
    models: ['nvidia/nemotron-3.5-bolt-e4b'],
    signal: new AbortController().signal,
    emit: (ev) => events.push(ev),
  });
  const alive = events.find((e) => e.type === 'result');
  assert.strictEqual(alive.ok, true);
  assert.strictEqual(alive.free, true, 'NIM bebas dipakai begitu menjawab — perilaku lama dipertahankan');
});
