'use strict';
// Integration tests: the HTTP surface of server.js (engine oc-only —
// Pi & legacy DIHAPUS; chat butuh biner OpenCode yang tidak ada di
// lingkungan test, jadi rute chat diuji jalur error jelasnya).

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway, collectSse, getJson, postJson } = require('./mock-gateway');

// Isolated env for the server under test.
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-srv-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0'; // will be replaced once we know a free port
// The /api/dns route writes the guest resolver config — point it at a temp
// file so tests never touch the real /etc/resolv.conf.
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
// Keep the fire-and-forget gateway warm-up out of the test process.
process.env.SACHET_NO_WARMUP = '1';
// Chat route is oc-ONLY (Pi & legacy deleted). Dev machines may have the real
// binary at /opt/sachet/opencode/bin/opencode — force the no-binary path so
// the suite never spawns a live `opencode serve` (hermetic + fast). Must be
// set BEFORE the first require of oc-bridge (OC_BIN is read at module load).
process.env.SACHET_OC_BIN = '/nonexistent/opencode';

/** Point both providers at the given mock gateways and reset the roots. */
function useGateways({ apinex, nvidia }) {
  const { config } = require('../config');
  if (apinex) config.providers.apinex.baseUrl = `http://127.0.0.1:${apinex.port}/v1`;
  if (nvidia) config.providers.nvidia.baseUrl = `http://127.0.0.1:${nvidia.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  // The client-side RPM limiter keeps state across tests otherwise (the
  // suite's chat volume would eventually wait out a 60s window).
  require('../ratelimit').reset();
  return config;
}

/** Poll until cond() is true (warm-up fetches are fire-and-forget). */
async function waitUntil(cond, ms = 3000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (cond()) return;
    await new Promise((r) => setTimeout(r, 25));
  }
  assert.ok(cond(), 'condition never became true');
}


test('HTTP chat route: engine oc-only — tanpa biner → error JELAS, tanpa fallback senyap', async (t) => {
  // Pi & legacy dihapus: satu-satunya jalur chat adalah OpenCode. Di
  // lingkungan test biner tidak ada (SACHET_OC_BIN default /opt/sachet/…)
  // → route harus menjawab error+done via SSE dan TIDAK memanggil gateway
  // (fallback legacy lama sudah tidak ada).
  const gw = await startMockGateway([{ kind: 'text', text: 'pong' }]);
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const events = await collectSse(
    port, '/api/chat',
    { 'x-sachet-key': 'sk-test-123', 'content-type': 'application/json' },
    { model: 'test-pro', messages: [{ role: 'user', content: 'hai' }], options: { oc: { orchestrator: 'a', executor: 'b' }, temperature: 0.5 } }
  );
  const err = events.find((e) => e.type === 'error');
  assert.ok(err && /OpenCode/.test(err.message), 'error menyebut engine OpenCode: ' + (err && err.message));
  assert.strictEqual(events.at(-1).type, 'done');
  assert.strictEqual(gw.requests.length, 0, 'tidak ada panggilan gateway chat (fallback sudah dihapus)');
});

test('HTTP server: unknown provider is rejected with 400', async (t) => {
  const gw = await startMockGateway([]);
  t.after(() => gw.close());

  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const r = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123', 'x-sachet-provider': 'bogus' });
  assert.strictEqual(r.status, 400);
  assert.ok(r.body.error.includes('Provider tidak dikenal'));

  const chat = await collectSse(
    port, '/api/chat',
    { 'x-sachet-key': 'sk-test-123', 'x-sachet-provider': 'bogus', 'content-type': 'application/json' },
    { model: 'x', messages: [{ role: 'user', content: 'hai' }] }
  ).catch((e) => e);
  // The route answers 400 JSON before the SSE stream starts.
  assert.ok(String(chat).includes('HTTP 400'));
});

test('HTTP server: X-Sachet-Provider routes models to the right gateway', async (t) => {
  const ap = await startMockGateway([]);
  const nv = await startMockGateway([], { key: 'nvapi-test-123', models: ['nvidia/nemotron-3.5-bolt-e4b', 'deepseek-ai/deepseek-v4.1-flash'] });
  t.after(() => ap.close());
  t.after(() => nv.close());

  useGateways({ apinex: ap, nvidia: nv });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  // default (no header) → apinex
  const def = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123' });
  assert.strictEqual(def.status, 200);
  assert.deepStrictEqual(def.body.models, ['test-mini', 'test-pro']);

  // nvidia header → nvidia mock, with the nvidia key
  const nvRes = await getJson(port, '/api/models', { 'x-sachet-key': 'nvapi-test-123', 'x-sachet-provider': 'nvidia' });
  assert.strictEqual(nvRes.status, 200);
  assert.deepStrictEqual(nvRes.body.models, ['deepseek-ai/deepseek-v4.1-flash', 'nvidia/nemotron-3.5-bolt-e4b']);
  assert.strictEqual(ap.modelRequests.length, 1); // only the default call hit apinex
  assert.strictEqual(nv.modelRequests.length, 1);

  // wrong key for the chosen provider still fails
  const bad = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123', 'x-sachet-provider': 'nvidia' });
  assert.strictEqual(bad.status, 502);
});

test('HTTP server: health, models proxy, chat SSE, file explorer', async (t) => {
  const gw = await startMockGateway([
    { kind: 'tools', calls: [] },
    { kind: 'text', text: 'File ditulis.' },
  ]);
  t.after(() => gw.close());

  const config = useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  // health
  const h = await getJson(port, '/health');
  assert.strictEqual(h.status, 200);
  assert.strictEqual(h.body.ok, true);
  assert.strictEqual(h.body.providers.apinex, `http://127.0.0.1:${gw.port}/v1`);
  assert.ok('nvidia' in h.body.providers);

  // models proxy: good key and bad key
  const ok = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123' });
  assert.strictEqual(ok.status, 200);
  assert.deepStrictEqual(ok.body.models, ['test-mini', 'test-pro']);
  const bad = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-salah' });
  assert.strictEqual(bad.status, 502);

  // chat: oc-only tanpa biner → error JELAS, bukan fallback senyap
  const events = await collectSse(
    port, '/api/chat',
    { 'x-sachet-key': 'sk-test-123', 'content-type': 'application/json' },
    { model: 'test-pro', messages: [{ role: 'user', content: 'tulis file notes.txt' }] }
  );
  assert.ok(events.some((e) => e.type === 'error' && /OpenCode/.test(e.message || '')), 'error menyebut engine OpenCode');
  assert.ok(events.at(-1).type === 'done');

  // file explorer API — buat file langsung (chat tidak lagi membuat file:
  // engine oc butuh biner yang tidak ada di lingkungan test).
  fs.writeFileSync(path.join(ROOT, 'root/work/notes.txt'), 'dari agent');
  const ls = await getJson(port, `/api/fs?path=${encodeURIComponent(config.workdir)}`);
  assert.strictEqual(ls.status, 200);
  assert.ok(ls.body.entries.some((e) => e.name === 'notes.txt'));

  const cat = await getJson(port, `/api/fs/file?path=${encodeURIComponent(path.join(ROOT, 'root/work/notes.txt'))}`);
  assert.strictEqual(cat.status, 200);
  assert.strictEqual(cat.body.content, 'dari agent');

  const outside = await getJson(port, `/api/fs?path=${encodeURIComponent('/etc')}`);
  assert.strictEqual(outside.status, 400);
});

