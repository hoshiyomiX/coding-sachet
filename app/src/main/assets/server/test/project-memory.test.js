'use strict';
// T-ML40 tests: persistent project memory (MEMORI.md, owner order sesi
// 28 — "persistent memory"). The head of the workdir's MEMORI.md rides
// the system prompt at run start (the next session in the same folder
// starts from written facts instead of re-exploring — field 2026-11-14:
// putaran 2-5 = 17,2 K tok of pure re-discovery), and a long run that
// never touched the file gets ONE wire-only reminder. All gateway tests
// run against the mock gateway, no network.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway } = require('./mock-gateway');

const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-memori-'));
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

function cleanWorkdir() {
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
  fs.rmSync(path.join(config.workdir, config.memoryFilename), { force: true });
}

// ---------- unit: readProjectMemory / memoryStale ----------

test('readProjectMemory unit: hilang → null; ada → teks; panjang → dipangkas', () => {
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  cleanWorkdir();
  assert.strictEqual(agent.readProjectMemory(config.workdir), null);

  fs.writeFileSync(path.join(config.workdir, config.memoryFilename), '# Memori\n- proyek = coding-sachet\n- test: node --test\n');
  const mem = agent.readProjectMemory(config.workdir);
  assert.ok(mem.includes('coding-sachet'), mem);
  assert.ok(mem.startsWith('# Memori'));

  const oldMax = config.memoryInjectMaxChars;
  config.memoryInjectMaxChars = 20;
  try {
    const cut = agent.readProjectMemory(config.workdir);
    assert.ok(cut.length < 120, `kepala terpangkas: ${cut.length}`);
    assert.ok(cut.includes('dipangkas'), cut);
    assert.ok(cut.startsWith('# Memori'), 'kepala file tetap dibawa');
  } finally {
    config.memoryInjectMaxChars = oldMax;
  }
  // Folder tanpa memory → null, bukan error.
  assert.strictEqual(agent.readProjectMemory(path.join(ROOT, 'tmp', 'kosong')), null);
});

test('memoryStale unit: hilang/lama → true; ditulis saat run → false', () => {
  config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
  fs.mkdirSync(config.workdir, { recursive: true });
  // Mundur 2 dtk dari "mulai run" — kebal granularitas stempel waktu FS.
  const runStart = Date.now() - 2000;
  cleanWorkdir();
  assert.strictEqual(agent.memoryStale(config.workdir, runStart), true, 'file hilang = stale');

  fs.writeFileSync(path.join(config.workdir, config.memoryFilename), 'memori lama\n');
  const oldTime = new Date(Date.now() - 3600_000);
  fs.utimesSync(path.join(config.workdir, config.memoryFilename), oldTime, oldTime);
  assert.strictEqual(agent.memoryStale(config.workdir, runStart), true, 'mtime pra-run = stale');

  fs.writeFileSync(path.join(config.workdir, config.memoryFilename), 'memori baru\n');
  assert.strictEqual(agent.memoryStale(config.workdir, runStart), false, 'ditulis saat run = segar');
  cleanWorkdir();
});

// ---------- gateway: injeksi di awal run ----------

test('MEMORI.md diinjeksi ke system prompt putaran pertama', async (t) => {
  fs.writeFileSync(path.join(config.workdir, config.memoryFilename), '# Memori proyek\n- server = node nol-dependensi\n- jebakan: python3 tidak ada\n');
  t.after(() => cleanWorkdir());
  const gw = await startMockGateway([{ kind: 'text', text: 'Siap, aku sudah ingat konteksnya.' }]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanPlanOnly();
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'lanjut kerja kemarin' }],
    signal: null, emit: () => {},
  });

  assert.strictEqual(gw.requests.length, 1);
  const sys = gw.requests[0].messages.find((m) => m.role === 'system');
  assert.ok(sys, 'ada pesan system');
  assert.ok(sys.content.includes('INGATAN PROYEK'), sys.content.slice(0, 120));
  assert.ok(sys.content.includes('python3 tidak ada'), sys.content);
  assert.ok(sys.content.includes('perbarui MEMORI.md'), sys.content);
});

