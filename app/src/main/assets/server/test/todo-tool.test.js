'use strict';
// T-ML40 tests: the `todo` tool — the sanctioned RENCANA.md editor. Field
// evidence 2026-11-14 (putaran 14-18, 56,3 K tok = 37,6% dari run
// 149.739 tok): the model tried sed (gagal — regex + kurung), python3
// (TIDAK ADA di rootfs), which (exit≠0), then recovered via
// read_file+write_file. One tool call now does what took five rounds.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway } = require('./mock-gateway');

const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-todo-tool-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
process.env.SACHET_PORT = '0';
process.env.SACHET_RESOLV_CONF = path.join(ROOT, 'resolv.conf');
process.env.SACHET_NO_WARMUP = '1';

const { config } = require('../config');
const agent = require('../agent');
const { toolByName } = require('../tools');

function useLocalRoots() {
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
}

function useGateway(gw) {
  config.providers.apinex.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.providers.nvidia.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  require('../ratelimit').reset();
  return config;
}

const PLAN = [
  '# Rencana kerja',
  '',
  '- [ ] clone repo',
  '- [ ] baca struktur',
  '- [ ] jalankan test',
].join('\n');

function resetPlan() {
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  fs.rmSync(path.join(config.workdir, config.memoryFilename), { force: true });
  fs.writeFileSync(path.join(config.workdir, 'RENCANA.md'), PLAN);
}

// ---------- tool langsung ----------

test('todo baca: daftar bernomor dengan status', async () => {
  useLocalRoots();
  resetPlan();
  const r = await toolByName.todo.run({ aksi: 'baca' });
  assert.strictEqual(r.ok, true);
  assert.ok(r.output.includes('1. [ ] clone repo'), r.output);
  assert.ok(r.output.includes('3. [ ] jalankan test'), r.output);
});

test('todo centang: beberapa nomor sekali jalan, file berubah', async () => {
  useLocalRoots();
  resetPlan();
  const r = await toolByName.todo.run({ aksi: 'centang', nomor: [1, 2] });
  assert.strictEqual(r.ok, true);
  assert.ok(r.output.includes('dicentang'), r.output);
  const after = fs.readFileSync(path.join(config.workdir, 'RENCANA.md'), 'utf8');
  assert.ok(after.includes('- [x] clone repo'), after);
  assert.ok(after.includes('- [x] baca struktur'), after);
  assert.ok(after.includes('- [ ] jalankan test'), after);
  // nomor tunggal tanpa array juga diterima.
  const r2 = await toolByName.todo.run({ aksi: 'centang', nomor: 3 });
  assert.strictEqual(r2.ok, true);
  assert.ok(fs.readFileSync(path.join(config.workdir, 'RENCANA.md'), 'utf8').includes('- [x] jalankan test'));
});

test('todo coret: wajib alasan, teks dicoret', async () => {
  useLocalRoots();
  resetPlan();
  const tanpaAlasan = await toolByName.todo.run({ aksi: 'coret', nomor: [3] });
  assert.strictEqual(tanpaAlasan.ok, false);
  assert.ok(tanpaAlasan.error.includes('alasan'), tanpaAlasan.error);

  const r = await toolByName.todo.run({ aksi: 'coret', nomor: [3], alasan: 'cukup 2 langkah' });
  assert.strictEqual(r.ok, true);
  const after = fs.readFileSync(path.join(config.workdir, 'RENCANA.md'), 'utf8');
  assert.ok(after.includes('- [x] ~~jalankan test~~ (dibatalkan: cukup 2 langkah)'), after);
  // Langkah tercoret = selesai buat enforcement.
  assert.deepStrictEqual(agent.readTodoUnchecked(config.workdir).unchecked, ['clone repo', 'baca struktur']);
});

test('todo validasi: nomor di luar daftar, aksi asing, file hilang', async () => {
  useLocalRoots();
  resetPlan();
  const oob = await toolByName.todo.run({ aksi: 'centang', nomor: [99] });
  assert.strictEqual(oob.ok, false);
  assert.ok(oob.error.includes('di luar daftar'), oob.error);

  const asing = await toolByName.todo.run({ aksi: 'hapus' });
  assert.strictEqual(asing.ok, false);

  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  const hilang = await toolByName.todo.run({ aksi: 'baca' });
  assert.strictEqual(hilang.ok, false);
  assert.ok(hilang.error.includes('belum ada'), hilang.error);
});

// ---------- agent loop: todo tool dipakai nyata ----------

test('todo tool lewat agent loop: nudge → model mencentang dengan tool todo', async (t) => {
  const wd = config.workdir;
  const gw = await startMockGateway([
    { kind: 'tools', calls: [
      { id: 'p1', name: 'write_file', args: { path: path.join(wd, 'RENCANA.md'), content: '- [ ] satu\n- [ ] dua\n' } },
    ] },
    { kind: 'text', text: 'Sudah selesai semuanya.' },
    { kind: 'tools', calls: [
      { id: 'p2', name: 'todo', args: { aksi: 'centang', nomor: [1, 2] } },
    ] },
    { kind: 'text', text: 'Kini benar-benar selesai.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  fs.rmSync(path.join(wd, 'RENCANA.md'), { force: true });
  fs.rmSync(path.join(wd, config.memoryFilename), { force: true });
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'kerjakan dua langkah' }],
    signal: null, emit: (e) => events.push(e),
  });

  // 4 request: rencana → jawaban#1 (nudge) → todo centang → jawaban#2.
  assert.strictEqual(gw.requests.length, 4);
  const toolend = events.find((e) => e.type === 'toolend' && e.id === 'p2');
  assert.ok(toolend, 'tool todo dieksekusi');
  assert.strictEqual(toolend.ok, true);
  const after = fs.readFileSync(path.join(wd, 'RENCANA.md'), 'utf8');
  assert.ok(!after.includes('- [ ]'), after);
  // Nudge menyebut jalur resminya.
  const nudges = gw.requests[2].messages.filter((m) => m.role === 'user' && m.content.includes('[pengingat harness]'));
  assert.ok(nudges[0].content.includes('tool todo'), nudges[0].content);
});
