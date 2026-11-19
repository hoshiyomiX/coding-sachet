'use strict';
// pi-bridge.test.js — T-ML45 Fase 0: kontrak jembatan engine Pi Coding Agent.
//
// Tiga hal yang DIBUKTIKAN di sini (pake bundle Pi ASLI dari assets/pi):
//  1. Kontrak event: runPiChat memancarkan event SSE bentuk app yang sama
//     dengan agent legacy (turn/text/message/tool/toolend/toolresult/usage/
//     done) dan tool bash Pi benar-benar menulis di workdir sesi.
//  2. Kontinuitas: child dibunuh lalu respawn → sesi Pi dimuat ulang
//     (permintaan gateway berikutnya MEMUAT pesan-pesan lama dari sesi,
//     bukan dari bridge) — restart server tidak kehilangan konteks.
//  3. Penyimpangan: history app diedit → bridge mendeteksi hash prefix
//     berubah → sesi baru gen++ dengan shim konteks, bukan lanjut buta.
//
// Tarball bundle adalah bagian repo (assets/pi/pi-bundle.tar.xz) — hilang =
// repo rusak = tes GAGAL (bukan skip): CI wajib menangkapnya.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { startMockGateway } = require('./mock-gateway');

// --- siapkan bundle ASLI + state terisolasi SEBELUM require pi-bridge ---
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-pi-'));
const TARBALL = path.join(__dirname, '..', '..', 'pi', 'pi-bundle.tar.xz');
let bundleReady = false;
let bundleNote = '';
if (!fs.existsSync(TARBALL)) {
  bundleNote = `tarball aset Pi tidak ditemukan: ${TARBALL}`;
} else {
  const r = spawnSync('tar', ['-xJf', TARBALL, '-C', ROOT], { timeout: 180000 });
  if (r.error) bundleNote = `tar tidak tersedia: ${r.error.message}`;
  else if (r.status !== 0) bundleNote = `ekstraksi gagal (exit ${r.status}): ${String(r.stderr || '').slice(0, 300)}`;
  else if (!fs.existsSync(path.join(ROOT, 'bundle', 'rpc-entry.js'))) bundleNote = 'tarball tidak berisi bundle/rpc-entry.js';
  else bundleReady = true;
}
if (bundleReady) {
  process.env.SACHET_PI_BUNDLE_DIR = ROOT;
  process.env.SACHET_PI_STATE_DIR = path.join(ROOT, 'state');
}
process.env.SACHET_NO_WARMUP = '1';

const { runPiChat, killChild, piBundleAvailable, _internals } = require('../pi-bridge');
const { config } = require('../config');

/** Arahkan provider apinex ke mock gateway (mutasi live config). */
function useGateway(gw) {
  config.providers.apinex.baseUrl = `http://127.0.0.1:${gw.port}/v1`;
}

const USAGE_A = { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 };
const USAGE_B = { prompt_tokens: 150, completion_tokens: 10, total_tokens: 160 };
const USAGE_C = { prompt_tokens: 30, completion_tokens: 5, total_tokens: 35 };

test.after(() => killChild());

