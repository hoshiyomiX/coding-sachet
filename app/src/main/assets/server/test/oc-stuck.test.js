'use strict';
// T-ML71 — perbaikan permanen stuck engine OpenCode (oc-bridge.js):
//   1. translate menandai lastAcc (tool berjalan tanpa hasil = diam sah)
//   2. silence budget terpisah: TOOL_SILENCE_MS > SILENCE_MS
//   3. isBusyRejection mengenali penolakan "sesi sibuk"
//   4. prompt busy → self-heal interrupt + retry prompt TANPA reset server
//   5. fase tanpa terminal event → interrupt zombie + error terlihat
//      (bukan done palsu yang diam-diam)
const { test, describe } = require('node:test');
const assert = require('node:assert');
const { _internals } = require('../oc-bridge');
const {
  translate, newAcc, isTerminalEvent, isRetryableError,
  isBusyRejection, SILENCE_MS, TOOL_SILENCE_MS,
} = _internals;

describe('T-ML71 silence budget', () => {
  test('anggaran diam tool lebih longgar daripada diam generasi', () => {
    assert.ok(TOOL_SILENCE_MS > SILENCE_MS, `${TOOL_SILENCE_MS} harus > ${SILENCE_MS}`);
    assert.ok(TOOL_SILENCE_MS >= 300_000, 'harus menutup bash/apt panjang di proot');
  });
});

describe('T-ML71 translate: pelacakan tool terbuka', () => {
  test('tool.called tanpa hasil = openTools (diam sah)', () => {
    const st = { msgs: new Map(), added: [], emit: () => {} };
    translate({ type: 'session.tool.called', data: { assistantMessageID: 'm1', id: 't1', name: 'bash', input: { command: 'apt-get update' } } }, st);
    assert.strictEqual(st.lastAcc.toolCalls.length, 1);
    assert.ok(st.lastAcc.toolCalls.some((tc) => !st.lastAcc.results.has(tc.id)), 'tool t1 masih terbuka');
  });

  test('tool.succeeded menutup tool (diam panjang berikutnya = macet)', () => {
    const st = { msgs: new Map(), added: [], emit: () => {} };
    translate({ type: 'session.tool.called', data: { assistantMessageID: 'm1', id: 't1', name: 'bash', input: {} } }, st);
    translate({ type: 'session.tool.succeeded', data: { assistantMessageID: 'm1', id: 't1', output: 'ok' } }, st);
    assert.ok(!st.lastAcc.toolCalls.some((tc) => !st.lastAcc.results.has(tc.id)), 'semua tool tertutup');
  });

  test('tool.success (nama event v2.4.2) menutup tool juga', () => {
    const st = { msgs: new Map(), added: [], emit: () => {} };
    translate({ type: 'session.tool.called', data: { assistantMessageID: 'm1', id: 't1', name: 'bash', input: {} } }, st);
    translate({ type: 'session.tool.success', data: { assistantMessageID: 'm1', id: 't1', output: 'ok' } }, st);
    assert.ok(!st.lastAcc.toolCalls.some((tc) => !st.lastAcc.results.has(tc.id)), 'semua tool tertutup');
  });

  test('execution.interrupted (v2.4.2) = event terminal', () => {
    assert.ok(isTerminalEvent({ type: 'session.execution.interrupted', data: { sessionID: 's1' } }));
  });

  test('step.ended memancarkan message + toolresult dan membuang hasil tanpa pemanggil', () => {
    const ev = [];
    const st = { msgs: new Map(), added: [], emit: (e) => ev.push(e) };
    translate({ type: 'session.tool.called', data: { assistantMessageID: 'm1', id: 't1', name: 'read_file', input: { path: 'a.js' } } }, st);
    translate({ type: 'session.tool.succeeded', data: { assistantMessageID: 'm1', id: 't1', output: 'isi' } }, st);
    translate({ type: 'session.step.ended', data: { assistantMessageID: 'm1', text: 'selesai', finish: 'stop' } }, st);
    const types = ev.map((e) => e.type);
    assert.deepStrictEqual(types, ['tool', 'toolend', 'message', 'toolresult']);
    const msg = ev.find((e) => e.type === 'message').msg;
    assert.strictEqual(msg.role, 'assistant');
    assert.strictEqual(msg.content, 'selesai');
    assert.strictEqual(msg.toolCalls[0].id, 't1');
    assert.strictEqual(st.added.length, 2, 'message + toolresult masuk added');
  });
});

describe('T-ML71 sesi sibuk (self-heal, tanpa reset server)', () => {
  test('isBusyRejection mengenali 409/400 busy', () => {
    assert.ok(isBusyRejection(409, 'Session is busy'));
    assert.ok(isBusyRejection(400, 'session already running a prompt'));
    assert.ok(isBusyRejection(409, 'konflik: masih sibuk'));
    assert.ok(!isBusyRejection(404, 'not found'));
    assert.ok(!isBusyRejection(500, 'server error'));
    assert.ok(!isBusyRejection(200, 'busy?'));
  });

  test('error sesi sibuk TIDAK dianggap retryable (self-heal di sendPrompt)', () => {
    assert.ok(!isRetryableError('prompt ditolak: sesi tetap sibuk setelah interrupt'), 'busy yang tersisa = fail cepat, bukan respawn 2×15s');
  });
});

describe('T-ML71 fase macet tanpa terminal', () => {
  test('stuck-bukti: error macet TIDAK retryable-respawn (fail cepat ke app)', () => {
    assert.ok(!isRetryableError('run OpenCode macet tanpa keluaran (tidak ada terminal event) — sesi direset, coba lagi'), 'macet harus disurface ke app, bukan dua respawn 15s yang membosankan');
    assert.ok(!isRetryableError('run OpenCode berhenti di tengah (tidak ada event terminal) — tekan Lanjutkan untuk meneruskan'));
  });

  test('terminal OpenCode tetap terdeteksi (regresi kontrak lama)', () => {
    assert.ok(isTerminalEvent({ type: 'session.execution.succeeded' }));
    assert.ok(isTerminalEvent({ type: 'session.error', data: { error: 'x' } }));
    assert.ok(!isTerminalEvent({ type: 'session.text.delta' }));
    assert.ok(!isTerminalEvent({ type: 'session.step.started' }));
  });

  test('translate aman menerima event di luar sesi (tidak melempar)', () => {
    const st = { msgs: new Map(), added: [], emit: () => {} };
    assert.doesNotThrow(() => {
      translate({ type: 'server.connected' }, st);
      translate({ type: 'session.usage.updated', data: { tokens: { input: 1, output: 2 } } }, st);
      translate({ type: 'session.execution.succeeded' }, st);
    });
  });
});