test('tanpa MEMORI.md: system prompt bersih tanpa blok ingatan', async (t) => {
  const gw = await startMockGateway([{ kind: 'text', text: 'Halo.' }]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'halo' }],
    systemPrompt: 'Kamu agent test.',
    signal: null, emit: () => {},
  });

  const sys = gw.requests[0].messages.find((m) => m.role === 'system');
  assert.ok(sys, 'system prompt dasar tetap ada');
  assert.strictEqual(sys.content, 'Kamu agent test.');
  assert.ok(!sys.content.includes('INGATAN PROYEK'));
});

function cleanPlanOnly() {
  fs.rmSync(path.join(config.workdir, 'RENCANA.md'), { force: true });
}

// ---------- gateway: nudge akhir run ----------

test('memory-nudge: run panjang tanpa MEMORI.md → satu pengingat wire-only', async (t) => {
  const replies = [];
  for (let i = 0; i < 5; i++) {
    replies.push({ kind: 'tools', calls: [{ id: `m${i}`, name: 'bash', args: { command: `echo langkah ${i}` } }] });
  }
  replies.push({ kind: 'text', text: 'Selesai.' });              // putaran 6 → nudge
  replies.push({ kind: 'text', text: 'Sudah kubuat MEMORI.md.' }); // putaran 7 → diterima
  const gw = await startMockGateway(replies);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  const events = [];
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'kerja panjang' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 7, '5 tool + jawaban + jawaban pasca-nudge');
  const nudges = events.filter((e) => e.type === 'memorynudge');
  assert.strictEqual(nudges.length, 1, 'tepat satu nudge');
  assert.strictEqual(nudges[0].n, 1);
  // Pengingat naik wire sebagai pesan user, menyebut MEMORI.md + write_file.
  const reminder = gw.requests[6].messages.filter((m) => m.role === 'user' && m.content.includes('[pengingat harness]'));
  assert.strictEqual(reminder.length, 1);
  assert.ok(reminder[0].content.includes('MEMORI.md'), reminder[0].content);
  assert.ok(reminder[0].content.includes('write_file'), reminder[0].content);
  // Wire-only: histori app bersih.
  assert.ok(!added.some((m) => (m.content || '').includes('[pengingat harness]')));
  assert.strictEqual(events.at(-1).type, 'done');
});

test('memory-nudge tidak jalan bila MEMORI.md ditulis saat run', async (t) => {
  const wd = config.workdir;
  const replies = [
    { kind: 'tools', calls: [{ id: 'w0', name: 'write_file', args: { path: path.join(wd, config.memoryFilename), content: '# Memori\n- baru saja ditulis\n' } }] },
  ];
  for (let i = 1; i < 5; i++) {
    replies.push({ kind: 'tools', calls: [{ id: `w${i}`, name: 'bash', args: { command: `echo ${i}` } }] });
  }
  replies.push({ kind: 'text', text: 'Selesai.' }); // putaran 6 — file segar → tanpa nudge
  const gw = await startMockGateway(replies);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'kerja panjang dengan memori' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 6, 'tidak ada putaran nudge ekstra');
  assert.ok(!events.some((e) => e.type === 'memorynudge'));
  assert.strictEqual(events.at(-1).type, 'done');
});

test('memory-nudge tidak jalan pada run pendek', async (t) => {
  const gw = await startMockGateway([
    { kind: 'tools', calls: [{ id: 's1', name: 'bash', args: { command: 'echo satu' } }] },
    { kind: 'tools', calls: [{ id: 's2', name: 'bash', args: { command: 'echo dua' } }] },
    { kind: 'text', text: 'Selesai singkat.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'tugas cepat' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 3);
  assert.ok(!events.some((e) => e.type === 'memorynudge'), 'run 3 putaran < ambang 5');
});

test('config default T-ML40: memori terpaut wajar', () => {
  assert.strictEqual(config.memoryFilename, 'MEMORI.md');
  assert.strictEqual(config.memoryNudgeMax, 1, 'nudge tunggal — tidak pernah mengunci run');
  assert.strictEqual(config.memoryNudgeMinTurns, 5);
  assert.ok(config.memoryInjectMaxChars > 0 && config.memoryInjectMaxChars <= 2000);
});
