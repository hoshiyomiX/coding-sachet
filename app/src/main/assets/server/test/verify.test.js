'use strict';
// T-ML26 integration tests: the live model-verification route
// POST /api/verify-models (owner order: "mengecek actual bukan dari
// membaca hardcoded list") — the SSE sweep against the mock gateway:
//   * NIM probe path: alive via first SSE chunk / 404 dead / first-chunk
//     timeout dead / a single pre-headers hang marked dead while the
//     sweep continues / two connect failures in a row abort with an error
//   * APInex catalogue fast path: health → dot, allowFree → free, zero
//     chat requests; unknown catalogue ids come back ok:null
//   * catalogue unreachable → falls back to the probe path
//   * route guards: missing key 401, unknown provider 400, no list passed
//     → verify the gateway's own /v1/models catalogue

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway, collectSse, postJson } = require('./mock-gateway');

// Isolated env for the server under test (same pattern as server.test.js).
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-verify-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';
// Fast probe budgets so a "dead" model costs milliseconds, not 15 s.
process.env.SACHET_VERIFY_FIRST_CHUNK_MS = '150';
process.env.SACHET_CONNECT_TIMEOUT_MS = '700';

const { config } = require('../config');

const KEY = 'sk-test-123';

/** Point both providers at the mock; wire the APInex catalogue URL (or
 *  leave it on the mock's 404 path to force the probe fallback). */
function useGateway(mock, withCatalogue) {
  config.providers.apinex.baseUrl = `http://127.0.0.1:${mock.port}/v1`;
  config.providers.nvidia.baseUrl = `http://127.0.0.1:${mock.port}/v1`;
  config.providers.apinex.publicCatalogUrl = `http://127.0.0.1:${mock.port}/api/public/models`;
  config.allowedRoots = [ROOT];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();
  if (!withCatalogue) delete config.providers.apinex.publicCatalogUrl;
  return config;
}

/** Start the agent server on an ephemeral port; resolves its port. */
async function startServer(t) {
  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => setTimeout(r, 25)); // listen() is sync-side-effect
  return srv.address().port;
}

function verifyHeaders() {
  return { 'X-Sachet-Key': KEY, 'X-Sachet-Provider': 'nvidia' };
}

test('verify: NIM probe path — alive (first SSE chunk), 404 dead, first-chunk timeout dead, single pre-headers hang dead but sweep continues', async (t) => {
  const gw = await startMockGateway([], {
    key: KEY,
    byModel: {
      'nvidia/alive': { kind: 'sse' },
      'nvidia/forty': { kind: 'error', status: 404, message: 'Function … not found' },
      'nvidia/slow-chunk': { kind: 'hangStream' },
      'nvidia/no-headers': { kind: 'hang' },
    },
  });
  t.after(() => gw.close());
  useGateway(gw, false);
  const port = await startServer(t);

  const events = await collectSse(port, '/api/verify-models', verifyHeaders(), {
    models: ['nvidia/alive', 'nvidia/forty', 'nvidia/slow-chunk', 'nvidia/no-headers'],
  });

  assert.strictEqual(events[0].type, 'start', 'event pertama = start');
  assert.strictEqual(events[0].total, 4);
  const byModel = Object.fromEntries(
    events.filter((e) => e.type === 'result').map((r) => [r.model, r]),
  );
  assert.strictEqual(byModel['nvidia/alive'].ok, true, 'chunk data pertama = hidup');
  assert.strictEqual(byModel['nvidia/alive'].free, true, 'NIM menjawab = free');
  assert.strictEqual(byModel['nvidia/forty'].ok, false);
  assert.strictEqual(byModel['nvidia/forty'].reason, 'HTTP 404');
  assert.strictEqual(byModel['nvidia/slow-chunk'].ok, false, 'tanpa chunk dalam budget = mati');
  assert.strictEqual(byModel['nvidia/slow-chunk'].reason, 'timeout');
  assert.strictEqual(byModel['nvidia/no-headers'].ok, false, 'hang pra-header sekali = mati, sweep lanjut');
  assert.ok(String(byModel['nvidia/no-headers'].reason).includes('gateway'), 'reason menyebut gateway');
  const done = events.find((e) => e.type === 'done');
  assert.strictEqual(done.ok, 1);
  assert.strictEqual(done.dead, 3);
  assert.strictEqual(done.unknown, 0);

  // Probe shape: tiny streaming chat call (mirrors the app's real path).
  const probe = gw.requests.find((r) => r.model === 'nvidia/alive');
  assert.ok(probe, 'probe terkirim ke gateway');
  assert.strictEqual(probe.max_tokens, 16);
  assert.strictEqual(probe.stream, true);
  assert.strictEqual(probe.messages.length, 1);
});