test('HTTP server: models are cached per provider+key (5 min TTL)', async (t) => {
  const ap = await startMockGateway([]);
  const nv = await startMockGateway([], { key: 'nvapi-test-123', models: ['nv-a'] });
  t.after(() => ap.close());
  t.after(() => nv.close());

  useGateways({ apinex: ap, nvidia: nv });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  // first call hits the gateway
  const first = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123' });
  assert.strictEqual(first.status, 200);
  assert.deepStrictEqual(first.body.models, ['test-mini', 'test-pro']);
  assert.notStrictEqual(first.body.cached, true);

  // second call is served from cache: same payload, no extra gateway hit
  const second = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123' });
  assert.strictEqual(second.status, 200);
  assert.strictEqual(second.body.cached, true);
  assert.deepStrictEqual(second.body.models, first.body.models);
  assert.strictEqual(ap.modelRequests.length, 1);

  // the SAME key under the nvidia provider is a different cache entry
  const nvRes = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123', 'x-sachet-provider': 'nvidia' });
  assert.strictEqual(nvRes.status, 502); // sk-… is not the nvidia mock's key
  assert.strictEqual(nv.modelRequests.length, 1);

  // a different key must not reuse the cache (and its failure is not cached)
  const other = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123-lain' });
  assert.strictEqual(other.status, 502);
  const retryOther = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-test-123-lain' });
  assert.strictEqual(retryOther.status, 502);
  assert.strictEqual(ap.modelRequests.length, 3);
});

