'use strict';
// pi-bridge.js — T-ML45 Fase 0: jembatan engine Pi Coding Agent (PoC).
//
// Menjalankan bundle RPC Pi (@earendil-works/pi-coding-agent, dipin lewat
// assets/pi/pi-bundle.tar.xz) sebagai CHILD PROCESS node di dalam guest
// proot, dikendalikan lewat JSONL stdin/stdout (docs pi: docs/rpc.md), lalu
// menerjemahkan event Pi menjadi event SSE yang SUDAH dipakai aplikasi —
// ChatScreen/ChatEngine TIDAK berubah sama sekali.
//
// Peta translasi (bentuk event diverifikasi dari run nyata, sesi 31):
//   pi: message_update{text_delta}          → app: {type:'text', delta}
//   pi: message_update{thinking_delta}      → app: {type:'think', delta}
//   pi: message_end{role:assistant}         → app: {type:'message', msg} + {type:'usage'}
//   pi: tool_execution_start                → app: {type:'tool', id, name, args}
//   pi: tool_execution_end                  → app: {type:'toolend'} + {type:'toolresult'}
//   pi: turn_start                          → app: {type:'turn', n}
//   pi: agent_settled                       → app: {type:'done', turns}
//
// Kontinuitas: child Pi HIDUP lintas permintaan /api/chat (satu per kunci
// provider|model|workdir) — history TIDAK dikirim ulang tiap turn (jawaban
// struktural buat temuan audit T-ML28: prompt = 99,7% konsumsi run). Sesi
// Pi dipersist dengan --session-id deterministik + --session-dir, sehingga
// restart server melanjutkan konteks yang sama. Penyimpangan history di
// sisi app (pesan diedit/dihapus) terdeteksi lewat hash prefix kanonik →
// sesi baru (gen++), konteks lama dibuang — jelas di log, bukan diam-diam.
//
// Batas PoC (disengaja, Fase 2 menutupnya): tanpa protokol RENCANA/MEMORI
// (Pi memakai system prompt bawaannya + lampiran bahasa), tanpa 15 RPM
// client-side, tanpa input gambar (wasm photon tidak dibundel).

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { config, providerById } = require('./config');
const log = require('./log');

const NODE_BIN = process.execPath;
const BUNDLE_DIR = process.env.SACHET_PI_BUNDLE_DIR || '/opt/sachet/pi-bundle';
const STATE_DIR = process.env.SACHET_PI_STATE_DIR || '/opt/sachet/pi-bridge';
const RPC_ENTRY = path.join(BUNDLE_DIR, 'bundle', 'rpc-entry.js');

// Lampiran system prompt statis (PoC): aturan bahasa T-ML41 diteruskan ke
// engine baru; sisanya memakai prompt bawaan Pi yang sudah diuji untuk
// tool-toolnya sendiri (read/bash/edit/write).
const APPEND_SYSTEM =
  'Always answer in the same language as the user message. ' +
  'Finish coding tasks end-to-end with the tools available.';

/** Apakah bundle Pi tersedia di guest (dipakai server.js untuk memilih rute
 *  dan /health untuk melapor). */
function piBundleAvailable() {
  try { return fs.statSync(RPC_ENTRY).isFile(); } catch { return false; }
}

// ---------------------------------------------------------------- util

function sha(s) {
  return crypto.createHash('sha256').update(String(s)).digest('hex');
}

/** Hash kanonik satu pesan format app — bebas urutan field JSON, jadi hash
 *  yang dihitung bridge selalu cocok dengan serialisasi ulang dari app. */
function canonicalMsg(m) {
  const calls = Array.isArray(m && m.toolCalls) ? m.toolCalls : [];
  return [
    m && m.role,
    m && m.content,
    (m && m.thinking) || '',
    calls.map((c) => `${c.id}\u001f${c.name}\u001f${JSON.stringify(c.args ?? {})}`).join('\u001e'),
    (m && m.toolCallId) || '',
    (m && m.name) || '',
  ].join('\u0000');
}

function hashPrefix(messages, n) {
  const h = crypto.createHash('sha256');
  for (let i = 0; i < n && i < messages.length; i++) h.update(canonicalMsg(messages[i]) + '\u0001');
  return h.digest('hex');
}

/** Resolve workdir sesi: terima apa adanya bila bisa dibuat/pakai; jika
 *  gagal (bind /sdcard hilang dsb.) jatuh ke default server. */
