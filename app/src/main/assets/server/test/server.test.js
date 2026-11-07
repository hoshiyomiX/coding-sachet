'use strict';
// Integration tests: the full agent loop against the mock gateway,
// plus the HTTP surface of server.js.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway, collectSse, getJson } = require('./mock-gateway');

// Isolated env for the server under test.
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'ahm-srv-'));
process.env.AIHUBMIX_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.AIHUBMIX_PORT = '0'; // will be replaced once we know a free port

test('agent loop: tool round trip then final text', async (t) => {
  const gw = await startMockGateway([
    {
      kind: 'tools',
      text: 'Aku cek dulu ya.',
      calls: [{ id: 'call_1', name: 'bash', args: { command: 'echo satu-dua-tiga' } }],
    },
    { kind: 'text', text: 'Selesai! Perintahnya mencetak satu-dua-tiga.' },
  ]);
  t.after(() => gw.close());

  const { config } = require('../config');
  config.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });

  const { runAgent } = require('../agent');
  const events = [];
  const added = await runAgent({
    apiKey: 'sk-test-123',
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

  const { config } = require('../config');
  config.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  const { runAgent } = require('../agent');

  // Direct call: the gateway error propagates out of runAgent.
  await assert.rejects(
    runAgent({
      apiKey: 'sk-salah', model: 'test-pro',
      messages: [{ role: 'user', content: 'hai' }],
      emit: () => {},
    }),
    (e) => e.message.includes('401') && e.message.includes('Invalid token')
  );
});

test('agent loop: AIHubMix trial-exhausted note becomes a clear error, never chat text', async (t) => {
  const gw = await startMockGateway([{ kind: 'trial' }]);
  t.after(() => gw.close());

  const { config } = require('../config');
  config.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  const { runAgent } = require('../agent');

  const events = [];
  await assert.rejects(
    runAgent({
      apiKey: 'sk-test-123', model: 'test-pro',
      messages: [{ role: 'user', content: 'hai' }],
      emit: (e) => events.push(e),
    }),
    (e) => e.message.includes('Trial gratis AIHubMix habis') && e.message.includes('topup')
  );

  // The paywall text must never reach the app as a streamed answer.
  const leaked = events.some((e) => e.type === 'text' && /prevent abuse/.test(e.delta || ''));
  assert.ok(!leaked, 'paywall note leaked into text deltas');
});

test('HTTP chat route: gateway errors become error + done SSE events', async (t) => {
  const gw = await startMockGateway([{ kind: 'error', status: 429, message: 'Quota habis' }]);
  t.after(() => gw.close());

  const { config } = require('../config');
  config.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  const events = await collectSse(
    port, '/api/chat',
    { 'x-aihubmix-key': 'sk-test-123', 'content-type': 'application/json' },
    { model: 'test-pro', messages: [{ role: 'user', content: 'hai' }] }
  );
  const err = events.find((e) => e.type === 'error');
  assert.ok(err && err.message.includes('Quota habis'));
  assert.strictEqual(events.at(-1).type, 'done');
});

test('HTTP server: health, models proxy, chat SSE, file explorer', async (t) => {
  const gw = await startMockGateway([
    { kind: 'tools', calls: [{ id: 'c1', name: 'write_file', args: { path: path.join(ROOT, 'root/work/notes.txt'), content: 'dari agent' } }] },
    { kind: 'text', text: 'File ditulis.' },
  ]);
  t.after(() => gw.close());

  const { config } = require('../config');
  config.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  // health
  const h = await getJson(port, '/health');
  assert.strictEqual(h.status, 200);
  assert.strictEqual(h.body.ok, true);
  assert.strictEqual(h.body.baseUrl, `http://127.0.0.1:${gw.port}/v1`);

  // models proxy: good key and bad key
  const ok = await getJson(port, '/api/models', { 'x-aihubmix-key': 'sk-test-123' });
  assert.strictEqual(ok.status, 200);
  assert.deepStrictEqual(ok.body.models, ['test-mini', 'test-pro']);
  const bad = await getJson(port, '/api/models', { 'x-aihubmix-key': 'sk-salah' });
  assert.strictEqual(bad.status, 502);

  // chat: agent writes a real file through the tool, then answers
  const events = await collectSse(
    port, '/api/chat',
    { 'x-aihubmix-key': 'sk-test-123', 'content-type': 'application/json' },
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

test('HTTP server: models are cached per key (5 min TTL)', async (t) => {
  const gw = await startMockGateway([]);
  t.after(() => gw.close());

  const { config } = require('../config');
  config.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });

  const { start } = require('../server');
  const srv = start();
  t.after(() => new Promise((r) => srv.close(r)));
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;

  // first call hits the gateway
  const first = await getJson(port, '/api/models', { 'x-aihubmix-key': 'sk-test-123' });
  assert.strictEqual(first.status, 200);
  assert.deepStrictEqual(first.body.models, ['test-mini', 'test-pro']);
  assert.notStrictEqual(first.body.cached, true);

  // second call is served from cache: same payload, no extra gateway hit
  const second = await getJson(port, '/api/models', { 'x-aihubmix-key': 'sk-test-123' });
  assert.strictEqual(second.status, 200);
  assert.strictEqual(second.body.cached, true);
  assert.deepStrictEqual(second.body.models, first.body.models);
  assert.strictEqual(gw.modelRequests.length, 1);

  // a different key must not reuse the cache (and its failure is not cached)
  const other = await getJson(port, '/api/models', { 'x-aihubmix-key': 'sk-test-123-lain' });
  assert.strictEqual(other.status, 502);
  const retryOther = await getJson(port, '/api/models', { 'x-aihubmix-key': 'sk-test-123-lain' });
  assert.strictEqual(retryOther.status, 502);
  assert.strictEqual(gw.modelRequests.length, 3);
});