test('HTTP server: /api/dns rewrites the guest resolver (VPN-aware DNS push)', async (t) => {
  const gw = await startMockGateway([]);
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const r = await postJson(port, '/api/dns', {
    servers: ['10.11.12.13', '10.11.12.13', 'bogus', 'fd00::1', '1.2.3.4', '5.6.7.8', '9.9.9.10'],
  });
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body.ok, true);
  // deduped, validated, max 3 system servers — fallbacks appended after.
  assert.deepStrictEqual(
    r.body.nameservers,
    ['10.11.12.13', 'fd00::1', '1.2.3.4', '1.1.1.1', '8.8.8.8']
  );

  const written = fs.readFileSync(process.env.SACHET_RESOLV_CONF, 'utf8');
  assert.ok(written.includes('# managed by coding-sachet'));
  assert.ok(written.includes('nameserver 10.11.12.13'));
  assert.ok(written.includes('options timeout:1 attempts:2 single-request-reopen'));

  const g = await getJson(port, '/api/dns');
  assert.strictEqual(g.status, 200);
  assert.ok(g.body.nameservers.includes('10.11.12.13'));
});

test('HTTP server: /api/quota serves APInex token counters via the single documented API (native + cache + fresh)', async (t) => {
  const gw = await startMockGateway([], {
    key: 'sk-quota-1',
    quota: { unlimited: false, tokenLimit: 20000000, tokensUsed: 700000, tokensRemaining: 19300000 },
  });
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const r = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-quota-1' });
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body.supported, true);
  assert.strictEqual(r.body.source, 'native');
  assert.strictEqual(r.body.unlimited, false);
  assert.strictEqual(r.body.tokensRemaining, 19300000);
  assert.strictEqual(r.body.tokensUsed, 700000);
  assert.strictEqual(r.body.tokenLimit, 20000000);
  // T-ML49: mode/plan_label diteruskan — chip membedakan 0 asli vs key
  // free yang belum unlock top-up $5 (bukti probe 2026-11-20).
  assert.strictEqual(r.body.mode, 'free');
  assert.strictEqual(r.body.planLabel, 'Free');
  // ONE documented API per feature: exactly one gateway hit per fetch.
  assert.strictEqual(gw.quotaRequests, 1); // /v1/subscription only

  // second call within the TTL is cached — no extra gateway hits
  const r2 = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-quota-1' });
  assert.strictEqual(r2.status, 200);
  assert.strictEqual(r2.body.cached, true);
  assert.strictEqual(r2.body.tokensRemaining, 19300000);
  assert.strictEqual(gw.quotaRequests, 1);

  // ?fresh=1 skips the cache read (still refreshes it)
  const r3 = await getJson(port, '/api/quota?fresh=1', { 'x-sachet-key': 'sk-quota-1' });
  assert.strictEqual(r3.status, 200);
  assert.notStrictEqual(r3.body.cached, true);
  assert.strictEqual(gw.quotaRequests, 2);

  // NIM has no per-key balance API
  const nv = await getJson(port, '/api/quota', { 'x-sachet-key': 'nvapi-x', 'x-sachet-provider': 'nvidia' });
  assert.strictEqual(nv.status, 200);
  assert.strictEqual(nv.body.supported, false);

  // a rejected key surfaces the gateway error
  const bad = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-salah' });
  assert.strictEqual(bad.status, 502);
  assert.ok(bad.body.error.includes('Invalid token'));
});

test('HTTP server: /api/quota errors cleanly when the documented quota API is absent (no fallback chain)', async (t) => {
  // No opts.quota → /v1/subscription answers 404. The former new-api
  // billing fallback is gone (single-official-API order): the 404 must
  // surface as a quota failure, not as fabricated counters.
  const gw = await startMockGateway([], { key: 'sk-quota-2' });
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const r = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-quota-2' });
  assert.strictEqual(r.status, 502);
  assert.ok(r.body.error.includes('404'));
  assert.strictEqual(gw.quotaRequests, 1);
});

