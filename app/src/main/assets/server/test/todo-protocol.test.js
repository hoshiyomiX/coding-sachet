'use strict';
// T-ML39 tests (owner order sesi 27 — "hasil coding aimless, tanpa
// workflow/protocol/harness"): the TODO-file protocol (RENCANA.md
// enforcement in the agent loop), the write_file syntax gate, and the
// estimator calibration (audit §6.4 R1/R2). All gateway tests run against
// the mock gateway, no network.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway } = require('./mock-gateway');

// Isolated env for the server under test (same pattern as
// token-economy.test.js — each test file runs in its own process).
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-todo-'));
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

function useLocalRoots() {
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
}

const PLAN_2_OPEN = [
  '# Rencana kerja',
  '',
  '- [ ] buat index.html',
  '- [ ] tulis style.css',
].join('\n');

const PLAN_2_DONE = [
  '# Rencana kerja',
  '',
  '- [x] buat index.html',
  '- [x] tulis style.css',
].join('\n');

// ---------- R1: estimator calibration ----------

test('estimateTokens unit: divisor 2 — kalibrasi T-ML39 R1', () => {
  // 2000 chars → 1000 estimated tokens (was 500 under the old /4, which
  // let the 16K threshold fire at ~29.6K REAL tokens; AUDIT-TOKEN §6.2#1).
  assert.strictEqual(agent.estimateTokens([{ role: 'user', content: 'x'.repeat(2000) }]), 1000);
  // tool_calls name+arguments are counted too: (4 + 100) / 2 = 52.
  assert.strictEqual(agent.estimateTokens([{
    role: 'assistant', content: '',
    tool_calls: [{ function: { name: 'bash', arguments: 'y'.repeat(100) } }],
  }]), 52);
  // Empty history is still zero.
  assert.strictEqual(agent.estimateTokens([]), 0);
});

test('config default T-ML39: contextCompactKeep 6 + todoNudgeMax 2', () => {
  assert.strictEqual(config.contextCompactKeep, 6, 'R2b: keep default naik 3 → 6');
  assert.strictEqual(config.todoNudgeMax, 2, 'nudge terbatas — model keras kepala tak mengunci run');
  assert.ok(config.syntaxGateMaxBytes > 0);
});

// ---------- R2a / protocol text ----------

test('readTodoUnchecked unit: hanya "- [ ]" yang menunggak', () => {
  useLocalRoots();
  const dir = config.workdir;
  fs.writeFileSync(path.join(dir, 'RENCANA.md'), [
    '# Rencana',
    '- [ ] langkah satu',
    '* [ ] langkah dua (asterisk)',
    '- [x] langkah selesai',
    '- [x] ~~langkah dicoret~~ (dilewati: tidak perlu)',
    'baris teks biasa bukan checklist',
    '- [ ]',
  ].join('\n'));

  const r = agent.readTodoUnchecked(dir);
  assert.strictEqual(r.exists, true);
  assert.deepStrictEqual(r.unchecked, ['langkah satu', 'langkah dua (asterisk)', '(tanpa teks)']);

  // File hilang → exists:false, bukan error (enforcement tidak menghukum).
  const missing = agent.readTodoUnchecked(path.join(ROOT, 'tmp', 'kosong'));
  assert.strictEqual(missing.exists, false);
  assert.deepStrictEqual(missing.unchecked, []);
});

// ---------- syntax harness ----------

test('write_file gerbang sintaks: js/sh/json divalidasi, tipe lain dilewati', async () => {
  useLocalRoots();
  const dir = config.workdir;

  const goodJs = await toolByName.write_file.run({
    path: path.join(dir, 'bagus.js'), content: 'function f(a) { return a + 1; }\n',
  });
  assert.strictEqual(goodJs.ok, true);
  assert.ok(goodJs.output.includes('sintaks OK'), goodJs.output);
  assert.ok(goodJs.output.includes('node --check'), goodJs.output);

  const badJs = await toolByName.write_file.run({
    path: path.join(dir, 'rusak.js'), content: 'function f({\n',
  });
  assert.strictEqual(badJs.ok, false, 'file JS rusak → tool GAGAL, bukan diterima diam-diam');
  assert.ok(badJs.output.includes('gerbang sintaks'), badJs.output);
  assert.ok(badJs.output.includes('Perbaiki file ini'), badJs.output);
  assert.ok(badJs.output.length > 0);
  // File tetap tertulis di disk — harness jujur soal itu.
  assert.ok(fs.existsSync(path.join(dir, 'rusak.js')));

  const goodJson = await toolByName.write_file.run({
    path: path.join(dir, 'config.json'), content: '{"a": 1}',
  });
  assert.strictEqual(goodJson.ok, true);
  assert.ok(goodJson.output.includes('JSON valid'), goodJson.output);

  const badJson = await toolByName.write_file.run({
    path: path.join(dir, 'rusak.json'), content: '{"a": ',
  });
  assert.strictEqual(badJson.ok, false);
  assert.ok(badJson.output.includes('JSON tidak valid'), badJson.output);

  const badSh = await toolByName.write_file.run({
    path: path.join(dir, 'rusak.sh'), content: 'if [ x\n',
  });
  assert.strictEqual(badSh.ok, false);
  assert.ok(badSh.output.includes('bash -n GAGAL'), badSh.output);

  const goodSh = await toolByName.write_file.run({
    path: path.join(dir, 'bagus.sh'), content: '#!/bin/bash\necho ok\n',
  });
  assert.strictEqual(goodSh.ok, true);
  assert.ok(goodSh.output.includes('bash -n OK'), goodSh.output);

  const md = await toolByName.write_file.run({
    path: path.join(dir, 'catatan.md'), content: '# bukan kode\n',
  });
  assert.strictEqual(md.ok, true);
  assert.ok(md.output.includes('gerbang sintaks dilewati'), md.output);
});

