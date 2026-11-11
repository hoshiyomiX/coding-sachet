'use strict';
// Token-economy tests (T-ML29, from the T-ML28 usage audit): usage
// accounting, the model-side tool-output window, read_lines, context
// compaction, the removed budget guard (T-ML32), and the adaptive
// stream_options fallback — all against the mock gateway, no network.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway } = require('./mock-gateway');

// Isolated env for the server under test (same pattern as server.test.js —
// each test file runs in its own process, so config requires cleanly).
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-tok-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';

const { config } = require('../config');
const agent = require('../agent');
const { toolByName } = require('../tools');

function useGateway(gw) {
  config.providers.apinex.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.providers.nvidia.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();
  return config;
}

const U = (p, c, extra = {}) => ({
  prompt_tokens: p,
  completion_tokens: c,
  total_tokens: p + c,
  ...extra,
});

test('stream_options.include_usage terkirim default; chunk usage → event usage + akumulasi runTotal', async (t) => {
  const gw = await startMockGateway([
    {
      kind: 'tools',
      calls: [{ id: 'call_u1', name: 'bash', args: { command: 'echo halo' } }],
      usage: U(120, 30, {
        prompt_tokens_details: { cached_tokens: 40 },
        completion_tokens_details: { reasoning_tokens: 12 },
      }),
    },
    { kind: 'text', text: 'Sudah.', usage: U(200, 20) },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'echo halo' }],
    signal: null, emit: (e) => events.push(e),
  });

  // Default body carries the usage request (both rounds).
  assert.deepStrictEqual(gw.requests[0].stream_options, { include_usage: true });
  assert.deepStrictEqual(gw.requests[1].stream_options, { include_usage: true });

  const usage = events.filter((e) => e.type === 'usage');
  assert.strictEqual(usage.length, 2);
  assert.strictEqual(usage[0].promptTokens, 120);
  assert.strictEqual(usage[0].completionTokens, 30);
  assert.strictEqual(usage[0].reasoningTokens, 12);
  assert.strictEqual(usage[0].cachedTokens, 40);
  assert.strictEqual(usage[0].totalTokens, 150);
  assert.strictEqual(usage[0].runTotal, 150);
  assert.strictEqual(usage[1].runTotal, 370); // 150 + 220
});

test('forModel: jendela head+tail untuk gateway, histori app tetap utuh', async (t) => {
  const big = Array.from({ length: 3000 }, (_, i) => `baris-${i}`).join('\n'); // ~28 KB
  const gw = await startMockGateway([
    { kind: 'tools', calls: [{ id: 'call_f1', name: 'bash', args: { command: 'cat besar.txt' } }] },
    { kind: 'text', text: 'Oke.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  fs.writeFileSync(path.join(config.workdir, 'besar.txt'), big);
  const events = [];
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'cat besar.txt' }],
    signal: null, emit: (e) => events.push(e),
  });

  // App-side persisted history keeps the FULL output.
  const toolMsg = added.find((m) => m.role === 'tool');
  assert.ok(toolMsg.content.includes('baris-1500'));
  assert.ok(toolMsg.content.length > 20000);

  // Gateway-side copy on round 2: windowed + marker; head and tail present.
  const wireTool = gw.requests[1].messages.find((m) => m.role === 'tool');
  assert.ok(wireTool.content.length <= config.toolModelBudgetChars + 220, `panjang ${wireTool.content.length}`);
  assert.ok(wireTool.content.includes('dipangkas hemat token'));
  assert.ok(wireTool.content.includes('baris-0'));       // head
  assert.ok(wireTool.content.includes('baris-2999'));     // tail
  assert.ok(!wireTool.content.includes('baris-1500'));    // middle gone

  // The toolend UI event still carries a preview of the real output.
  const toolend = events.find((e) => e.type === 'toolend');
  assert.ok(toolend.output.includes('baris-0'));
});

test('forModel unit: kecil lolos utuh; non-string dikonversi', () => {
  assert.strictEqual(agent.forModel('pendek'), 'pendek');
  assert.strictEqual(agent.forModel(null), '');   // null/undefined → kosong, bukan "null"
  assert.strictEqual(agent.forModel(undefined), '');
  assert.strictEqual(agent.forModel(42), '42');
  const big = 'x'.repeat(config.toolModelBudgetChars + 100);
  const out = agent.forModel(big);
  assert.ok(out.startsWith('x'));
  assert.ok(out.endsWith('x'));
  assert.ok(out.includes('dipangkas'));
});

test('read_lines: rentang baris bernomor + error di luar EOF', async () => {
  const dir = path.join(ROOT, 'root');
  const f = path.join(dir, 'nomor.txt');
  fs.writeFileSync(f, Array.from({ length: 10 }, (_, i) => `isi${i + 1}`).join('\n'));

  const r1 = await toolByName.read_lines.run({ path: f, from: 3, count: 4 });
  assert.strictEqual(r1.ok, true);
  assert.ok(r1.output.includes('baris 3–6 dari 10'));
  assert.ok(r1.output.includes('     3| isi3'));
  assert.ok(r1.output.includes('     6| isi6'));
  assert.ok(!r1.output.includes('isi7'));

  const r2 = await toolByName.read_lines.run({ path: f }); // default from 1
  assert.ok(r2.output.includes('     1| isi1'));

  const r3 = await toolByName.read_lines.run({ path: f, from: 99 });
  assert.strictEqual(r3.ok, false);
  assert.ok(r3.error.includes('melebihi akhir file'));

  const r4 = await toolByName.read_lines.run({ path: path.join(dir, 'nggak-ada.txt') });
  assert.strictEqual(r4.ok, false);
});

