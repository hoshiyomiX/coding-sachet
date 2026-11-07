'use strict';
// Integration tests: the full agent loop against the mock gateway,
// plus the HTTP surface of server.js — for BOTH providers (APInex
// and NVIDIA NIM), which the mock impersonates on separate ports.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway, collectSse, getJson } = require('./mock-gateway');

// Isolated env for the server under test.
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'pkt-srv-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0'; // will be replaced once we know a free port

/** Point both providers at the given mock gateways and reset the roots. */
function useGateways({ apinex, nvidia }) {
  const { config } = require('../config');
  if (apinex) config.providers.apinex.baseUrl = `http://127.0.0.1:${apinex.port}/v1`;
  if (nvidia) config.providers.nvidia.baseUrl = `http://127.0.0.1:${nvidia.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  return config;
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
  const gw = await startMockGateway([{ kind: 'error', status: 429, message: 'Too many requests' }]);
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
});

test('agent loop: NVIDIA NIM rate-limit (429) hint differs from APInex', async (t) => {
  const gw = await startMockGateway([{ kind: 'error', status: 429, message: 'Too many requests' }]);
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

test('HTTP chat route: gateway errors become error + done SSE events', async (t) => {
  const gw = await startMockGateway([{ kind: 'error', status: 429, message: 'Quota habis' }]);
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