function resolveWorkdir(workdir) {
  if (typeof workdir !== 'string' || !workdir.trim()) return config.workdir;
  try {
    fs.mkdirSync(workdir, { recursive: true });
    return workdir;
  } catch (e) {
    log.wrn('pi', `workdir sesi tidak terpakai (${e.message}) — fallback ${config.workdir}`);
    return config.workdir;
  }
}

// ---------------------------------------------------------------- child

let active = null; // { key, keyhash, dir, proc, rpc, busy }

class PiRpc {
  constructor(proc) {
    this.proc = proc;
    this.buf = '';
    this.pending = new Map();
    this.events = [];
    this.waiters = [];
    this.nextId = 0;
    proc.stdout.setEncoding('utf8');
    proc.stdout.on('data', (d) => this._feed(d));
    proc.stderr.setEncoding('utf8');
    let errTail = '';
    proc.stderr.on('data', (d) => {
      errTail = (errTail + d).slice(-4000);
      for (const line of String(d).split('\n')) {
        if (line.trim()) log.dbg('pi', 'stderr:', line.slice(0, 200));
      }
    });
    proc.on('exit', (code, sig) => { this.exited = { code, sig, errTail }; this._wake(); });
  }

  _feed(d) {
    this.buf += d;
    // Framing JSONL ketat: split HANYA pada LF (docs pi: jangan readline —
    // U+2028/U+2029 valid di dalam string JSON).
    let idx;
    while ((idx = this.buf.indexOf('\n')) >= 0) {
      const line = this.buf.slice(0, idx).replace(/\r$/, '');
      this.buf = this.buf.slice(idx + 1);
      if (!line.trim()) continue;
      let rec;
      try { rec = JSON.parse(line); } catch { log.dbg('pi', 'stdout non-JSON:', line.slice(0, 160)); continue; }
      if (rec && rec.type === 'response' && rec.id && this.pending.has(rec.id)) {
        const res = this.pending.get(rec.id);
        this.pending.delete(rec.id);
        clearTimeout(res.timer);
        res.resolve(rec);
      } else if (rec && rec.type === 'response' && rec.command === 'parse') {
        log.err('pi', 'RPC parse error dari Pi:', rec.error);
      } else {
        this.events.push(rec);
        this._wake();
      }
    }
  }

  _wake() { for (const w of this.waiters.splice(0)) w(); }

  send(obj, timeoutMs = 20000) {
    const id = `b${++this.nextId}`;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ id, type: 'response', command: obj.type, success: false, error: `timeout ${timeoutMs}ms menunggu respons RPC` });
      }, timeoutMs);
      this.pending.set(id, { resolve, timer });
      try { this.proc.stdin.write(JSON.stringify({ id, ...obj }) + '\n'); }
      catch (e) { clearTimeout(timer); this.pending.delete(id); resolve({ id, type: 'response', command: obj.type, success: false, error: `stdin tertutup: ${e.message}` }); }
    });
  }

  /** Tunggu event yang cocok pred (atau proses exit). */
  waitFor(pred, timeoutMs = 300000) {
    const found = () => this.events.find(pred);
    if (found()) return Promise.resolve(found());
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout menunggu event Pi')), timeoutMs);
      const poll = () => {
        if (this.exited) { clearTimeout(timer); reject(new Error(`proses Pi keluar (kode ${this.exited.code} sinyal ${this.exited.sig})`)); return; }
        const f = found();
        if (f) { clearTimeout(timer); resolve(f); return; }
        this.waiters.push(poll);
      };
      this.waiters.push(poll);
    });
  }

  dispose() { try { this.proc.stdin.end(); } catch { /* already closed */ } }
}

/** models.json untuk satu kunci — provider OpenAI-compatible + SATU model
 *  aktif (yang dipilih app). Ditulis sebelum spawn; baseUrl mengikuti
 *  config.js (env test override sudah berlaku di sana). */
function writeModelsJson(dir, { providerId, provider, model, apiKey }) {
  const maxTokens = provider.maxTokens || 16384;
  const spec = {
    $schema: 'https://pi.dev/schemas/models.schema.json',
    providers: {
      [providerId]: {
        name: provider.label,
        baseUrl: provider.baseUrl,
        api: 'openai-completions',
        apiKey,
        models: [{
          id: model, name: model, reasoning: false, input: ['text'],
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
          contextWindow: 128000, maxTokens,
        }],
      },
    },
  };
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'models.json'), JSON.stringify(spec, null, 2));
}