test('kompaksi: hasil tool lama dilipat, terbaru keep tetap utuh', async (t) => {
  const gw = await startMockGateway([
    { kind: 'tools', calls: [{ id: 'c1', name: 'bash', args: { command: 'echo satu' } }] },
    { kind: 'tools', calls: [{ id: 'c2', name: 'bash', args: { command: 'echo dua' } }] },
    { kind: 'tools', calls: [{ id: 'c3', name: 'bash', args: { command: 'echo tiga' } }] },
    { kind: 'tools', calls: [{ id: 'c4', name: 'bash', args: { command: 'echo empat' } }] },
    { kind: 'text', text: 'Selesai.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  const oldThreshold = config.contextCompactTokens;
  config.contextCompactTokens = 40; // force folding on this tiny history
  t.after(() => { config.contextCompactTokens = oldThreshold; });

  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'echo berkali-kali' }],
    signal: null, emit: () => {},
  });

  // Round 5 request: the OLDEST tool result is folded, the newest 3 intact.
  const tools5 = gw.requests[4].messages.filter((m) => m.role === 'tool');
  assert.strictEqual(tools5.length, 4);
  assert.ok(tools5[0].content.includes('dipangkas hemat token'), tools5[0].content);
  assert.ok(tools5.some((m) => m.content.includes('empat')));
  assert.ok(tools5.some((m) => m.content.includes('tiga')));
  assert.ok(tools5.some((m) => m.content.includes('dua')));
  assert.ok(!tools5[0].content.includes('satu'), 'hasil terlama tak boleh utuh');
});

test('budget guard DIHAPUS (T-ML32): pemakaian besar tidak lagi menghentikan giliran', async (t) => {
  // Owner order: APInex grants 20 M free tokens/day — the 100 k/turn cap
  // only cut healthy long runs (field evidence: a legitimate turn stopped
  // at ≈113.678 tokens). A run far past the old limit must keep going:
  // no stop message, tools still run, done still emitted, usage still
  // accounted.
  const gw = await startMockGateway([
    { kind: 'tools', calls: [{ id: 'g1', name: 'bash', args: { command: 'echo x' } }], usage: U(60000, 55000) },
    { kind: 'text', text: 'Lanjut sampai sini.', usage: U(61000, 2000) },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  assert.strictEqual(config.sessionBudgetTokens, undefined, 'konstanta guard harus hilang dari config');

  const events = [];
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'echo' }],
    signal: null, emit: (e) => events.push(e),
  });

  // Round 1 usage (115k) is far past the old 100k cap — the run must not
  // stop: round 2 WAS sent…
  assert.strictEqual(gw.requests.length, 2);
  // …the tool ran for real…
  const toolend = events.find((e) => e.type === 'toolend' && e.id === 'g1');
  assert.ok(toolend, 'tool putaran 1 dieksekusi normal');
  assert.strictEqual(toolend.ok, true);
  // …usage accounting keeps flowing (runTotal = 115k + 63k = 178k)…
  const usage = events.filter((e) => e.type === 'usage');
  assert.strictEqual(usage.length, 2);
  assert.strictEqual(usage[1].runTotal, 178000);
  // …and NO budget-stop note was ever persisted…
  assert.ok(!added.some((m) => m.role === 'assistant' && /pengaman budget/.test(m.content || '')));
  // …the run ends the normal way.
  assert.strictEqual(events.at(-1).type, 'done');
  assert.strictEqual(added[added.length - 1].content, 'Lanjut sampai sini.');
});

test('batas maxTurns: pesan penjelasan ter-persist (bukan error transient)', async (t) => {
  const replies = [];
  for (let i = 0; i < 5; i++) {
    replies.push({ kind: 'tools', calls: [{ id: `m${i}`, name: 'bash', args: { command: `echo ${i}` } }] });
  }
  const gw = await startMockGateway(replies);
  t.after(() => gw.close());

  useGateway(gw);
  const oldMax = config.maxTurns;
  config.maxTurns = 3;
  t.after(() => { config.maxTurns = oldMax; });

  const events = [];
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'ulangi' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 3);
  const note = added[added.length - 1];
  assert.strictEqual(note.role, 'assistant');
  assert.ok(note.content.includes('3 putaran'));
  assert.ok(!events.some((e) => e.type === 'error'), 'cap putaran bukan error lagi');
  assert.strictEqual(events.at(-1).type, 'done');
});

test('stream_options adaptif: 400 dijawab ulang sekali tanpa field', async (t) => {
  const gw = await startMockGateway([
    { kind: 'reject_stream_options', text: 'Jalan tanpa usage.' },
    { kind: 'text', text: 'putaran kedua' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'halo' }],
    signal: null, emit: (e) => events.push(e),
  });

  // First attempt carried stream_options → 400; the retry dropped it.
  assert.deepStrictEqual(gw.requests[0].stream_options, { include_usage: true });
  assert.strictEqual(gw.requests[1].stream_options, undefined);
  assert.strictEqual(gw.requests.length, 2);
  assert.ok(events.some((e) => e.type === 'text'));
  // No usage was reported by this gateway → no usage events.
  assert.ok(!events.some((e) => e.type === 'usage'));
});
