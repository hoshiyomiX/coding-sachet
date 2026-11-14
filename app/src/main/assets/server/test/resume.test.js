'use strict';
// T-ML41 tests (owner order sesi 28 "optimisasi lanjutan" #3): continue
// task handling whenever truncated or lost connection — the latest todo
// state lives in RENCANA.md, and a re-issued interrupted run (the app's
// Lanjutkan button) gets a wire-only [lanjutan harness] anchor to the
// NEXT unchecked step; a finish_reason=length answer is bounced back so
// it assembles itself instead of ending mid-sentence. All gateway tests
// run against the mock gateway, no network.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { startMockGateway } = require('./mock-gateway');

const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-resume-'));
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

/** A wire history whose last message is a completed tool round — the exact
 *  persisted shape continueRun() re-issues after a lost connection. */
function interruptedHistory(wd, rencanaContent) {
  return [
    { role: 'user', content: 'kerjakan rencananya' },
    {
      role: 'assistant', content: 'Kutulis rencananya dulu.',
      toolCalls: [{
        id: 'c1', name: 'write_file',
        args: { path: path.join(wd, 'RENCANA.md'), content: rencanaContent },
      }],
    },
    { role: 'tool', toolCallId: 'c1', name: 'write_file', content: 'file ditulis (58 byte)' },
  ];
}

// ---------- unit: resumableHistory ----------

test('resumableHistory unit: bentuk history yang menandakan run terputus', () => {
  assert.strictEqual(agent.resumableHistory([{ role: 'user', content: 'kerja' }, { role: 'tool', toolCallId: 'c1', name: 'bash', content: 'ok' }]), true, 'tool terakhir = terputus');
  assert.strictEqual(agent.resumableHistory([{ role: 'assistant', content: 'cek—', toolCalls: [{ id: 'c1', name: 'bash', args: {} }] }]), true, 'assistant menunggu hasil tool = terputus');
  assert.strictEqual(agent.resumableHistory([{ role: 'assistant', content: 'Sudah selesai.' }]), false, 'jawaban final = bukan resume');
  assert.strictEqual(agent.resumableHistory([{ role: 'user', content: 'hai' }]), false, 'pesan user = run baru');
  assert.strictEqual(agent.resumableHistory([]), false, 'history kosong');
  assert.strictEqual(agent.resumableHistory(null), false, 'null aman');
});

// ---------- gateway: direktif lanjutan saat resume ----------

test('resume: RENCANA.md punya langkah terbuka → direktif menunjuk langkah berikutnya', async (t) => {
  const wd = config.workdir;
  const gw = await startMockGateway([{ kind: 'text', text: 'Melanjutkan langkah dua.' }]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  fs.writeFileSync(path.join(wd, 'RENCANA.md'), '- [x] siapkan folder\n- [ ] tulis modul\n- [ ] uji modul\n');
  // Isolasi direktif resume dari todo-nudge (langkah masih terbuka → nudge
  // akan memantulkan jawaban final; itu perilaku T-ML39, bukan yang diuji).
  const oldNudgeMax = config.todoNudgeMax;
  config.todoNudgeMax = 0;
  const events = [];
  let added;
  try {
    added = await agent.runAgent({
      apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
      messages: interruptedHistory(wd, '- [x] siapkan folder\n- [ ] tulis modul\n- [ ] uji modul\n'),
      signal: null, emit: (e) => events.push(e),
    });
  } finally {
    config.todoNudgeMax = oldNudgeMax;
  }

  assert.strictEqual(gw.requests.length, 1);
  const msgs = gw.requests[0].messages;
  const last = msgs[msgs.length - 1];
  assert.strictEqual(last.role, 'user', 'direktif menutup permintaan');
  assert.ok(last.content.includes('[lanjutan harness]'), last.content);
  assert.ok(last.content.includes('tulis modul'), 'langkah berikutnya disebut');
  assert.ok(last.content.includes('uji modul'), 'semua langkah terbuka disebut');
  assert.ok(!last.content.includes('siapkan folder'), 'langkah selesai tidak disebut sebagai tugas');
  assert.ok(last.content.includes('jangan ulangi'), 'anti-restart eksplisit');
  // Wire-only: histori app tetap hanya pesan user/model.
  assert.ok(!added.some((m) => (m.content || '').includes('[lanjutan harness]')));
  assert.strictEqual(events.at(-1).type, 'done');
});

test('resume: semua langkah sudah dicentang → minta jawaban akhir', async (t) => {
  const wd = config.workdir;
  const gw = await startMockGateway([{ kind: 'text', text: 'Ini jawaban akhirmu.' }]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  fs.writeFileSync(path.join(wd, 'RENCANA.md'), '- [x] satu\n- [x] dua\n');
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: interruptedHistory(wd, '- [x] satu\n- [x] dua\n'),
    signal: null, emit: () => {},
  });

  const msgs = gw.requests[0].messages;
  const last = msgs[msgs.length - 1];
  assert.ok(last.content.includes('[lanjutan harness]'), last.content);
  assert.ok(last.content.includes('jawaban akhir'), last.content);
  assert.ok(!added.some((m) => (m.content || '').includes('[lanjutan harness]')));
});