test('HTTP server: /api/quota keeps a JSON null null — no fake "Sisa token: 0" (T-ML48)', async (t) => {
  // Docs 2026-11-20 (apinex.bond/developers/balance): tokens_remaining is
  // "number | null". The old num() did Number(null) === 0, so a null with
  // unlimited:false printed a fake 0 in the chip. null must survive to the
  // app (Kotlin shows "?" — "tidak diketahui" — instead of lying "0").
  const gw = await startMockGateway([], {
    key: 'sk-quota-3',
    quota: { unlimited: false, tokenLimit: null, tokensUsed: 0, tokensRemaining: null },
  });
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const r = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-quota-3' });
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body.supported, true);
  assert.strictEqual(r.body.unlimited, false);
  assert.strictEqual(r.body.tokensRemaining, null, 'null tetap null — BUKAN 0 (Number(null) trap)');
  assert.strictEqual(r.body.tokensUsed, 0);
  assert.strictEqual(r.body.tokenLimit, null);
  // Sanity: angka nyata tetap lolos utuh.
  const gw2 = await startMockGateway([], {
    key: 'sk-quota-4',
    quota: { unlimited: false, tokenLimit: 20000000, tokensUsed: 700000, tokensRemaining: 19300000 },
  });
  t.after(() => gw2.close());
  useGateways({ apinex: gw2 });
  const r2 = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-quota-4' });
  assert.strictEqual(r2.status, 200);
  assert.strictEqual(r2.body.tokensRemaining, 19300000);
});
test('HTTP server: /api/models survives a dropped first connection', async (t) => {
  const gw = await startMockGateway([], { flakyModels: 1, key: 'sk-flaky-1' });
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const r = await getJson(port, '/api/models', { 'x-sachet-key': 'sk-flaky-1' });
  assert.strictEqual(r.status, 200);
  assert.deepStrictEqual(r.body.models, ['test-mini', 'test-pro']);
  assert.strictEqual(gw.modelAttempts, 2);
});

test('HTTP server: extra allowed roots (root mode) are browsable via /api/fs', async (t) => {
  const gw = await startMockGateway([]);
  t.after(() => gw.close());
  const config = useGateways({ apinex: gw });

  // What SACHET_EXTRA_ROOTS="/,/device" does at boot: extra roots joined
  // onto the sandbox list (the app sends this env only in root mode).
  const deviceDir = path.join(ROOT, 'device');
  fs.mkdirSync(path.join(deviceDir, 'system'), { recursive: true });
  fs.writeFileSync(path.join(deviceDir, 'system', 'build.prop'), 'ro.build.id=test');
  const origRoots = config.allowedRoots.slice();
  config.allowedRoots.push('/', deviceDir);

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  try {
    // The extra root itself lists fine…
    const ls = await getJson(port, '/api/fs?path=' + encodeURIComponent(deviceDir));
    assert.strictEqual(ls.status, 200);
    assert.ok(ls.body.entries.some((e) => e.name === 'system'));

    // …files under it are readable through the file API…
    const read = await getJson(port, '/api/fs/file?path=' + encodeURIComponent(path.join(deviceDir, 'system', 'build.prop')));
    assert.strictEqual(read.status, 200);
    assert.strictEqual(read.body.content, 'ro.build.id=test');

    // …and with "/" allowed, any guest path resolves (up-navigation works).
    const guestEtc = await getJson(port, '/api/fs?path=' + encodeURIComponent(path.join(ROOT, 'guest-etc-missing')));
    assert.strictEqual(guestEtc.status, 404); // allowed root, missing dir → stat error, not 400
  } finally {
    config.allowedRoots.length = 0;
    for (const r of origRoots) config.allowedRoots.push(r);
  }

  // Without the extra roots the sandbox stays sealed.
  const outside = await getJson(port, '/api/fs?path=' + encodeURIComponent('/etc'));
  assert.strictEqual(outside.status, 400);
});

test('agent loop: huge read_file results are truncated without slurping the file', async (t) => {
  const gw = await startMockGateway([]);
  t.after(() => gw.close());
  useGateways({ apinex: gw });
  const { config } = require('../config');

  const big = path.join(config.workdir, 'big.log');
  const chunk = 'x'.repeat(64 * 1024) + '\n';
  fs.writeFileSync(big, chunk.repeat(32)); // 2 MB, well above readMaxBytes

  const { readFile } = require('../tools');
  const r = readFile(big);
  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.truncated, true);
  assert.strictEqual(r.size, 32 * chunk.length);
  assert.strictEqual(Buffer.byteLength(r.content, 'utf8'), config.readMaxBytes);
});

// ---------- cold-path connection fixes (NIM under VPN, T-ML13) ----------

// The owner's field log: the first warm-up through a cold VPN times out
// ("The operation was aborted due to timeout"), every later one answers
// in under a second. These tests pin the healing behaviour.