test('pi: tool round-trip + kontrak event app', async () => {
  assert.ok(bundleReady, bundleNote || 'bundle Pi tidak siap');
  assert.ok(piBundleAvailable(), 'piBundleAvailable harus true setelah ekstraksi');

  const gw = await startMockGateway([
    { kind: 'tools', text: 'Bikin marker.', calls: [{ id: 'call_1', name: 'bash', args: { command: 'echo poc-pi-ok > marker.txt' } }], usage: USAGE_A },
    { kind: 'text', text: 'Marker sudah dibuat.', usage: USAGE_B },
  ]);
  useGateway(gw);

  const workdir = path.join(ROOT, 'work1');
  const messages = [{ role: 'user', content: 'Buat file marker.txt berisi poc-pi-ok.' }];
  const events = [];
  const added = await runPiChat({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini',
    messages, workdir, emit: (e) => events.push(e),
  });

  // Tool bash Pi benar-benar jalan di workdir sesi.
  assert.strictEqual(fs.readFileSync(path.join(workdir, 'marker.txt'), 'utf8').trim(), 'poc-pi-ok');

  // Kontrak event: jenis + isi yang dipakai ChatEngine.kt.
  const types = events.map((e) => e.type);
  assert.ok(types.includes('turn'), 'event turn hilang');
  assert.ok(types.includes('text'), 'event text (streaming) hilang');
  assert.ok(types.includes('done'), 'event done hilang');

  const msgEv = events.filter((e) => e.type === 'message');
  assert.strictEqual(msgEv.length, 2, 'harus ada 2 pesan assistant (round tool + final)');
  assert.strictEqual(msgEv[0].msg.role, 'assistant');
  assert.ok(Array.isArray(msgEv[0].msg.toolCalls) && msgEv[0].msg.toolCalls.length === 1, 'round 1 harus membawa toolCalls');
  assert.strictEqual(msgEv[0].msg.toolCalls[0].name, 'bash');
  assert.strictEqual(msgEv[0].msg.toolCalls[0].args.command, 'echo poc-pi-ok > marker.txt');
  assert.strictEqual(msgEv[1].msg.content, 'Marker sudah dibuat.');

  const toolEv = events.find((e) => e.type === 'tool');
  assert.ok(toolEv, 'event tool hilang');
  assert.strictEqual(toolEv.name, 'bash');
  assert.strictEqual(toolEv.id, 'call_1');

  const toolendEv = events.find((e) => e.type === 'toolend');
  assert.strictEqual(toolendEv.ok, true, 'toolend.ok harus true');

  const trEv = events.find((e) => e.type === 'toolresult');
  assert.ok(trEv, 'event toolresult hilang');
  assert.strictEqual(trEv.msg.role, 'tool');
  assert.strictEqual(trEv.msg.toolCallId, 'call_1');
  assert.strictEqual(trEv.msg.name, 'bash');

  const usageEv = events.filter((e) => e.type === 'usage');
  assert.ok(usageEv.length >= 2, 'usage per round hilang');
  assert.strictEqual(usageEv[0].totalTokens, 120);
  assert.strictEqual(usageEv[0].promptTokens, 100);
  assert.strictEqual(usageEv[0].completionTokens, 20);

  const doneEv = events.find((e) => e.type === 'done');
  assert.ok(doneEv.turns >= 2, 'done.turns harus >= 2 (round tool + final)');

  // Kontrak return: daftar pesan baru untuk app persist.
  assert.strictEqual(added.length, 3, 'added = assistant + tool + assistant');
  assert.strictEqual(added[0].role, 'assistant');
  assert.strictEqual(added[1].role, 'tool');
  assert.strictEqual(added[2].role, 'assistant');

  await gw.close();
});