test('resume tanpa RENCANA.md: tidak ada direktif, run jalan normal', async (t) => {
  const gw = await startMockGateway([{ kind: 'text', text: 'Lanjut dari histori.' }]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  const wd = config.workdir;
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: interruptedHistory(wd, '- [ ] rencana yang tak pernah jadi'),
    signal: null, emit: () => {},
  });

  const msgs = gw.requests[0].messages;
  assert.ok(!msgs.some((m) => (m.content || '').includes('[lanjutan harness]')), 'tanpa rencana = tanpa direktif');
});

test('run baru (pesan user terakhir): tidak ada direktif walau RENCANA.md ada', async (t) => {
  const wd = config.workdir;
  const gw = await startMockGateway([{ kind: 'text', text: 'Siap.' }]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  fs.writeFileSync(path.join(wd, 'RENCANA.md'), '- [ ] langkah terbuka\n');
  const oldNudgeMax = config.todoNudgeMax;
  config.todoNudgeMax = 0; // isolasi: fokus uji absennya direktif resume
  try {
    await agent.runAgent({
      apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
      messages: [{ role: 'user', content: 'mulai tugas baru' }],
      signal: null, emit: () => {},
    });
  } finally {
    config.todoNudgeMax = oldNudgeMax;
  }

  const msgs = gw.requests[0].messages;
  assert.ok(!msgs.some((m) => (m.content || '').includes('[lanjutan harness]')), 'run segar = tanpa direktif');
});

test('resume penuh: direktif → model mencentang sisa langkah → selesai', async (t) => {
  const wd = config.workdir;
  const gw = await startMockGateway([
    { kind: 'tools', calls: [{ id: 'r1', name: 'todo', args: { aksi: 'centang', nomor: [2, 3] } }] },
    { kind: 'text', text: 'Semua langkah tuntas.' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  fs.writeFileSync(path.join(wd, 'RENCANA.md'), '- [x] siapkan folder\n- [ ] tulis modul\n- [ ] uji modul\n');
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: interruptedHistory(wd, '- [x] siapkan folder\n- [ ] tulis modul\n- [ ] uji modul\n'),
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 2, 'direktif → centang → jawaban final');
  const after = fs.readFileSync(path.join(wd, 'RENCANA.md'), 'utf8');
  assert.ok(!after.includes('- [ ]'), after);
  assert.ok(events.some((e) => e.type === 'toolend' && e.id === 'r1' && e.ok));
});

// ---------- gateway: finish_reason = length (jawaban terpotong) ----------

test('finish=length: balasan dipantulkan, lanjutan menyambung dari titik putus', async (t) => {
  const gw = await startMockGateway([
    { kind: 'text', text: 'Bagian pertama jawaban panjang.', finish: 'length' },
    { kind: 'text', text: ' Bagian penutup.', finish: 'stop' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  const events = [];
  const added = await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'jelaskan panjang' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 2, 'potongan pertama dipantulkan');
  const msgs = gw.requests[1].messages;
  const partial = msgs.find((m) => m.role === 'assistant' && m.content.includes('Bagian pertama'));
  assert.ok(partial, 'potongan tercatat sebagai assistant');
  const directive = msgs[msgs.length - 1];
  assert.strictEqual(directive.role, 'user');
  assert.ok(directive.content.includes('[lanjutan harness]'), directive.content);
  assert.ok(directive.content.includes('titik terputus'), directive.content);
  // Kedua potongan jadi histori app (pesan terpisah, urutan benar).
  const answers = added.filter((m) => m.role === 'assistant');
  assert.strictEqual(answers.length, 2);
  assert.ok(answers[0].content.includes('Bagian pertama'));
  assert.ok(answers[1].content.includes('penutup'));
  assert.ok(!added.some((m) => (m.content || '').includes('[lanjutan harness]')), 'wire-only');
  assert.strictEqual(events.at(-1).type, 'done');
});

test('finish=length: pantulan dibatasi lengthContinueMax', async (t) => {
  const gw = await startMockGateway([
    { kind: 'text', text: 'Potongan A.', finish: 'length' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  const oldMax = config.lengthContinueMax;
  config.lengthContinueMax = 2;
  try {
    await agent.runAgent({
      apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
      messages: [{ role: 'user', content: 'jelaskan' }],
      signal: null, emit: () => {},
    });
    // max 2 pantulan → total 3 permintaan; jawaban ke-3 diterima apa adanya.
    assert.strictEqual(gw.requests.length, 3, `permintaan = ${gw.requests.length}`);
    assert.ok(gw.requests[2].messages.some((m) => m.role === 'user' && m.content.includes('[lanjutan harness]')));
  } finally {
    config.lengthContinueMax = oldMax;
  }
});

test('finish=length tanpa konten (reasoning habis): tidak dipantulkan', async (t) => {
  const gw = await startMockGateway([
    { kind: 'text', text: '', finish: 'length' },
  ]);
  t.after(() => gw.close());

  useGateway(gw);
  cleanWorkdir();
  const events = [];
  await agent.runAgent({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-pro',
    messages: [{ role: 'user', content: 'jelaskan' }],
    signal: null, emit: (e) => events.push(e),
  });

  assert.strictEqual(gw.requests.length, 1, 'tanpa konten = diterima sebagai final');
  assert.strictEqual(events.at(-1).type, 'done');
});