test('verify: NIM probe path — JSON 200 (gateway ignored stream:true) juga hidup; 429 = tak pasti (null)', async (t) => {
  const gw = await startMockGateway([], {
    key: KEY,
    byModel: {
      'nvidia/json-only': { kind: 'json' },
      'nvidia/limited': { kind: 'error', status: 429, message: 'Rate limit exceeded' },
    },
  });
  t.after(() => gw.close());
  useGateway(gw, false);
  const port = await startServer(t);

  const events = await collectSse(port, '/api/verify-models', verifyHeaders(), {
    models: ['nvidia/json-only', 'nvidia/limited'],
  });
  const byModel = Object.fromEntries(
    events.filter((e) => e.type === 'result').map((r) => [r.model, r]),
  );
  assert.strictEqual(byModel['nvidia/json-only'].ok, true, '200 JSON = model melayani');
  assert.strictEqual(byModel['nvidia/limited'].ok, null, '429 = tidak bisa memastikan');
  assert.strictEqual(byModel['nvidia/limited'].free, null);
  const done = events.find((e) => e.type === 'done');
  assert.strictEqual(done.ok, 1);
  assert.strictEqual(done.dead, 0);
  assert.strictEqual(done.unknown, 1);
});

test('verify: dua kegagalan connect berturut-turut = jalur gateway mati, sweep berhenti dengan error', async (t) => {
  const gw = await startMockGateway([], {
    key: KEY,
    byModel: {
      'nvidia/a': { kind: 'hang' },
      'nvidia/b': { kind: 'hang' },
    },
  });
  t.after(() => gw.close());
  useGateway(gw, false);
  const port = await startServer(t);

  const events = await collectSse(port, '/api/verify-models', verifyHeaders(), {
    models: ['nvidia/a', 'nvidia/b'],
  });
  // Model pertama dicatat mati (kegagalan tunggal), lalu kegagalan kedua
  // berturut-turut membatalkan sweep dengan event error + done.
  const results = events.filter((e) => e.type === 'result');
  assert.strictEqual(results.length, 1, 'hanya model pertama yang sempat dicatat');
  assert.strictEqual(results[0].ok, false);
  const err = events.find((e) => e.type === 'error');
  assert.ok(err, 'event error wajib ada');
  assert.ok(String(err.message).includes('gateway'), 'pesan error menyebut gateway');
  assert.ok(events.some((e) => e.type === 'done'), 'done tetap dikirim');
});

test('verify: APInex jalur cepat katalog publik — health jadi dot, allowFree jadi free, TANPA chat', async (t) => {
  const gw = await startMockGateway([], {
    key: KEY,
    models: ['free/a', 'b', 'c'],
    catalogue: [
      { id: 'free/a', health: 'live', allowFree: true },
      { id: 'b', health: 'live', allowFree: false },
      { id: 'c', health: 'dead', allowFree: true },
    ],
  });
  t.after(() => gw.close());
  useGateway(gw, true);
  const port = await startServer(t);

  const events = await collectSse(
    port,
    '/api/verify-models',
    { 'X-Sachet-Key': KEY, 'X-Sachet-Provider': 'apinex' },
    { models: ['free/a', 'b', 'c', 'tak-ada-di-katalog'] },
  );
  const byModel = Object.fromEntries(
    events.filter((e) => e.type === 'result').map((r) => [r.model, r]),
  );
  assert.strictEqual(byModel['free/a'].ok, true);
  assert.strictEqual(byModel['free/a'].free, true, 'allowFree = badge free');
  assert.strictEqual(byModel['b'].ok, true);
  assert.strictEqual(byModel['b'].free, false, 'bukan allowFree');
  assert.strictEqual(byModel['c'].ok, false, 'health=dead = merah');
  assert.strictEqual(byModel['c'].reason, 'health=dead');
  assert.strictEqual(byModel['tak-ada-di-katalog'].ok, null, 'id tak dikenal = tak pasti');
  assert.strictEqual(byModel['tak-ada-di-katalog'].free, null);
  const done = events.find((e) => e.type === 'done');
  assert.strictEqual(done.ok, 2);
  assert.strictEqual(done.dead, 1);
  assert.strictEqual(done.unknown, 1);
  // The whole point of the fast path: not a single chat request.
  assert.strictEqual(gw.chatAttempts, 0, 'katalog = nol panggilan chat');
  assert.strictEqual(gw.catalogueRequests, 1);
});

