'use strict';
// T-ML40 tests (owner order sesi 28 — "hemat token, fokus konteks",
// analisa log 149.739 tok): the baseline elision of old tool results
// (audit §6.7 / §6.4 R3) — every tool result older than the newest
// contextCompactKeep is shrunk to a head summary on EVERY round (the
// 16 K threshold alone never fired on the 19-putaran field run), and
// the paired assistant tool_call arguments are compacted once their
// whole round is elided. All gateway tests run against the mock
// gateway, no network.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway } = require('./mock-gateway');

const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-elide-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';

const { config } = require('../config');
const agent = require('../agent');

function useGateway(gw) {
  config.providers.apinex.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.providers.nvidia.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();
  return config;
}

// The field-session shape: a big output (>400 ch) that used to ride every
// later round verbatim, and a long command (>140 ch) whose JSON args used
// to drift +150 tok/putaran forever.
const BIG = 'A'.repeat(600);
const BIG_CMD = `node -e "console.log('A'.repeat(600))"`;
const LONG_CMD = `node -e "console.log('A'.repeat(600))" # ${'x'.repeat(200)}`;

// ---------- unit: elideOldTools ----------

function buildHistory(rounds) {
  const out = [{ role: 'system', content: 'sistem' }, { role: 'user', content: 'kerja' }];
  for (let i = 0; i < rounds; i++) {
    out.push({
      role: 'assistant', content: '',
      tool_calls: [{ id: `c${i}`, type: 'function', function: { name: 'bash', arguments: JSON.stringify({ command: LONG_CMD }) } }],
    });
    out.push({ role: 'tool', tool_call_id: `c${i}`, name: 'bash', content: `${BIG}` });
  }
  out.push({ role: 'assistant', content: 'selesai' });
  return out;
}

test('elideOldTools unit: beyond keep → ringkasan kepala; keep terbaru utuh', () => {
  const openai = buildHistory(8);
  const elided = new Set();
  agent.elideOldTools(openai, elided, 6);
  const tools = openai.filter((m) => m.role === 'tool');
  assert.strictEqual(elided.size, 2, '8 hasil − keep 6 = 2 dielisi');
  // Elided: marker + 160-char head, bukan utuh.
  assert.ok(tools[0].content.includes('dipangkas hemat token'), tools[0].content);
  assert.ok(tools[0].content.includes('asli 600ch'), tools[0].content);
  assert.ok(tools[0].content.includes('A'.repeat(160)), 'kepala 160 ch ikut');
  assert.ok(!tools[0].content.includes('A'.repeat(200)), 'badan tidak utuh');
  // Terbaru 6 utuh.
  for (let i = 2; i < 8; i++) assert.strictEqual(tools[i].content, BIG, `hasil ${i} harus utuh`);
  // Idempoten: putaran berikutnya tidak memangkas ganda.
  const before = tools[0].content;
  agent.elideOldTools(openai, elided, 6);
  assert.strictEqual(tools[0].content, before);
});

test('elideOldTools unit: hasil kecil TIDAK dielisi (marker lebih mahal dari isinya)', () => {
  const openai = [
    { role: 'user', content: 'u' },
    { role: 'assistant', content: '', tool_calls: [{ id: 'k1', type: 'function', function: { name: 'bash', arguments: '{"command":"echo kecil"}' } }] },
    { role: 'tool', tool_call_id: 'k1', name: 'bash', content: 'exit=0\nkecil\n' },
  ];
  const elided = new Set();
  agent.elideOldTools(openai, elided, 0); // keep 0 → semua kandidat
  assert.strictEqual(elided.size, 0, '13 ch terlalu kecil untuk dielisi');
  assert.strictEqual(openai[2].content, 'exit=0\nkecil\n');
});

// ---------- unit: compactOldAssistantArgs ----------

test('compactOldAssistantArgs unit: args round terelisi → {"ringkas":…} valid JSON', () => {
  const openai = buildHistory(8);
  const elided = new Set();
  agent.elideOldTools(openai, elided, 6);
  const done = new Set();
  agent.compactOldAssistantArgs(openai, elided, done);
  const assistants = openai.filter((m) => m.role === 'assistant' && m.tool_calls);
  // Round 1-2 (semua hasilnya terelisi) dipadatkan.
  const a1 = assistants[0].tool_calls[0].function.arguments;
  assert.ok(a1.length < LONG_CMD.length, `args dipadatkan: ${a1.length}`);
  const parsed = JSON.parse(a1); // HARUS tetap JSON valid (gateway ketat)
  assert.strictEqual(typeof parsed.ringkas, 'string');
  assert.ok(parsed.ringkas.length <= 101);
  // Round 3-8 (hasilnya masih utuh) args tetap asli.
  assert.strictEqual(assistants[7].tool_calls[0].function.arguments, JSON.stringify({ command: LONG_CMD }));
  // Idempoten.
  agent.compactOldAssistantArgs(openai, elided, done);
  assert.strictEqual(assistants[0].tool_calls[0].function.arguments, a1);
});