test('net: fetchRetryTimeout retries a timed-out attempt with a fresh budget', async (t) => {
  const gw = await startMockGateway([], { models: ['cold-ok'], slowModels: 1, slowModelsMs: 400 });
  t.after(() => gw.close());
  const { fetchRetryTimeout } = require('../net');

  // The first /models reply sleeps 400 ms; the client times out at 120 ms
  // and the retry — with a FRESH budget — lands on the fast second reply.
  const res = await fetchRetryTimeout(
    `http://127.0.0.1:${gw.port}/v1/models`,
    { headers: { Authorization: 'Bearer sk-test-123' } },
    { timeoutMs: 120, retries: 1, delays: [50] }
  );
  assert.strictEqual(res.status, 200);
  await res.json();
  assert.strictEqual(gw.modelAttempts, 2);
});

test('net: fetchRetryTimeout never retries a caller abort', async (t) => {
  const gw = await startMockGateway([]);
  t.after(() => gw.close());
  const { fetchRetryTimeout } = require('../net');

  const ac = new AbortController();
  ac.abort();
  await assert.rejects(
    fetchRetryTimeout(`http://127.0.0.1:${gw.port}/v1/models`, { signal: ac.signal }, { timeoutMs: 5000, retries: 2 }),
    /abort/i
  );
  assert.strictEqual(gw.modelAttempts, 0); // thrown, not retried
});

test('HTTP server: /api/models survives a slow first reply (cold VPN path)', async (t) => {
  const gw = await startMockGateway([], { slowModels: 1, slowModelsMs: 400, key: 'sk-slow-1' });
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { fetchModels } = require('../server');
  const { providerById } = require('../config');
  // Production uses 15 s budgets; the test injects the same retry SHAPE
  // with a tiny budget so the timeout→retry path runs in milliseconds.
  const ids = await fetchModels('sk-slow-1', providerById('apinex'), { timeoutMs: 120, retries: 1, delays: [50] });
  assert.deepStrictEqual(ids, ['test-mini', 'test-pro']);
  assert.strictEqual(gw.modelAttempts, 2);
});

test('warm-up: a second round within the spacing window is throttled out', async (t) => {
  const gw = await startMockGateway([]);
  t.after(() => gw.close());
  const config = useGateways({ apinex: gw, nvidia: gw });
  const orig = config.warmupMinIntervalMs;
  config.warmupMinIntervalMs = 15000;
  const { warmUpGateways } = require('../server');

  assert.strictEqual(warmUpGateways(), true);
  await waitUntil(() => gw.modelAttempts >= 2); // both providers warmed
  const settled = gw.modelAttempts;

  // The DNS push that follows server startup fires again within the same
  // second — throttled out, so no duplicate round and no duplicate log
  // lines (every warm-up line used to appear twice in Log server).
  assert.strictEqual(warmUpGateways(), false);
  await new Promise((r) => setTimeout(r, 250));
  assert.strictEqual(gw.modelAttempts, settled);
  config.warmupMinIntervalMs = orig;
});

test('warm-up: a dropped first connection is retried, then logged OK', async (t) => {
  const flaky = await startMockGateway([], { flakyModels: 1 });
  const clean = await startMockGateway([]);
  t.after(() => { flaky.close(); clean.close(); });
  const config = useGateways({ apinex: flaky, nvidia: clean });
  const orig = config.warmupMinIntervalMs;
  config.warmupMinIntervalMs = 0; // the previous test just warmed up
  const { warmUpGateways } = require('../server');

  assert.strictEqual(warmUpGateways(), true);
  await waitUntil(() => flaky.modelAttempts >= 2); // destroyed + retry
  assert.strictEqual(clean.modelAttempts, 1);
  config.warmupMinIntervalMs = orig;
});

test('T-ML33 server: unhandledRejection burst policy — isolated survives, burst is fatal', () => {
  const { makeUnhandledPolicy } = require('../server');
  const t0 = 1_000_000;

  // Default wiring: 5 hits inside the 60 s window trip the fuse.
  const p = makeUnhandledPolicy();
  assert.strictEqual(p.note(t0), false);
  assert.strictEqual(p.note(t0 + 1000), false);
  assert.strictEqual(p.note(t0 + 2000), false);
  assert.strictEqual(p.note(t0 + 3000), false);
  assert.strictEqual(p.note(t0 + 4000), true); // 5th inside the window

  // Isolated rejections spaced past the window never burst.
  const q = makeUnhandledPolicy();
  for (let i = 0; i < 12; i++) assert.strictEqual(q.note(t0 + i * 61_000), false);

  // Custom wiring sanity.
  const r = makeUnhandledPolicy({ limit: 2, windowMs: 1000 });
  assert.strictEqual(r.note(t0), false);
  assert.strictEqual(r.note(t0 + 500), true);
});
