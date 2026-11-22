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
// T-ML57: ulangan otomatis cepat untuk test (produksi: jeda 15s, 2 ulangan).
process.env.SACHET_PI_RETRY_DELAY_MS = '400';
process.env.SACHET_PI_RETRY_MAX = '2';

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
  assert.strictEqual(pgrepChildCount(), 0, 'child harus benar-benar mati');

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

test('pi: error model mid-run → child dibuang, retry respawn bersih (T-ML55)', async () => {
  assert.ok(bundleReady, bundleNote || 'bundle Pi tidak siap');

  // Gateway 1: SELALU 503 (script habis → reply terakhir diulang) — berapa
  // kali pun Pi mencoba ulang, run 1 tetap gagal. Bentuk insiden lapangan
  // 2026-11-21: upstream_busy dari 100milionseek.
  const gw1 = await startMockGateway([
    { kind: 'error', status: 503, message: 'The service is busy right now. Please try again in a moment.', headers: { 'retry-after': '20' } },
  ]);
  useGateway(gw1);

  const workdir = path.join(ROOT, 'work4');
  const m1 = [{ role: 'user', content: 'tolong kerjakan sesuatu' }];

  // Run 1: harus melempar. Opsi A: child TIDAK boleh dibiarkan hidup dalam
  // state 'processing' (zombie) seperti bug lama — dia wajib sudah dibunuh.
  await assert.rejects(() => runPiChat({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini',
    messages: m1, workdir, emit: () => {},
  }));
  assert.strictEqual(pgrepChildCount(), 0, 'zombie child harus sudah dibunuh setelah error mid-run (opsi A)');

  await gw1.close();

  // Run 2 (retry owner): gateway sehat → respawn bersih dan SUKSES — bukan
  // ditolak "Agent is already processing" seperti sebelum fix.
  const gw2 = await startMockGateway([
    { kind: 'text', text: 'Sembuh setelah respawn.', usage: USAGE_C },
  ]);
  useGateway(gw2);
  const added2 = await runPiChat({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini',
    messages: m1, workdir, emit: () => {},
  });
  assert.strictEqual(added2[added2.length - 1].content, 'Sembuh setelah respawn.');

  await gw2.close();
});

test('pi: unit — klasifikasi error retryable (T-ML57)', () => {
  const { isRetryableError } = _internals;

  // Bentuk lapangan (insiden 2026-11-21): Pi mem-prefix status HTTP.
  assert.ok(isRetryableError('502 {"title":"Error 502: Bad gateway","status":502,"zone":"100milionseek.top"}'));
  assert.ok(isRetryableError('503 {"error":{"message":"The service is busy right now."}}'));
  assert.ok(isRetryableError('fetch failed'));
  assert.ok(isRetryableError('socket hang up'));
  assert.ok(isRetryableError('read ECONNRESET'));
  assert.ok(isRetryableError('terminated'));
  assert.ok(isRetryableError('529 {"error":{"type":"overloaded_error"}}'));

  // Konfigurasi/salah kunci — ulang tidak menolong.
  assert.ok(!isRetryableError('401 {"error":{"message":"Invalid token"}}'));
  assert.ok(!isRetryableError('402 Payment required: saldo habis'));
  assert.ok(!isRetryableError('404 model tidak dikenal'));

  // Watchdog internal + child mati: bukan domain auto-retry.
  assert.ok(!isRetryableError('timeout menunggu agent_settled'));
  assert.ok(!isRetryableError('timeout 30000ms menunggu respons RPC'));
  assert.ok(!isRetryableError('proses Pi keluar (kode null sinyal SIGKILL)'));
  assert.ok(!isRetryableError('bundle Pi tidak ditemukan di /opt/sachet/pi-bundle'));
  assert.ok(!isRetryableError(''));
});