test('pi: sesi bertahan melewati respawn child (kontinuitas)', async () => {
  assert.ok(bundleReady, bundleNote || 'bundle Pi tidak siap');

  const gw = await startMockGateway([
    { kind: 'tools', text: 'Bikin marker lagi.', calls: [{ id: 'call_9', name: 'bash', args: { command: 'echo sesi-2 > marker2.txt' } }], usage: USAGE_A },
    { kind: 'text', text: 'Marker kedua selesai.', usage: USAGE_B },
    { kind: 'text', text: 'Sesi masih ingat.', usage: USAGE_C },
  ]);
  useGateway(gw);

  const workdir = path.join(ROOT, 'work2');
  const m1 = [{ role: 'user', content: 'Buat marker2 berisi sesi-2.' }];
  const events1 = [];
  const added1 = await runPiChat({ apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini', messages: m1, workdir, emit: (e) => events1.push(e) });
  assert.strictEqual(fs.readFileSync(path.join(workdir, 'marker2.txt'), 'utf8').trim(), 'sesi-2');

  // "Restart server": child dibunuh keras — state sesi hanya di disk.
  await killChild();
  assert.strictEqual(activeChildCount(), 0, 'child harus benar-benar mati');

  // Turn 2: app mengirim history penuh (m1 + added1 + user baru).
  const m2 = m1.concat(added1, [{ role: 'user', content: 'Masih ingat marker tadi?' }]);
  const events2 = [];
  const added2 = await runPiChat({ apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini', messages: m2, workdir, emit: (e) => events2.push(e) });

  assert.strictEqual(added2[added2.length - 1].content, 'Sesi masih ingat.');

  // BUKTI kontinuitas: permintaan gateway PUTARAN-2 (setelah respawn, indeks
  // 2 = call pertama turn ini) memuat pesan user PERTAMA dari sesi —
  // context dimuat ulang oleh Pi dari sesinya sendiri, bukan dikirim bridge.
  const turn2Req = gw.requests[2];
  assert.ok(turn2Req, 'tidak ada permintaan gateway untuk turn 2');
  const flat = JSON.stringify(turn2Req.messages);
  assert.ok(flat.includes('Buat marker2 berisi sesi-2.'), 'pesan lama dari sesi Pi harus ikut terkirim ke gateway');
  assert.ok(flat.includes('Masih ingat marker tadi?'), 'pesan baru harus terkirim');
  // Bridge TIDAK mengirim ulang history sebagai prompt: prompt RPC hanya
  // pesan user baru (bukti tidak ada blob history di body gateway).
  assert.ok(!flat.includes('Konteks percakapan sebelumnya'), 'turn normal tidak boleh memakai shim konteks');

  await gw.close();
});

test('pi: history app menyimpang → sesi baru + shim konteks', async () => {
  assert.ok(bundleReady, bundleNote || 'bundle Pi tidak siap');

  const gw = await startMockGateway([
    { kind: 'text', text: 'Halo.', usage: USAGE_A },
    { kind: 'text', text: 'Sesi baru dimulai.', usage: USAGE_C },
  ]);
  useGateway(gw);

  const workdir = path.join(ROOT, 'work3');
  const m1 = [{ role: 'user', content: 'pesan asli A' }];
  const added1 = await runPiChat({ apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini', messages: m1, workdir, emit: () => {} });
  assert.strictEqual(added1[0].content, 'Halo.');

  // User mengedit pesan lama di app → prefix tidak cocok lagi.
  const mEdited = [{ role: 'user', content: 'pesan DIEDIT A' }].concat(added1, [{ role: 'user', content: 'pesan baru B' }]);
  const added2 = await runPiChat({ apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini', messages: mEdited, workdir, emit: () => {} });
  assert.strictEqual(added2[added2.length - 1].content, 'Sesi baru dimulai.');

  // Ses baru = shim konteks terlihat di gateway, dan gen naik di state.
  const flat = JSON.stringify(gw.requests[1].messages);
  assert.ok(flat.includes('Konteks percakapan sebelumnya'), 'penyimpangan harus memicu shim konteks sesi baru');
  const keyhash = _internals.readState;
  // gen naik dibaca dari state.json workdir3 (kunci = provider|model|workdir)
  const { readState } = _internals;
  const crypto = require('node:crypto');
  const sha = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');
  const dir = path.join(process.env.SACHET_PI_STATE_DIR, sha(['apinex', 'test-mini', workdir, sha('sk-test-123').slice(0, 12)].join('|')).slice(0, 16));
  const st = readState(dir);
  assert.strictEqual(st.gen, 1, 'gen harus naik 0→1 setelah penyimpangan');
  assert.strictEqual(st.seen, mEdited.length + added2.length, 'seen harus = history baru');

  await gw.close();
});

test('pi: unit — promptFor + hash kanonik', () => {
  const { promptFor, hashPrefix, canonicalMsg } = _internals;

  // Turn normal: pesan user baru terakhir.
  assert.strictEqual(promptFor([{ role: 'user', content: 'halo' }], 0, false), 'halo');
  assert.strictEqual(promptFor([{ role: 'user', content: 'a' }, { role: 'assistant', content: 'b' }, { role: 'user', content: 'c' }], 2, false), 'c');

  // Lanjutkan (tanpa user baru di ekor): instruksi lanjutan.
  const cont = promptFor([{ role: 'user', content: 'a' }, { role: 'assistant', content: 'b', toolCalls: [{ id: 'x', name: 'bash', args: {} }] }], 2, false);
  assert.ok(cont.includes('[lanjutan]'), 'harus instruksi lanjutan');

  // Sesi fresh + history lama: shim konteks.
  const shim = promptFor([{ role: 'user', content: 'a' }, { role: 'assistant', content: 'b' }], 0, true);
  assert.ok(shim.includes('Konteks percakapan sebelumnya'));
  assert.ok(shim.includes('Pesan terbaru pengguna: a'));

  // Hash kanonik: urutan field JSON tidak berpengaruh; isi beda = beda.
  const h1 = hashPrefix([{ role: 'user', content: 'x' }], 1);
  const h2 = hashPrefix([{ content: 'x', role: 'user' }], 1);
  const h3 = hashPrefix([{ role: 'user', content: 'y' }], 1);
  assert.strictEqual(h1, h2, 'urutan field tidak boleh mengubah hash');
  assert.notStrictEqual(h1, h3, 'isi beda harus mengubah hash');
  assert.strictEqual(canonicalMsg({ role: 'tool', content: 'o', toolCallId: 't1', name: 'bash' }).includes('t1'), true);
});

/** Jumlah child hidup (untuk assert kill). */
function activeChildCount() {
  // Tidak diekspor langsung — dipakai lewat efek: piBundleAvailable tidak
  // relevan; jembatan paling sederhana = cek tidak ada node child tersisa
  // dengan rpc-entry di cmdline. Cukup longgar untuk tes ini.
  const { spawnSync } = require('node:child_process');
  const r = spawnSync('pgrep', ['-f', 'rpc-entry.js'], { encoding: 'utf8' });
  return r.status === 0 ? String(r.stdout).trim().split('\n').filter(Boolean).length : 0;
}
