'use strict';
// Integration tests: the full agent loop against the mock gateway,
// plus the HTTP surface of server.js — for BOTH providers (APInex
// and NVIDIA NIM), which the mock impersonates on separate ports.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway, collectSse, getJson, postJson } = require('./mock-gateway');

// Isolated env for the server under test.
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'pkt-srv-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0'; // will be replaced once we know a free port
// The /api/dns route writes the guest resolver config — point it at a temp
// file so tests never touch the real /etc/resolv.conf.
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
// Keep the fire-and-forget gateway warm-up out of the test process.
process.env.SACHET_NO_WARMUP = '1';

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

test('agent loop: tool round trip then final text (apinex)', async (t) => {
  const gw = await startMockGateway([
    {
      kind: 'tools',
      text: 'Aku cek dulu ya.',
      calls: [{ id: 'call_1', name: 'bash', args: { command: 'echo satu-dua-tiga' } }],
    },
    { kind: 'text', text: 'Selesai! Perintahnya mencetak satu-dua-tiga.' },
  ]);
  t.after(() => gw.close());

  const config = useGateways({ apinex: gw });
  const { runAgent } = require('../agent');
  const events = [];
  const added = await runAgent({
    apiKey: 'sk-test-123',
    providerId: 'apinex',
    model: 'test-pro',
    messages: [{ role: 'user', content: 'jalankan echo satu-dua-tiga' }],
    signal: null,
    emit: (e) => events.push(e),
  });

  const types = events.map((e) => e.type);
  assert.ok(types.includes('turn'));
  assert.ok(types.includes('think'));
  assert.ok(types.includes('text'));
  assert.ok(types.includes('tool'));
  assert.ok(types.includes('toolend'));
  assert.ok(types.includes('message'));
  assert.strictEqual(types.at(-1), 'done');

  const toolEvs = events.filter((e) => e.type === 'tool');
  assert.strictEqual(toolEvs[0].name, 'bash');
  assert.deepStrictEqual(toolEvs[0].args, { command: 'echo satu-dua-tiga' });

  const toolend = events.find((e) => e.type === 'toolend');
  assert.strictEqual(toolend.ok, true);
  assert.ok(toolend.output.includes('satu-dua-tiga'));

  // History: assistant(with toolCalls) + tool result + assistant(final)
  assert.strictEqual(added.filter((m) => m.role === 'assistant').length, 2);
  const first = added[0];
  assert.strictEqual(first.toolCalls[0].name, 'bash');
  assert.strictEqual(added[1].role, 'tool');
  assert.strictEqual(added[1].toolCallId, 'call_1');

  // The mock must have received the tool result as an OpenAI tool message.
  const second = gw.requests[1];
  const toolMsg = second.messages.find((m) => m.role === 'tool');
  assert.ok(toolMsg);
  assert.ok(toolMsg.content.includes('satu-dua-tiga'));
  assert.strictEqual(second.messages.at(-1).role, 'tool');
});

test('agent loop: gateway error surfaces as an error event', async (t) => {
  const gw = await startMockGateway([{ kind: 'error', status: 401, message: 'Invalid token' }]);
  t.after(() => gw.close());

  useGateways({ apinex: gw });
  const { runAgent } = require('../agent');

  // Direct call: the gateway error propagates out of runAgent.
  await assert.rejects(
    runAgent({
      apiKey: 'sk-salah', providerId: 'apinex', model: 'test-pro',
      messages: [{ role: 'user', content: 'hai' }],
      emit: () => {},
    }),
    (e) => e.message.includes('401') && e.message.includes('Invalid token')
  );
});

test('agent loop: APInex rate-limit (429) error carries a clear hint', async (t) => {
  const gw = await startMockGateway([{ kind: 'error', status: 429, message: 'Too many requests', headers: { 'retry-after': '0' } }]);
  t.after(() => gw.close());

  useGateways({ apinex: gw });
  const { runAgent } = require('../agent');

  await assert.rejects(
    runAgent({
      apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
      messages: [{ role: 'user', content: 'hai' }],
      emit: () => {},
    }),
    (e) => e.message.includes('429') && e.message.includes('Too many requests') &&
      e.message.includes('Batas laju APInex tercapai')
  );
  // The bounded 429 retries happened before giving up (1 initial + 2 retries).
  assert.strictEqual(gw.requests.length, 3);
});