test('pi: unit — APPEND_SYSTEM memuat disiplin hemat putaran (T-ML58 #1)', () => {
  // Bukti lapangan 1.0.58 (logcat 11:49): ptok 50.617/giliran, sesi 418.339
  // tok untuk prompt 82ch — engine Pi tidak mewarisi disiplin T-ML40 dan
  // tiap putaran tool mengirim ulang SELURUH konteks ke provider 50 tok/s.
  // Lampiran system prompt kini menegakkan kontrak hemat yang sama.
  const p = _internals.APPEND_SYSTEM;

  // Aturan bahasa T-ML41 tetap diteruskan.
  assert.ok(p.includes('Always answer in the same language as the user message'));

  // Kontrak hemat putaran (T-ML58 #1):
  assert.ok(p.includes('Batch independent commands'), 'perintah independen digabung');
  assert.ok(p.includes('Never re-read a file'), 'tidak membaca ulang file');
  assert.ok(p.includes('view_range'), 'baca per rentang baris');
  assert.ok(/grep/i.test(p), 'grep-first alih-alih dump file');
  assert.ok(p.includes('Do not narrate'), 'tanpa narasi langkah');
  assert.ok(p.includes('no progress'), 'berhenti saat dua putaran mentok');
  assert.ok(p.includes('restate file contents'), 'jawaban tidak menyalin ulang isi file');
});

test('pi: auto-retry — 503 mid-run setelah tool → ulang otomatis → sukses (T-ML57)', async () => {
  assert.ok(bundleReady, bundleNote || 'bundle Pi tidak siap');

  // Replika insiden 08:51 (1.0.57-571): putaran PERTAMA sehat (pesan
  // assistant + tool sudah terkirim ke app), lalu 503 menimpa call
  // berikutnya sampai Pi menyerah. Latch `recovered` diputar tepat di
  // jendela antara child dibunuh dan respawn ulangan.
  let recovered = false;
  const gw = await startMockGateway(
    [
      { kind: 'tools', text: 'Cek dulu.', calls: [{ id: 'call_r1', name: 'bash', args: { command: 'echo retry-marker > retry.txt' } }], usage: USAGE_A },
      { kind: 'text', text: 'Pulih setelah retry.', usage: USAGE_C },
    ],
    { failChatWhile: (i) => i >= 2 && !recovered },
  );
  useGateway(gw);

  // try/finally: assertion gagal pun gateway WAJIB ditutup — server yang
  // tetap listening membuat event loop test runner tidak pernah drain.
  try {
    const workdir = path.join(ROOT, 'work5');
    const events = [];
    // Deteksi jendela retry memakai state modul (_internals.activeChildCount)
    // — deterministik, bebas race /proc. pgrep eksternal terbukti bisa
    // mengembalikan hitungan palsu 0 persis di window spawn yang sibuk.
    const bridgeCount = _internals.activeChildCount;

    // Baseline bersih: tes sebelumnya (path sukses) meninggalkan child hidup.
    await killChild();
    assert.strictEqual(bridgeCount(), 0, 'baseline: tidak boleh ada child sebelum run');

    const done = runPiChat({
      apiKey: 'sk-test-123', providerId: 'apinex', model: 'test-mini',
      messages: [{ role: 'user', content: 'buat retry.txt' }], workdir, emit: (e) => events.push(e),
    });
    done.catch(() => {}); // jangan biarkan rejection nganggur selama polling

    // Attempt-1: child spawn → ... → dibunuh oleh catch. Pulihkan gateway
    // saat child mati (jendela = jeda ulangan 400ms).
    await waitUntil(() => bridgeCount() >= 1, 30000, 'attempt-1 child tidak pernah spawn');
    await waitUntil(() => bridgeCount() === 0, 60000, 'attempt-1 child tidak kunjung dibunuh');
    recovered = true;

    const added = await done;

    // Run SUKSES lewat ulangan — bukan reject.
    assert.strictEqual(added[added.length - 1].content, 'Pulih setelah retry.');
    assert.ok(events.some((e) => e.type === 'message' && e.msg.content === 'Pulih setelah retry.'), 'pesan ulangan harus sampai ke app');

    // Event attempt GAGAL tetap terkirim (streaming live) — bentuk insiden:
    // tool round dulu, baru kegagalan, baru pemulihan. Probe lapangan: saat
    // ulangan, Pi memuat sesi persist lalu menutup toolCall menggantung
    // dengan pesan assistant kosong sintetis (tanpa LLM call) SEBELUM call
    // pulih — jadi jumlah assistant >= 2, bukan persis 2.
    assert.ok(fs.readFileSync(path.join(workdir, 'retry.txt'), 'utf8').trim() === 'retry-marker', 'tool attempt-1 harus benar-benar jalan');
    const assistants = added.filter((m) => m.role === 'assistant');
    assert.ok(assistants.length >= 2, 'minimal a1 (tool round) + a2 (pulih)');
    assert.ok(Array.isArray(assistants[0].toolCalls) && assistants[0].toolCalls.length === 1, 'pesan pertama = a1 round tool attempt-1');
    assert.ok(events.filter((e) => e.type === 'message').length >= 2, 'kedua pesan terkirim ke app');

    // Gateway benar-benar melihat gelombang gagal sebelum pulih.
    assert.ok(gw.chatAttempts >= 3, 'harus ada request sehat + beberapa request 503');

    // State sesi maju dengan SEMUA pesan request ini (konsistensi history app).
    const crypto2 = require('node:crypto');
    const sha = (s) => crypto2.createHash('sha256').update(String(s)).digest('hex');
    const dir = path.join(process.env.SACHET_PI_STATE_DIR, sha(['apinex', 'test-mini', workdir, sha('sk-test-123').slice(0, 12)].join('|')).slice(0, 16));
    const st = _internals.readState(dir);
    assert.strictEqual(st.seen, 1 + added.length, 'seen = user + semua pesan lintas percobaan');
  } finally {
    await gw.close();
  }
});