test('verify: katalog publik tak terjangkau → mundur ke probe per model (free jadi null di APInex)', async (t) => {
  const gw = await startMockGateway([], {
    key: KEY,
    byModel: { 'x/probe-me': { kind: 'sse' } },
  });
  t.after(() => gw.close());
  // Catalogue URL dibiarkan menunjuk ke path mock yang 404.
  const cfg = useGateway(gw, true);
  assert.ok(cfg.providers.apinex.publicCatalogUrl);
  const port = await startServer(t);

  const events = await collectSse(
    port,
    '/api/verify-models',
    { 'X-Sachet-Key': KEY, 'X-Sachet-Provider': 'apinex' },
    { models: ['x/probe-me'] },
  );
  const result = events.find((e) => e.type === 'result');
  assert.strictEqual(result.ok, true, 'fallback probe menjawab');
  assert.strictEqual(result.free, null, 'probe tak bisa melihat allowFree');
  assert.ok(gw.chatAttempts >= 1, 'probe path benar-benar dipakai');
});

test('verify: key ditolak gateway (401) → sweep berhenti dengan error', async (t) => {
  const gw = await startMockGateway([], {
    key: KEY,
    byModel: { 'nvidia/ok': { kind: 'sse' }, 'nvidia/bad': { kind: 'error', status: 401, message: 'Invalid token' } },
  });
  t.after(() => gw.close());
  useGateway(gw, false);
  const port = await startServer(t);

  const events = await collectSse(port, '/api/verify-models', verifyHeaders(), {
    models: ['nvidia/bad', 'nvidia/ok'],
  });
  assert.strictEqual(events.filter((e) => e.type === 'result').length, 0, 'tidak ada hasil setelah 401');
  const err = events.find((e) => e.type === 'error');
  assert.ok(err && String(err.message).includes('HTTP 401'), 'error menyebut 401');
});

test('verify: guard rute — tanpa key 401, provider asing 400, tanpa daftar model pakai katalog /v1/models', async (t) => {
  const gw = await startMockGateway([], {
    key: KEY,
    models: ['m-satu', 'm-dua'],
    byModel: { 'm-satu': { kind: 'sse' }, 'm-dua': { kind: 'error', status: 404, message: 'nope' } },
  });
  t.after(() => gw.close());
  useGateway(gw, false);
  const port = await startServer(t);

  // Tanpa X-Sachet-Key → 401 JSON (bukan SSE).
  const noKey = await postJson(port, '/api/verify-models', { models: ['m-satu'] });
  assert.strictEqual(noKey.status, 401);

  // Provider tidak dikenal → 400.
  const bogus = await postJson(port, '/api/verify-models', { models: ['m-satu'] }, {
    'X-Sachet-Key': KEY,
    'X-Sachet-Provider': 'bogus',
  });
  assert.strictEqual(bogus.status, 400);

  // Tanpa body models → sweep memakai /v1/models milik gateway.
  const events = await collectSse(port, '/api/verify-models', verifyHeaders(), {});
  assert.strictEqual(events[0].total, 2, 'daftar dari /v1/models');
  const byModel = Object.fromEntries(
    events.filter((e) => e.type === 'result').map((r) => [r.model, r]),
  );
  assert.strictEqual(byModel['m-satu'].ok, true);
  assert.strictEqual(byModel['m-dua'].ok, false);
});