test('agent loop: 429 is retried after retry-after, then succeeds', async (t) => {
  const gw = await startMockGateway([
    { kind: 'error', status: 429, message: 'Rate limit exceeded. Max 15 requests per minute', headers: { 'retry-after': '0' } },
    { kind: 'text', text: 'Oke, lanjut lagi.' },
  ]);
  t.after(() => gw.close());

  useGateways({ apinex: gw });
  const { runAgent } = require('../agent');
  const events = [];
  const added = await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'hai' }],
    emit: (e) => events.push(e),
  });

  assert.strictEqual(added.length, 1);
  assert.ok(added[0].content.includes('lanjut lagi'));
  assert.strictEqual(gw.requests.length, 2);
  // A ratelimit wait hint was surfaced while backing off.
  const waits = events.filter((e) => e.type === 'ratelimit');
  assert.ok(waits.length >= 1);
  assert.ok(waits[0].waitMs >= 500);
});

test('agent loop: NVIDIA NIM rate-limit (429) hint differs from APInex', async (t) => {
  const gw = await startMockGateway([{ kind: 'error', status: 429, message: 'Too many requests', headers: { 'retry-after': '0' } }]);
  t.after(() => gw.close());

  useGateways({ nvidia: gw });
  const { runAgent } = require('../agent');

  await assert.rejects(
    runAgent({
      apiKey: 'nvapi-test-123', providerId: 'nvidia', model: 'nvidia/nemotron-3.5-bolt-e4b',
      messages: [{ role: 'user', content: 'hai' }],
      emit: () => {},
    }),
    (e) => e.message.includes('429') &&
      e.message.includes('Batas laju NVIDIA NIM tercapai') &&
      !e.message.includes('Batas laju APInex')
  );
});

test('agent loop: NVIDIA requests carry max_tokens, APInex requests do not', async (t) => {
  const nv = await startMockGateway([{ kind: 'text', text: 'ok' }], { key: 'nvapi-test-123' });
  const ap = await startMockGateway([{ kind: 'text', text: 'ok' }]);
  t.after(() => nv.close());
  t.after(() => ap.close());

  useGateways({ apinex: ap, nvidia: nv });
  const { runAgent } = require('../agent');

  await runAgent({
    apiKey: 'nvapi-test-123', providerId: 'nvidia', model: 'nv-x',
    messages: [{ role: 'user', content: 'hai' }],
    emit: () => {},
  });
  assert.strictEqual(nv.requests[0].max_tokens, 4096);
  assert.strictEqual(nv.chatAuths[0], 'Bearer nvapi-test-123');

  await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'hai' }],
    emit: () => {},
  });
  assert.strictEqual('max_tokens' in ap.requests[0], false);
});