/** Jumlah child rpc-entry yang terlihat /proc (pembuktian eksternal utk
 *  zombie T-ML55). Kegagalan pgrep TIDAK boleh dibaca "0 child" — retry,
 *  lalu gagal keras: hitungan palsu pernah membuat poll retry menembus
 *  saat child masih hidup (sesi T-ML57). */
function pgrepChildCount() {
  const { spawnSync } = require('node:child_process');
  for (let i = 0; i < 5; i++) {
    const r = spawnSync('pgrep', ['-f', 'rpc-entry.js'], { encoding: 'utf8' });
    if (!r.error && r.status !== null) {
      return r.status === 0 ? String(r.stdout).trim().split('\n').filter(Boolean).length : 0;
    }
  }
  throw new Error('pgrep gagal berulang — hitungan child tidak bisa dipercaya');
}

/** Poll sampai predikat benar (atau timeout — dengan pesan yang jelas). */
async function waitUntil(pred, timeoutMs, label) {
  const t0 = Date.now();
  while (!pred()) {
    if (Date.now() - t0 > timeoutMs) throw new Error(`waitUntil timeout: ${label || 'kondisi tidak pernah terpenuhi'}`);
    await new Promise((r) => setTimeout(r, 15));
  }
}

test('pi: unit — parseDualConfig + models.json dual (T-ML68 #1)', () => {
  const { parseDualConfig, writeModelsJson } = _internals;
  const os = require('node:os');

  // parseDualConfig: bentuk valid, tidak valid, dan no-op.
  assert.deepStrictEqual(parseDualConfig({ pi: { orchestrator: 'a', executor: 'b' } }), { orchestrator: 'a', executor: 'b' });
  assert.deepStrictEqual(parseDualConfig({ pi: { executor: 'b' } }), { orchestrator: null, executor: 'b' });
  assert.deepStrictEqual(parseDualConfig({ pi: { orchestrator: '  a  ' } }), { orchestrator: 'a', executor: null });
  assert.strictEqual(parseDualConfig(null), null, 'tanpa options = null');
  assert.strictEqual(parseDualConfig({}), null, 'tanpa kunci pi = null');
  assert.strictEqual(parseDualConfig({ pi: 'bukan objek' }), null, 'pi non-objek = null');
  assert.strictEqual(parseDualConfig({ pi: [1, 2] }), null, 'pi array = null');
  assert.strictEqual(parseDualConfig({ pi: {} }), null, 'pi kosong = null');
  assert.strictEqual(parseDualConfig({ pi: { orchestrator: 'sama', executor: 'sama' } }), null, 'identik = no-op');
  assert.strictEqual(parseDualConfig({ pi: { orchestrator: 42, executor: true } }), null, 'tipe salah = null');

  // writeModelsJson: executor terdaftar di provider sama; tanpa executor =
  // perilaku lama (satu model).
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-dual-'));
  const provider = { label: 'T', baseUrl: 'http://x/v1', maxTokens: 999 };
  writeModelsJson(dir, { providerId: 'apinex', provider, model: 'orch', apiKey: 'k', executor: 'exec' });
  let spec = JSON.parse(fs.readFileSync(path.join(dir, 'models.json'), 'utf8'));
  assert.deepStrictEqual(spec.providers.apinex.models.map((m) => m.id), ['orch', 'exec'], 'kedua model harus terdaftar');
  assert.strictEqual(spec.providers.apinex.models[1].maxTokens, 999);

  writeModelsJson(dir, { providerId: 'apinex', provider, model: 'solo', apiKey: 'k', executor: 'solo' });
  spec = JSON.parse(fs.readFileSync(path.join(dir, 'models.json'), 'utf8'));
  assert.deepStrictEqual(spec.providers.apinex.models.map((m) => m.id), ['solo'], 'executor identik tidak diduplikasi');

  writeModelsJson(dir, { providerId: 'apinex', provider, model: 'solo', apiKey: 'k' });
  spec = JSON.parse(fs.readFileSync(path.join(dir, 'models.json'), 'utf8'));
  assert.deepStrictEqual(spec.providers.apinex.models.map((m) => m.id), ['solo'], 'tanpa executor = single (perilaku lama)');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('pi: dual-model — executor mengambil alih putaran setelah tool (T-ML68 #1)', async () => {
  assert.ok(bundleReady, bundleNote || 'bundle Pi tidak siap');

  const gw = await startMockGateway([
    { kind: 'tools', text: 'Rencana siap.', calls: [{ id: 'call_1', name: 'bash', args: { command: 'echo dual-ok > dual.txt' } }], usage: USAGE_A },
    { kind: 'text', text: 'Selesai oleh executor.', usage: USAGE_B },
  ]);
  useGateway(gw);
  const workdir = path.join(ROOT, 'work-dual');
  const events = [];
  const added = await runPiChat({
    apiKey: 'sk-test-123', providerId: 'apinex', model: 'orch-mini', executor: 'exec-mini',
    messages: [{ role: 'user', content: 'Buat file dual.txt.' }], workdir,
    emit: (e) => events.push(e),
  });

  // Event handoff executor terkirim ke app (ChatEngine mengabaikan tipe asing).
  const modelEv = events.find((e) => e.type === 'model');
  assert.ok(modelEv, 'event model (handoff executor) hilang');
  assert.strictEqual(modelEv.role, 'executor');
  assert.strictEqual(modelEv.model, 'exec-mini');

  // Permintaan chat kedua ke gateway benar-benar memakai executor.
  const sentModels = gw.requests.map((r) => r && r.model).filter(Boolean);
  assert.ok(sentModels.includes('exec-mini'), `permintaan executor tidak terlihat: ${sentModels.join(',')}`);
  assert.strictEqual(added[added.length - 1].content, 'Selesai oleh executor.');
  assert.strictEqual(fs.readFileSync(path.join(workdir, 'dual.txt'), 'utf8').trim(), 'dual-ok');
  await gw.close();
});