function readState(dir) {
  try { return JSON.parse(fs.readFileSync(path.join(dir, 'state.json'), 'utf8')); } catch { return { seen: 0, prefix: '', gen: 0 }; }
}

function writeState(dir, st) {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'state.json'), JSON.stringify(st));
}

/** Matikan child lama dengan sopan (stdin EOF → tunggu → SIGKILL). */
async function killChild() {
  const a = active;
  active = null;
  if (!a) return;
  a.rpc.dispose();
  const t0 = Date.now();
  while (a.proc.exitCode === null && Date.now() - t0 < 3000) {
    await new Promise((r) => setTimeout(r, 100));
  }
  if (a.proc.exitCode === null) a.proc.kill('SIGKILL');
  log.dbg('pi', `child ${a.keyhash} dihentikan (${Date.now() - t0}ms)`);
}

/** Pastikan child untuk kunci ini hidup (respawn memuat ulang sesi via
 *  --session-id deterministik). Melempar bila bundle hilang. */
async function ensureChild({ apiKey, providerId, model, workdir }) {
  const key = [providerId, model, workdir, sha(apiKey).slice(0, 12)].join('|');
  const keyhash = sha(key).slice(0, 16);
  const dir = path.join(STATE_DIR, keyhash);
  if (active && active.key === key && active.proc.exitCode === null) return active;

  if (active && active.busy) {
    throw new Error('engine Pi masih sibuk dengan sesi lain — tunggu selesai, lalu kirim ulang');
  }
  await killChild();

  if (!piBundleAvailable()) {
    throw new Error(`bundle Pi tidak ditemukan di ${BUNDLE_DIR} (aset APK gagal diekstrak?)`);
  }
  const provider = providerById(providerId);
  if (!provider) throw new Error(`Provider tidak dikenal: ${providerId}`);

  const st = readState(dir);
  writeModelsJson(path.join(dir, 'agent'), { providerId, provider, model, apiKey });

  const args = [
    RPC_ENTRY, '--mode', 'rpc',
    '--provider', providerId, '--model', model,
    '--session-id', `sachet-${st.gen}`, '--session-dir', path.join(dir, 'sessions'),
    '--append-system-prompt', APPEND_SYSTEM,
    // PoC hermetic: tanpa ekstensi/MCP/skill/context-file — workflow
    // RENCANA/MEMORI (Fase 2) yang nanti membuka keran ini.
    '--no-extensions', '--no-mcp', '--no-skills', '--no-prompt-templates', '--no-themes', '--no-context-files',
  ];
  log.inf('pi', `spawn child ${keyhash} gen=${st.gen} model=${model} workdir=${workdir}`);
  const proc = spawn(NODE_BIN, args, {
    cwd: workdir,
    env: { ...process.env, PI_CODING_AGENT_DIR: path.join(dir, 'agent'), PI_OFFLINE: '1', NO_COLOR: '1' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const rpc = new PiRpc(proc);
  const entry = { key, keyhash, dir, proc, rpc, busy: false };
  active = entry;

  // Jabat tangan: get_state membuktikan RPC hidup dan model terdaftar.
  const hs = await rpc.send({ type: 'get_state' }, 25000);
  if (!hs.success) {
    await killChild();
    throw new Error(`Pi tidak siap: ${hs.error || 'get_state gagal'}`);
  }
  log.dbg('pi', `child ${keyhash} siap (get_state OK)`);
  return entry;
}

// ---------------------------------------------------------------- translasi

/** Blok konten Pi → teks gabungan (type:'text'). */
function textOf(content) {
  return (Array.isArray(content) ? content : [])
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('');
}

/** Pesan assistant Pi → format Msg app (kontrak event 'message'). */
function toAppAssistant(piMsg) {
  const blocks = Array.isArray(piMsg.content) ? piMsg.content : [];
  const text = blocks.filter((b) => b.type === 'text').map((b) => b.text).join('');
  const thinking = blocks.filter((b) => b.type === 'thinking').map((b) => b.thinking || '').join('');
  const toolCalls = blocks.filter((b) => b.type === 'toolCall')
    .map((b) => ({ id: b.id, name: b.name, args: b.arguments ?? {} }));
  return {
    role: 'assistant',
    content: text,
    ...(thinking ? { thinking } : {}),
    ...(toolCalls.length ? { toolCalls } : {}),
  };
}

/** Prompt untuk turn ini dari history app + state bridge.
 *  - turn baru: pesan user baru terakhir
 *  - Lanjutkan (tanpa pesan user baru, ujung history = tool/assistant): instruksi lanjut
 *  - sesi Pi masih kosong + history lama ada: shim konteks ringkas (chat
 *    yang dulu jalan di engine lama tetap punya konteks) */
function promptFor(messages, seen, piFresh) {
  const tail = messages.slice(seen);
  const lastUser = [...tail].reverse().find((m) => m && m.role === 'user');
  if (piFresh && seen === 0 && messages.length > 1) {
    const lines = messages
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .map((m) => `${m.role === 'user' ? 'Pengguna' : 'Asisten'}: ${(m.content || '').slice(0, 400)}`)
      .join('\n');
    const trimmed = lines.length > 8000 ? lines.slice(0, 4000) + '\n…(dipangkas)…\n' + lines.slice(-4000) : lines;
    return (lastUser
      ? `Konteks percakapan sebelumnya (ringkas):\n${trimmed}\n\nPesan terbaru pengguna: ${lastUser.content}`
      : `Konteks percakapan sebelumnya (ringkas):\n${trimmed}\n\nLanjutkan pekerjaan dari titik terakhir.`);
  }
  if (lastUser) return String(lastUser.content || '');
  return '[lanjutan] Lanjutkan pekerjaan dari titik terakhir. Jangan mengulang langkah yang sudah selesai.';
}

// ---------------------------------------------------------------- API utama

/**
 * Satu run chat lewat engine Pi — kontrak sama dengan runAgent() di
 * agent.js: emit event SSE app, kembalikan daftar pesan baru untuk app.
 */
async function runPiChat({ apiKey, providerId, model, messages, signal, emit, workdir }) {
  if (!Array.isArray(messages)) throw new Error('messages wajib array');
  const wd = resolveWorkdir(workdir);

  // Penyimpangan history → buang sesi lama, mulai gen baru.
  let st = null;
  let entry = null;
  const probeKey = [providerId, model, wd, sha(apiKey).slice(0, 12)].join('|');
  const probeHash = sha(probeKey).slice(0, 16);
  const probeDir = path.join(STATE_DIR, probeHash);
  st = readState(probeDir);
  if (st.seen > 0 && (messages.length < st.seen || hashPrefix(messages, st.seen) !== st.prefix)) {
    st = { seen: 0, prefix: '', gen: (st.gen || 0) + 1 };
    writeState(probeDir, st);
    log.wrn('pi', `history app menyimpang dari sesi Pi — sesi baru gen=${st.gen} (konteks lama dibuang)`);
  }
  const piFresh = st.seen === 0;

  entry = await ensureChild({ apiKey, providerId, model, workdir: wd });
  entry.busy = true;
  const { rpc } = entry;

  const added = [];
  let turns = 0;
  const marker = rpc.events.length; // event run ini mulai dari sini

  try {
    const prompt = promptFor(messages, piFresh ? 0 : st.seen, piFresh);
    log.inf('pi', `prompt ${prompt.length}ch (history ${messages.length} msg, pi-seen ${st.seen})`);

    const pr = await rpc.send({ type: 'prompt', message: prompt }, 30000);
    if (!pr.success) throw new Error(`prompt ditolak Pi: ${pr.error || pr.data}`);
    if (pr.data && pr.data.disposition === 'handled') {
      // Tidak ada run yang dimulai (mis. perintah internal) — anggap selesai.
      emit({ type: 'done', turns: 0 });
      return added;
    }

    // Abort user → perintah abort RPC, tunggu settle.
    const onAbort = () => { rpc.send({ type: 'abort' }, 10000).catch(() => {}); };
    signal && signal.addEventListener('abort', onAbort, { once: true });

    /** Translasi SATU event Pi → event app, dikirim begitu tiba (streaming
     *  live — bukan ditumpuk lalu ditembak di akhir run). */
    const translate = (e) => {
      if (e.type === 'turn_start') {
        turns++;
        emit({ type: 'turn', n: turns });
      } else if (e.type === 'message_update' && e.assistantMessageEvent) {
        const u = e.assistantMessageEvent;
        if (u.type === 'text_delta' && u.delta) emit({ type: 'text', delta: u.delta });
        else if (u.type === 'thinking_delta' && u.delta) emit({ type: 'think', delta: u.delta });
      } else if (e.type === 'message_end' && e.message && e.message.role === 'assistant') {
        const m = e.message;
        const msg = toAppAssistant(m);
        emit({ type: 'message', msg });
        added.push(msg);
        const u = m.usage;
        if (u && (u.input || u.output || u.totalTokens)) {
          emit({
            type: 'usage',
            promptTokens: u.input ?? 0,
            completionTokens: u.output ?? 0,
            ...(u.reasoning != null ? { reasoningTokens: u.reasoning } : {}),
            totalTokens: u.totalTokens ?? ((u.input ?? 0) + (u.output ?? 0)),
          });
        }
        if (m.stopReason === 'error') {
          throw new Error(m.errorMessage || 'model mengembalikan error');
        }
      } else if (e.type === 'tool_execution_start') {
        emit({ type: 'tool', id: e.toolCallId, name: e.toolName, args: e.args ?? {} });
      } else if (e.type === 'tool_execution_end') {
        const output = textOf(e.result && e.result.content) || '(kosong)';
        const ok = !e.isError;
        emit({ type: 'toolend', id: e.toolCallId, ok, output: output.slice(0, 4000) });
        const toolMsg = { role: 'tool', content: output, toolCallId: e.toolCallId, name: e.toolName };
        emit({ type: 'toolresult', msg: toolMsg });
        added.push(toolMsg);
      }
    };

    let settledInfo = null;
    try {
      // Pompa event: konsumsi bertahap sampai agent_settled — satu-satunya
      // titik keluar normal (run + retry/compaction bawaan Pi selesai).
      await new Promise((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new Error('timeout menunggu agent_settled')),
          config.requestTimeoutMs * 12
        );
        let consumed = marker;
        const pump = () => {
          if (rpc.exited) {
            clearTimeout(timer);
            reject(new Error(`proses Pi keluar (kode ${rpc.exited.code} sinyal ${rpc.exited.sig})${rpc.exited.errTail ? ' — stderr ekor: ' + rpc.exited.errTail.slice(-300) : ''}`));
            return;
          }
          try {
            while (consumed < rpc.events.length) {
              const e = rpc.events[consumed++];
              if (e.type === 'agent_settled') { settledInfo = e; break; }
              translate(e);
            }
          } catch (err) { clearTimeout(timer); reject(err); return; }
          if (settledInfo) { clearTimeout(timer); resolve(settledInfo); return; }
          rpc.waiters.push(pump);
        };
        rpc.waiters.push(pump);
        pump();
      });
    } finally {
      signal && signal.removeEventListener('abort', onAbort);
      // Buang event yang sudah dikonsumsi — buffer tidak tumbuh tanpa batas.
      rpc.events.splice(0, rpc.events.length);
    }

    emit({ type: 'done', turns });
    if (settledInfo && settledInfo.aborted) log.dbg('pi', 'run dibatalkan user');

    // ----- state sesi diperbarui setelah sukses -----
    // Pesan `added` direkonstruksi app dari event (message/toolresult) —
    // hash prefix kanonik dihitung dari gabungan history+added, sehingga
    // permintaan berikutnya (app mengirim ulang semuanya) cocok tanpa
    // mengirim ulang apa pun ke model: konteks hidup di sesi Pi.
    const seen = messages.length + added.length;
    const combined = messages.concat(added);
    writeState(entry.dir, { seen, prefix: hashPrefix(combined, seen), gen: readState(entry.dir).gen });
    return added;
  } catch (e) {
    // Child mati / error fatal → buang agar turn berikutnya respawn bersih.
    if (rpc.exited || /proses Pi keluar|tidak siap/.test(e.message || '')) {
      log.err('pi', 'child mati saat run:', e.message);
      await killChild();
    }
    throw e;
  } finally {
    if (active) active.busy = false;
  }
}

module.exports = { runPiChat, piBundleAvailable, killChild, _internals: { canonicalMsg, hashPrefix, promptFor, toAppAssistant, writeModelsJson, readState, writeState, PiRpc } };