test('JSON options: sanitized fields reach the gateway body, reserved keys never do', async (t) => {
  const ap = await startMockGateway([{ kind: 'text', text: 'ok' }]);
  const nv = await startMockGateway([{ kind: 'text', text: 'ok' }], { key: 'nvapi-test-123' });
  t.after(() => ap.close());
  t.after(() => nv.close());

  useGateways({ apinex: ap, nvidia: nv });
  const { runAgent } = require('../agent');

  await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'hai' }],
    options: {
      temperature: 0.4,
      top_p: 0.9,
      max_tokens: 512,
      presence_penalty: -0.5,
      frequency_penalty: 1.5,
      seed: 42,
      stop: ['\n\n', 'SELESAI'],
      reasoning_effort: 'medium',
      // reserved — must be stripped
      model: 'gateway-hax', stream: false, n: 5, tool_choice: 'required',
      // invalid values — must be dropped
      temperature2: 'x', bogus: { deep: [1, 2] },
    },
    emit: () => {},
  });
  const b = ap.requests[0];
  assert.strictEqual(b.temperature, 0.4);
  assert.strictEqual(b.top_p, 0.9);
  assert.strictEqual(b.max_tokens, 512);
  assert.strictEqual(b.presence_penalty, -0.5);
  assert.strictEqual(b.frequency_penalty, 1.5);
  assert.strictEqual(b.seed, 42);
  assert.deepStrictEqual(b.stop, ['\n\n', 'SELESAI']);
  assert.strictEqual(b.reasoning_effort, 'medium');
  assert.strictEqual(b.model, 'test-pro');
  assert.strictEqual(b.stream, true);
  assert.strictEqual('n' in b, false);
  assert.strictEqual(b.tool_choice, 'auto'); // the agent's own value

  // Out-of-range numbers are dropped, not fatal.
  await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'hai' }],
    options: { temperature: 9.9, top_p: 'nope', max_tokens: -5, reasoning_effort: 'ultra' },
    emit: () => {},
  });
  const b2 = ap.requests[1];
  assert.strictEqual('temperature' in b2, false);
  assert.strictEqual('top_p' in b2, false);
  assert.strictEqual('max_tokens' in b2, false);
  assert.strictEqual('reasoning_effort' in b2, false);

  // A user max_tokens overrides the NIM provider default (4096).
  await runAgent({
    apiKey: 'nvapi-test-123', providerId: 'nvidia', model: 'nv-x',
    messages: [{ role: 'user', content: 'hai' }],
    options: { max_tokens: 2048 },
    emit: () => {},
  });
  assert.strictEqual(nv.requests[0].max_tokens, 2048);
});

test('HTTP chat route: gateway errors become error + done SSE events', async (t) => {
  const gw = await startMockGateway([{ kind: 'error', status: 429, message: 'Quota habis', headers: { 'retry-after': '0' } }]);
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
    { model: 'test-pro', messages: [{ role: 'user', content: 'hai' }] }
  );
  const err = events.find((e) => e.type === 'error');
  assert.ok(err && err.message.includes('Quota habis'));
  assert.strictEqual(events.at(-1).type, 'done');
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
    { kind: 'tools', calls: [{ id: 'c1', name: 'write_file', args: { path: path.join(ROOT, 'root/work/notes.txt'), content: 'dari agent' } }] },
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

  // chat: agent writes a real file through the tool, then answers
  const events = await collectSse(
    port, '/api/chat',
    { 'x-sachet-key': 'sk-test-123', 'content-type': 'application/json' },
    { model: 'test-pro', messages: [{ role: 'user', content: 'tulis file notes.txt' }] }
  );
  assert.ok(events.some((e) => e.type === 'tool' && e.name === 'write_file'));
  assert.ok(events.some((e) => e.type === 'toolend' && e.ok));
  assert.ok(events.at(-1).type === 'done');
  // The file must really exist on disk.
  assert.strictEqual(fs.readFileSync(path.join(ROOT, 'root/work/notes.txt'), 'utf8'), 'dari agent');

  // file explorer API
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

test('HTTP server: /api/quota serves APInex token counters (native + cache + fresh)', async (t) => {
  const gw = await startMockGateway([], {
    key: 'sk-quota-1',
    quota: { unlimited: false, tokenLimit: 20000000, tokensUsed: 700000, tokensRemaining: 19300000, balanceUsd: 38.6 },
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
  assert.strictEqual(r.body.balanceUsd, 38.6);
  assert.strictEqual(gw.quotaRequests, 2); // subscription + balance

  // second call within the TTL is cached — no extra gateway hits
  const r2 = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-quota-1' });
  assert.strictEqual(r2.status, 200);
  assert.strictEqual(r2.body.cached, true);
  assert.strictEqual(r2.body.tokensRemaining, 19300000);
  assert.strictEqual(gw.quotaRequests, 2);

  // ?fresh=1 skips the cache read (still refreshes it)
  const r3 = await getJson(port, '/api/quota?fresh=1', { 'x-sachet-key': 'sk-quota-1' });
  assert.strictEqual(r3.status, 200);
  assert.notStrictEqual(r3.body.cached, true);
  assert.strictEqual(gw.quotaRequests, 4);

  // NIM has no per-key balance API
  const nv = await getJson(port, '/api/quota', { 'x-sachet-key': 'nvapi-x', 'x-sachet-provider': 'nvidia' });
  assert.strictEqual(nv.status, 200);
  assert.strictEqual(nv.body.supported, false);

  // a rejected key surfaces the gateway error
  const bad = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-salah' });
  assert.strictEqual(bad.status, 502);
  assert.ok(bad.body.error.includes('Invalid token'));
});

test('HTTP server: /api/quota falls back to the OpenAI billing pair when native is absent', async (t) => {
  const gw = await startMockGateway([], { key: 'sk-quota-2', billing: { hardLimitUsd: 40, totalUsage: 250 } });
  t.after(() => gw.close());
  useGateways({ apinex: gw });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const r = await getJson(port, '/api/quota', { 'x-sachet-key': 'sk-quota-2' });
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.body.source, 'billing');
  // (40 USD − 250 cents) × 500000 units/USD
  assert.strictEqual(r.body.tokensRemaining, 18750000);
  assert.strictEqual(r.body.balanceUsd, 37.5);
});

test('agent loop: history from a mid-session model switch is healed on the wire', async (t) => {
  const gw = await startMockGateway([{ kind: 'text', text: 'siap' }]);
  t.after(() => gw.close());
  useGateways({ apinex: gw });
  const { runAgent } = require('../agent');

  // A completed round whose gateway streamed tool calls WITHOUT ids
  // (some models do), persisted by an older turn.
  await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'model-b',
    messages: [
      { role: 'user', content: 'tolong cek' },
      { role: 'assistant', content: '', toolCalls: [{ id: '', name: 'bash', args: { command: 'pwd' } }] },
      { role: 'tool', content: 'exit=0\n/root/work', toolCallId: '', name: 'bash' },
      { role: 'user', content: 'lanjut' },
    ],
    emit: () => {},
  });

  const sent = gw.requests[0].messages;
  const withCalls = sent.find((m) => m.role === 'assistant' && m.tool_calls);
  assert.ok(withCalls, 'the completed round keeps its tool_calls');
  assert.ok(withCalls.tool_calls[0].id.length > 4, 'empty ids are synthesized');
  const toolMsg = sent.find((m) => m.role === 'tool');
  assert.strictEqual(toolMsg.tool_call_id, withCalls.tool_calls[0].id);
});