// ---------- TODO enforcement (mock gateway) ----------

test('todo-nudge: jawaban akhir ditolak selama RENCANA.md belum selesai', async (t) => {
  const gw = await startMockGateway([
    { kind: 'tools', calls: [
      { id: 'p1', name: 'write_file', args: { path: path.join(config.workdir, 'RENCANA.md'), content: PLAN_2_OPEN } },
      { id: 'p2', name: 'bash', args: { command: 'echo mulai' } },
    ] },
    { kind: 'text', text: 'Selesai semua.' },
    { kind: 'tools', calls: [
      { id: 'p3', name: 'write_file', args: { path: path.join(config.workdir, 'RENCANA.md'), content: PLAN_2_DONE } },
    ] },
    { kind: 'text', text: 'Sekarang semua langkah benar-benar selesai.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  // Fixture workdir is SHARED by every test in this file (same process)
  // — a RENCANA.md left by an earlier test would leak its unchecked
  // steps into this run.
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  const events = [];
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'buat halaman' }],
    signal: null, emit: (e) => events.push(e),
  });

  // 4 request: plan+mulai → finish#1 (nudge) → check-off → finish#2 (accepted)
  assert.strictEqual(gw.requests.length, 4);

  // Request ke-3 membawa pengingat harness sebagai pesan user.
  const nudges = gw.requests[2].messages.filter((m) => m.role === 'user' && m.content.includes('[pengingat harness]'));
  assert.strictEqual(nudges.length, 1);
  assert.ok(nudges[0].content.includes('2 langkah belum dicentang'), nudges[0].content);
  assert.ok(nudges[0].content.includes('buat index.html'), nudges[0].content);

  // Event todonudge ter-emit (UI mengabaikan tipe tak dikenal — aman).
  const todoEvents = events.filter((e) => e.type === 'todonudge');
  assert.strictEqual(todoEvents.length, 1);
  assert.strictEqual(todoEvents[0].remaining, 2);
  assert.strictEqual(todoEvents[0].n, 1);

  // Pengingat TIDAK masuk histori app (wire-only).
  assert.ok(!added.some((m) => (m.content || '').includes('[pengingat harness]')));

  // Run berakhir normal.
  assert.strictEqual(events.at(-1).type, 'done');
  assert.strictEqual(added[added.length - 1].content, 'Sekarang semua langkah benar-benar selesai.');
  // File plan di disk kini tercentang semua.
  assert.ok(!fs.readFileSync(path.join(config.workdir, 'RENCANA.md'), 'utf8').includes('- [ ]'));
});

test('todo-nudge terbatas todoNudgeMax: model keras kepala tetap dijawab', async (t) => {
  const gw = await startMockGateway([
    { kind: 'tools', calls: [
      { id: 'q1', name: 'write_file', args: { path: path.join(config.workdir, 'RENCANA.md'), content: PLAN_2_OPEN } },
    ] },
    { kind: 'text', text: 'Selesai.' },
    { kind: 'text', text: 'Sudah selesai kok.' },
    { kind: 'text', text: 'Sudah cukup, ini jawaban akhirnya.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'kerjakan' }],
    signal: null, emit: (e) => events.push(e),
  });

  // 4 request: plan → finish#1 (nudge1) → finish#2 (nudge2) → finish#3 (budget habis, DITERIMA)
  assert.strictEqual(gw.requests.length, 4);
  const todoEvents = events.filter((e) => e.type === 'todonudge');
  assert.strictEqual(todoEvents.length, 2, 'nudge maksimal todoNudgeMax kali');
  assert.strictEqual(events.at(-1).type, 'done', 'run tidak terkunci selamanya');
});

test('tanpa RENCANA.md: jawaban akhir diterima langsung', async (t) => {
  const gw = await startMockGateway([
    { kind: 'text', text: 'Jawaban singkat saja.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'halo' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 1);
  assert.ok(!events.some((e) => e.type === 'todonudge'));
  assert.strictEqual(events.at(-1).type, 'done');
});