// ---------- gateway: elisi di jalur nyata ----------

test('elisi gateway: putaran akhir membawa 2 ringkasan + 6 hasil utuh; histori app tetap utuh', async (t) => {
  const replies = [];
  for (let i = 0; i < 8; i++) replies.push({ kind: 'tools', calls: [{ id: `e${i}`, name: 'bash', args: { command: BIG_CMD } }] });
  replies.push({ kind: 'text', text: 'Selesai eksplorasi.' });   // putaran 9 → memory-nudge (run ≥5, MEMORI.md kosong)
  replies.push({ kind: 'text', text: 'Sudah kutulis MEMORI.md.' }); // putaran 10 → diterima
  const gw = await startMockGateway(replies);
  t.after(() => gw.close());

  useGateway(gw);
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  fs.rmSync(path.join(config.workdir, config.memoryFilename), { force: true });
  const events = [];
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'eksplorasi panjang' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 10, '8 tool + jawaban + nudge-memori + jawaban akhir');
  assert.strictEqual(events.filter((e) => e.type === 'memorynudge').length, 1);

  // Request terakhir: 2 terlama dielisi, 6 terbaru utuh.
  const tools9 = gw.requests[9].messages.filter((m) => m.role === 'tool');
  assert.strictEqual(tools9.length, 8);
  assert.ok(tools9[0].content.includes('dipangkas hemat token'), tools9[0].content);
  assert.ok(tools9[1].content.includes('dipangkas hemat token'), tools9[1].content);
  for (let i = 2; i < 8; i++) assert.strictEqual(tools9[i].content, `exit=0\n${BIG}\n`, `hasil ${i} utuh`);

  // Histori app (`added`) TIDAK ikut dielisi — UI tetap lengkap.
  const addedTools = added.filter((m) => m.role === 'tool');
  assert.strictEqual(addedTools.length, 8);
  for (const m of addedTools) assert.ok(!m.content.includes('dipangkas hemat token'), 'histori app utuh');
  assert.strictEqual(events.at(-1).type, 'done');
});

test('elisi gateway: histori sesi lama yang dikirim ulang ikut dilindungi di putaran 1', async (t) => {
  // Resent history (continueRun / pesan lanjutan): 8 round lama utuh di
  // histori app → request PERTAMA sudah harus membawa elisi (T-ML37
  // residu 313 pesan tidak lagi terbayar penuh tiap putaran).
  const resent = [{ role: 'user', content: 'kerja lama' }];
  for (let i = 0; i < 8; i++) {
    resent.push({ role: 'assistant', content: '', toolCalls: [{ id: `r${i}`, name: 'bash', args: { command: BIG_CMD } }] });
    resent.push({ role: 'tool', toolCallId: `r${i}`, name: 'bash', content: `${BIG}` });
  }
  const gw = await startMockGateway([{ kind: 'text', text: 'Lanjutan singkat.' }]);
  t.after(() => gw.close());

  useGateway(gw);
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  fs.rmSync(path.join(config.workdir, config.memoryFilename), { force: true });
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: resent,
    signal: null, emit: () => {},
  });

  assert.strictEqual(gw.requests.length, 1);
  const tools1 = gw.requests[0].messages.filter((m) => m.role === 'tool');
  assert.strictEqual(tools1.length, 8);
  assert.ok(tools1[0].content.includes('dipangkas hemat token'), 'hasil terlama langsung dielisi');
  assert.strictEqual(tools1[7].content, `${BIG}`, '6 terbaru utuh');
});

test('config default T-ML40: ambang elisi konservatif', () => {
  assert.strictEqual(config.contextElideHeadChars, 160);
  assert.strictEqual(config.contextElideMinChars, 400, 'hasil kecil tidak perlu dielisi');
  assert.strictEqual(config.contextCompactKeep, 6, 'elisi memakai jendela keep yang sama');
});