test('agent loop: interrupted tool rounds and orphan results never reach the gateway', async (t) => {
  const gw = await startMockGateway([{ kind: 'text', text: 'oke' }]);
  t.after(() => gw.close());
  useGateways({ apinex: gw });
  const { runAgent } = require('../agent');

  await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'model-b',
    messages: [
      { role: 'user', content: 'cek config' },
      // orphan result: the assistant message that asked for it is gone
      { role: 'tool', content: 'exit=0\nhantu', toolCallId: 'ghost', name: 'bash' },
      // interrupted round: stop was pressed before the tool answered
      // (its result never landed, the next message is a user turn)
      { role: 'assistant', content: 'Aku cek dulu—', toolCalls: [{ id: '', name: 'bash', args: { command: 'cat app.conf' } }] },
      { role: 'user', content: 'sudah cukup, lanjut' },
    ],
    emit: () => {},
  });

  const sent = gw.requests[0].messages;
  assert.ok(!sent.some((m) => m.role === 'assistant' && m.tool_calls), 'dangling tool_calls are folded away');
  assert.ok(!sent.some((m) => m.role === 'tool'), 'orphan tool results are dropped');
  const plain = sent.find((m) => m.role === 'assistant');
  assert.strictEqual(plain.content, 'Aku cek dulu—');
});

test('agent loop: a connect failure (VPN bring-up race) is retried, not failed', async (t) => {
  const gw = await startMockGateway([{ kind: 'text', text: 'ok' }], { flakyChat: 1 });
  t.after(() => gw.close());
  useGateways({ apinex: gw });
  const { runAgent } = require('../agent');

  const added = await runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'x',
    messages: [{ role: 'user', content: 'hai' }],
    emit: () => {},
  });
  assert.strictEqual(added[0].content, 'ok');
  assert.strictEqual(gw.chatAttempts, 2); // first destroyed, retry delivered
  assert.strictEqual(gw.requests.length, 1);
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
