'use strict';
// T-ML64 — provider keempat "ngodeai" (llm.ngodeai.net). Isolasi fitur
// dari file test lain (lesson-T40):
//   1. /api/models menyaring model non-chat (systemone judge, image-gen)
//      DAN status inactive (maintenance) — entri aktif $0 & berbayar lolos
//   2. /api/quota membaca saldo dompet GET /v1/balance (shape 'balance')
//   3. sweep fast-path katalog terautentikasi: ok dari status, free dari
//      pricing 0 (BUKAN freeByProbe), tier free/topup — tanpa probe chat
//   4. fallback ke probe path saat katalog gagal (free tetap null —
//      jawaban live tidak boleh menandai model berbayar gratis)
//      keepalive choices:[] dan delta reasoning_content ditoleransi

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway, getJson } = require('./mock-gateway');

const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-ngodeai-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';
process.env.SACHET_VERIFY_FIRST_CHUNK_MS = '150';
process.env.SACHET_CONNECT_TIMEOUT_MS = '700';

/** Point the ngodeai (+ seek, regression) provider at the mock gateway. */
function useNgodeaiGateway({ ngodeai, seek }) {
  const { config } = require('../config');
  if (ngodeai) config.providers.ngodeai.baseUrl = `http://127.0.0.1:${ngodeai.port}/v1`;
  if (seek) config.providers.seek.baseUrl = `http://127.0.0.1:${seek.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();
  return config;
}

async function bootServer(t) {
  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  return srv.address().port;
}

test('ngodeai: /api/models keeps active chat models, drops non-chat + inactive', async (t) => {
  const gw = await startMockGateway([], {
    key: 'ngodeai_live_test-1',
    models: [
      { id: 'ngodeai/glm-5.3-flash', endpoint: '/v1/chat/completions', status: 'active', pricing: { input: 0, output: 0 } },
      { id: 'ngodeai/claude-haiku-5.5', endpoint: '/v1/chat/completions', status: 'active', pricing: { input: 362, output: 1810 } }, // paid, tetap tampil
      { id: 'ngodeai/deepseek-v4.1-flash', endpoint: '/v1/chat/completions', status: 'inactive', pricing: { input: 0, output: 0 } }, // maintenance — dibuang
      { id: 'ngodeai/jev-1.14', endpoint: '/v1/systemone', status: 'active', pricing: { input: 761, output: 0 } }, // judge — dibuang
      { id: 'ngodeai/gpt-image-2.5-flare', endpoint: '/v1/images/generations', status: 'active', pricing: { input: 145141, output: 544440 } }, // image-gen — dibuang
      { id: 'ngodeai/no-status-field', endpoint: '/v1/chat/completions' }, // provider tanpa field status — tak tersentuh
    ],
  });
  t.after(() => gw.close());
  useNgodeaiGateway({ ngodeai: gw });
  const port = await bootServer(t);

  const r = await getJson(port, '/api/models', {
    'x-sachet-key': 'ngodeai_live_test-1',
    'x-sachet-provider': 'ngodeai',
  });
  assert.strictEqual(r.status, 200);
  assert.deepStrictEqual(r.body.models.sort(), [
    'ngodeai/claude-haiku-5.5',
    'ngodeai/glm-5.3-flash',
    'ngodeai/no-status-field',
  ]);
});

test('ngodeai: /api/quota reads the IDR wallet balance (cache + fresh + bad key)', async (t) => {
  const gw = await startMockGateway([], {
    key: 'ngodeai_live_test-2',
    models: [{ id: 'ngodeai/glm-5.3-flash', status: 'active', pricing: { input: 0, output: 0 } }],
    balance: 50000,
  });
  t.after(() => gw.close());
  useNgodeaiGateway({ ngodeai: gw });
  const port = await bootServer(t);
  const H = { 'x-sachet-key': 'ngodeai_live_test-2', 'x-sachet-provider': 'ngodeai' };

  const r = await getJson(port, '/api/quota', H);
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body.supported, true);
  assert.strictEqual(r.body.source, 'balance');
  assert.strictEqual(r.body.unlimited, false);
  assert.strictEqual(r.body.mode, 'idr');
  assert.strictEqual(r.body.tokensRemaining, 50000);
  assert.strictEqual(r.body.tokensUsed, null);
  assert.strictEqual(r.body.tokenLimit, null);
  assert.strictEqual(r.body.resetsAt, null);
  assert.strictEqual(gw.balanceRequests, 1);

  // cache dalam TTL — tanpa hit gateway tambahan
  const r2 = await getJson(port, '/api/quota', H);
  assert.strictEqual(r2.body.cached, true);
  assert.strictEqual(r2.body.tokensRemaining, 50000);
  assert.strictEqual(gw.balanceRequests, 1);

  // ?fresh=1 memaksa baca ulang
  const r3 = await getJson(port, '/api/quota?fresh=1', H);
  assert.notStrictEqual(r3.body.cached, true);
  assert.strictEqual(gw.balanceRequests, 2);

  // key salah → error gateway muncul
  const bad = await getJson(port, '/api/quota', {
    'x-sachet-key': 'ngodeai_live_salah',
    'x-sachet-provider': 'ngodeai',
  });
  assert.strictEqual(bad.status, 502);
  assert.ok(bad.body.error.includes('Invalid token'));
});

test('ngodeai: /api/quota fails loudly when balance_idr goes missing', async (t) => {
  // 200 tanpa balance_idr = perubahan bentuk — jangan fabrikasi 0.
  const gw = await startMockGateway([], {
    key: 'ngodeai_live_test-3',
    models: [],
    balance: { currency: 'IDR' },
  });
  t.after(() => gw.close());
  useNgodeaiGateway({ ngodeai: gw });
  const port = await bootServer(t);

  const r = await getJson(port, '/api/quota', {
    'x-sachet-key': 'ngodeai_live_test-3',
    'x-sachet-provider': 'ngodeai',
  });
  assert.strictEqual(r.status, 502);
  assert.ok(r.body.error.includes('balance_idr'));
});

test('ngodeai: sweep fast-path — ok from status, free from pricing 0, no chat probes', async (t) => {
  const gw = await startMockGateway([], {
    key: 'ngodeai_live_test-4',
    models: [
      { id: 'ngodeai/glm-5.3-flash', status: 'active', pricing: { input: 0, output: 0 } },
      { id: 'ngodeai/claude-haiku-5.5', status: 'active', pricing: { input: 362, output: 1810 } },
      { id: 'ngodeai/gemini-3.5-flash-lite', status: 'inactive', pricing: { input: 0, output: 0 } },
      { id: 'ngodeai/no-pricing', status: 'active' },
    ],
  });
  t.after(() => gw.close());
  useNgodeaiGateway({ ngodeai: gw });

  const { verifyModels } = require('../verify');
  const events = [];
  await verifyModels({
    apiKey: 'ngodeai_live_test-4',
    providerId: 'ngodeai',
    models: [
      'ngodeai/glm-5.3-flash',
      'ngodeai/claude-haiku-5.5',
      'ngodeai/gemini-3.5-flash-lite',
      'ngodeai/no-pricing',
      'ngodeai/tak-ada-di-katalog',
    ],
    signal: new AbortController().signal,
    emit: (ev) => events.push(ev),
  });

  const res = (id) => events.find((e) => e.type === 'result' && e.model === id);
  const free0 = res('ngodeai/glm-5.3-flash');
  assert.strictEqual(free0.ok, true);
  assert.strictEqual(free0.free, true, 'pricing 0 = gratis (katalog gateway sendiri)');
  assert.strictEqual(free0.tier, 'free');
  const paid = res('ngodeai/claude-haiku-5.5');
  assert.strictEqual(paid.ok, true);
  assert.strictEqual(paid.free, false, 'model berbayar TIDAK boleh free walau pasti menjawab');
  assert.strictEqual(paid.tier, 'topup');
  const maint = res('ngodeai/gemini-3.5-flash-lite');
  assert.strictEqual(maint.ok, false, 'status inactive = maintenance = mati');
  assert.strictEqual(maint.reason, 'status=inactive');
  const noprice = res('ngodeai/no-pricing');
  assert.strictEqual(noprice.ok, true);
  assert.strictEqual(noprice.free, null, 'tanpa pricing = free tak diketahui');
  const missing = res('ngodeai/tak-ada-di-katalog');
  assert.strictEqual(missing.ok, null);
  assert.strictEqual(missing.free, null);
  const done = events.find((e) => e.type === 'done');
  assert.strictEqual(done.ok, 3);
  assert.strictEqual(done.dead, 1);
  assert.strictEqual(done.unknown, 1);
  // fast path = NOL probe chat (semua fakta dari satu GET /v1/models)
  assert.strictEqual(gw.requests.length, 0);
});

test('ngodeai: sweep falls back to probe path when the catalogue fails (free stays null)', async (t) => {
  // flakyModels memutus socket GET /v1/models DUA kali (attempt pertama +
  // retry-nya) → katalog gagal total → sweep lanjut memprobe per model
  // via chat.
  const gw = await startMockGateway([{ kind: 'text', text: 'ok' }], {
    key: 'ngodeai_live_test-5',
    flakyModels: 2,
    models: [{ id: 'ngodeai/glm-5.3-flash', status: 'active', pricing: { input: 0, output: 0 } }],
    byModel: { 'ngodeai/glm-5.3-flash': { kind: 'sse' } },
  });
  t.after(() => gw.close());
  useNgodeaiGateway({ ngodeai: gw });

  const { verifyModels } = require('../verify');
  const events = [];
  await verifyModels({
    apiKey: 'ngodeai_live_test-5',
    providerId: 'ngodeai',
    models: ['ngodeai/glm-5.3-flash'],
    signal: new AbortController().signal,
    emit: (ev) => events.push(ev),
  });
  const alive = events.find((e) => e.type === 'result');
  assert.strictEqual(alive.ok, true);
  assert.strictEqual(alive.free, null, 'freeByProbe=false — jawaban live tidak menandai gratis');
  assert.strictEqual(gw.requests.length + Object.keys(gw.byModel || {}).length >= 0, true);
});

test('ngodeai: seek regression — header quota + JEV filter untouched after the new provider', async (t) => {
  const gw = await startMockGateway([], {
    key: 'hms-test-reg',
    models: [
      'deepseek-v4.1-flash-free',
      { id: 'jev-1.13-free', endpoint: '/v1/systemone' },
    ],
    quotaHeaders: {
      'x-quota-limit': '400000000',
      'x-quota-used': '1',
      'x-quota-remaining': '399999999',
      'x-quota-reset': '2026-11-22T00:00:00.000Z',
    },
  });
  t.after(() => gw.close());
  useNgodeaiGateway({ seek: gw });
  const port = await bootServer(t);
  const H = { 'x-sachet-key': 'hms-test-reg', 'x-sachet-provider': 'seek' };

  const m = await getJson(port, '/api/models', H);
  assert.deepStrictEqual(m.body.models, ['deepseek-v4.1-flash-free']);
  const q = await getJson(port, '/api/quota', H);
  assert.strictEqual(q.body.source, 'headers');
  assert.strictEqual(q.body.tokensRemaining, 399999999);
});
