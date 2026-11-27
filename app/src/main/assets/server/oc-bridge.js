'use strict';
// oc-bridge.js — engine KETIGA: jembatan OpenCode (v2.2.0, di-vendor + patch
// evil "Hapus Guardrails" via scripts/vendor-opencode.sh).
//
// Menjalankan `opencode serve` sebagai CHILD PROCESS di dalam guest proot,
// dikendalikan lewat HTTP (Basic auth, base path /api), lalu menerjemahkan
// event SSE OpenCode menjadi event SSE yang SUDAH dipakai aplikasi —
// ChatScreen/ChatEngine TIDAK berubah sama sekali (kontrak sama dengan
// pi-bridge.js / agent.js).
//
// Kontrak API v2.2.0 (DIVERIFIKASI LIVE di guest proot, lihat AUDIT-OPENCODE.md):
//   auth      : HTTP Basic base64("opencode:" + OPENCODE_SERVER_PASSWORD)
//   boot      : spawn `opencode serve --hostname 127.0.0.1 --port <p>`;
//               health = GET /api/config → 200
//   sesi      : POST /api/session {title, model:{providerID,id}} → {data:{id}}
//   prompt    : POST /api/session/:id/prompt {text}
//   model     : POST /api/session/:id/model {model:{providerID,id}} → 204 (switch)
//   interrupt : POST /api/session/:id/interrupt
//   event     : GET /api/event  (SSE global; data:{id,type,created,data,...})
//
// Peta translasi event → SSE app (bentuk diverifikasi dari run nyata):
//   session.reasoning.delta {delta}          → {type:'think', delta}
//   session.text.delta {delta}               → {type:'text', delta}
//   session.tool.input.started {id,name}     → (ingat nama tool)
//   session.tool.called {id,input}           → {type:'tool', id, name, args}
//   session.tool.success {id,content,meta}   → {type:'toolend'} + {type:'toolresult'}
//   session.step.ended {assistantMessageID,finish,tokens}
//                                            → {type:'message', msg} + toolresult + usage
//   POST /model sukses (dual)                → {type:'model', model, role:'executor'}
//   session.execution.succeeded              → {type:'done'}
//   session.error                            → {type:'error'} + {type:'done'}
//
// Kontinuitas: SATU `opencode serve` HIDUP lintas permintaan /api/chat per
// kunci provider|model|executor|workdir|keyhash (opencode melayani banyak
// sesi dalam satu serve). Riwayat TIDAK dikirim ulang tiap turn — hanya
// prompt user baru. State sesi (sessionID + seen + prefix + gen) dipersist;
// penyimpangan history app (pesan diedit/dihapus) terdeteksi via hash prefix
// kanonik → sesi OpenCode baru (gen++), konteks lama dibuang (jelas di log).
//
// Dual-model (padanan T-ML68): orchestrator = model sesi; setelah putaran
// tool pertama selesai (step.ended finish='tool-calls'), bridge POST /model →
// executor mengambil alih putaran berikutnya. Batas v1: executor satu provider
// dengan orchestrator (satu API key per run) — sama seperti Pi.

const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { config, providerById } = require('./config');
const log = require('./log');

const OC_BIN = process.env.SACHET_OC_BIN || '/opt/sachet/opencode/bin/opencode';
const STATE_DIR = process.env.SACHET_OC_STATE_DIR || '/opt/sachet/opencode/bridge';
// Provider id di sisi OpenCode untuk gateway app (OpenAI-compatible). Satu
// serve = satu provider app, jadi id tetap "sachet" aman.
const OC_PROVIDER_ID = 'sachet';

function envInt(name, dflt) {
  const v = Number(process.env[name]);
  return Number.isFinite(v) && v >= 0 ? Math.floor(v) : dflt;
}
const RETRY_DELAY_MS = envInt('SACHET_OC_RETRY_DELAY_MS', 15000);
const RETRY_MAX = envInt('SACHET_OC_RETRY_MAX', 2);
// Diam lebih dari SILENCE_MS tanpa event apa pun → anggap run selesai (OpenCode
// v2.2.0 tidak selalu mengirim session.execution.succeeded pada run sukses).
const SILENCE_MS = envInt('SACHET_OC_SILENCE_MS', 60000);
// T-ML71: saat tool masih dieksekusi (bash/apt panjang), OpenCode TIDAK
// mengirim event apa pun — diam 60s generation TIDAK boleh membunuh run di
// tengah tool. Anggaran diam terpisah untuk fase tool sebelum menyatakan
// macet; harus > bash timeout terpanjang yang wajar di proot (legacy 120s).
const TOOL_SILENCE_MS = envInt('SACHET_OC_TOOL_SILENCE_MS', 480000);

// Prompt sistem hemat-token yang sama dengan Pi (T-ML58 #1) — OpenCode
// memakai prompt bawaannya; kita hanya menambah aturan bahasa + disiplin.
const APPEND_SYSTEM =
  'Always answer in the same language as the user message. ' +
  'Finish coding tasks end-to-end with the tools available.\n' +
  'Work economically — every additional round is slow on this device and re-sends the whole conversation as input tokens:\n' +
  '- Batch independent commands into ONE bash call (&& or ;) or several tool calls in a single reply.\n' +
  '- Never re-read a file you already read this session; read only the line ranges you need (view_range).\n' +
  '- Prefer targeted searches (grep, glob) over dumping whole files or directories.\n' +
  '- Do not narrate or announce steps before doing them — act first, then summarize at the end.\n' +
  '- Keep conversational answers short. Write code carefully but concisely; do not restate file contents in the reply.\n' +
  '- If two consecutive rounds make no progress, stop exploring and report the blocker.';

// Prompt lanjutan untuk fase executor setelah handoff deterministik. OpenCode
// sudah menyimpan riwayat sesi (rencana + hasil tool putaran pertama
// orchestrator) di sisi server, jadi cukup instruksi singkat agar executor
// melanjutkan dari titik terakhir tanpa mengulang.
const OC_CONTINUE_PROMPT =
  'Lanjutkan pekerjaan dari titik terakhir. Rencana dan hasil tool putaran ' +
  'pertama sudah ada di riwayat sesi — jangan ulangi langkah yang sudah selesai. ' +
  'Selesaikan tugas sampai tuntas dan verifikasi dengan menjalankan kode.';

/** System prompt ringkas yang *mengganti* prompt bawaan OpenCode (~4,8K tok
 *  baseline) agar biaya per-task turun (Path B, AUDIT-OPENCODE.md §10).
 *  Dipakai sebagai `systemPrompt` di opencode.json. Bahasa Inggris (OpenCode
 *  lebih stabil dengan instruksi Inggris), inti protokol coding saja. */
const OC_SYSTEM_PROMPT =
  'You are a coding agent in Ubuntu 24.04 proot on the user\'s phone. ' +
  'Work folder: session project folder, or /sdcard/coding-sachet. ' +
  'Tools: bash, read_file, read_lines, write_file, list_dir, todo. ' +
  'Always answer in the user\'s language. Finish coding tasks end-to-end. ' +
  'Work economically: batch independent commands into ONE bash call; never re-read files; ' +
  'targeted grep/glob over dumping; no narration, no restating files; short answers. ' +
  'Multi-step work: write RENCANA.md (checklist), do steps one at a time, mark with todo tool, ' +
  'verify by running code. Write MEMORI.md (one fact per line with [tag]) at end of multi-step work. ' +
  'Do not claim done while RENCANA.md has open steps. Stop and report blocker after two rounds without progress.';

/** Apakah biner opencode tersedia di guest (dipakai server.js memilih rute +
 *  /health). */
function ocBundleAvailable() {
  try { return fs.statSync(OC_BIN).isFile(); } catch { return false; }
}

// ---------------------------------------------------------------- util

function sha(s) {
  return crypto.createHash('sha256').update(String(s)).digest('hex');
}

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

function resolveWorkdir(workdir) {
  if (typeof workdir !== 'string' || !workdir.trim()) return config.workdir;
  try { fs.mkdirSync(workdir, { recursive: true }); return workdir; }
  catch (e) {
    log.wrn('oc', `workdir sesi tidak terpakai (${e.message}) — fallback ${config.workdir}`);
    return config.workdir;
  }
}

/** Parse konfigurasi dual-model dari options JSON app, kunci "oc".
 *  Bentuk: {"oc":{"orchestrator":"…","executor":"…"}}. */
function parseDualConfig(options) {
  const oc = options && typeof options === 'object' && !Array.isArray(options) ? options.oc : null;
  if (!oc || typeof oc !== 'object' || Array.isArray(oc)) return null;
  const clean = (v) => (typeof v === 'string' ? v.trim().slice(0, 120) : '');
  const orchestrator = clean(oc.orchestrator);
  const executor = clean(oc.executor);
  if (!orchestrator && !executor) return null;
  if (orchestrator && executor && orchestrator === executor) return null;
  return { orchestrator: orchestrator || null, executor: executor || null };
}

function readState(dir) {
  try { return JSON.parse(fs.readFileSync(path.join(dir, 'state.json'), 'utf8')); }
  catch { return { sid: '', seen: 0, prefix: '', gen: 0 }; }
}
function writeState(dir, st) {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'state.json'), JSON.stringify(st));
}

// ---------------------------------------------------------------- config

/** Tulis opencode.json untuk satu kunci: guardrails removed (permission allow)
 *  + provider gateway app (OpenAI-compatible) dengan model primer + executor
 *  terdaftar. XDG_CONFIG_HOME=<dir>, file di <dir>/opencode/opencode.json. */
function writeOpencodeConfig(dir, { provider, model, apiKey, executor }) {
  const models = { [model]: { name: model } };
  if (executor && executor !== model) models[executor] = { name: executor };
  const spec = {
    $schema: 'https://opencode.ai/config.json',
    // Path B: system prompt ringkas mengganti built-in OpenCode (~4,8K tok
    // baseline) → biaya per-task turun drastis. APPEND_SYSTEM masih dikirim
    // per-turn di prompt (non-fresh sesi) sebagai pelengkap bahasa+disiplin.
    systemPrompt: OC_SYSTEM_PROMPT,
    permission: {
      bash: 'allow', edit: 'allow', write: 'allow', read: 'allow',
      list: 'allow', glob: 'allow', grep: 'allow', task: 'allow',
      webfetch: 'allow', todowrite: 'allow', todoread: 'allow',
    },
    provider: {
      [OC_PROVIDER_ID]: {
        npm: '@ai-sdk/openai-compatible',
        name: provider.label || 'app',
        options: { baseURL: provider.baseUrl, apiKey },
        models,
      },
    },
  };
  const cfgDir = path.join(dir, 'opencode');
  fs.mkdirSync(cfgDir, { recursive: true });
  fs.writeFileSync(path.join(cfgDir, 'opencode.json'), JSON.stringify(spec, null, 2));
}
function providerId(p) { return p && p.id ? p.id : 'app'; }

// ---------------------------------------------------------------- serve child

let active = null; // { key, keyhash, dir, proc, port, password, exiting }

/** Cari port bebas (bind :0 lalu tutup — race kecil, dapat diterima). */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
  });
}

/** Satu request HTTP ke serve opencode (Basic auth). Mengembalikan
 *  {status, body} teks; tidak melempar untuk status non-2xx. */
function ocRequest(port, password, method, urlPath, body, timeoutMs = 30000) {
  return new Promise((resolve) => {
    const data = body != null ? JSON.stringify(body) : null;
    const headers = {
      authorization: 'Basic ' + Buffer.from('opencode:' + password).toString('base64'),
    };
    if (data != null) {
      headers['content-type'] = 'application/json';
      headers['content-length'] = Buffer.byteLength(data);
    }
    const req = http.request({ host: '127.0.0.1', port, path: urlPath, method, headers }, (res) => {
      let s = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { s += c; if (s.length > 8_000_000) req.destroy(); });
      res.on('end', () => resolve({ status: res.statusCode, body: s }));
    });
    req.on('error', (e) => resolve({ status: 0, body: '', error: e.message }));
    req.setTimeout(timeoutMs, () => { req.destroy(new Error(`timeout ${timeoutMs}ms`)); });
    if (data != null) req.write(data);
    req.end();
  });
}

/** Koneksi SSE ke /api/event; event diurai → {type,data}. Predikat filter
 *  opsional (mis. hanya sessionID tertentu). Return {close, events, waiters}. */
function openEventStream(port, password, filter) {
  const events = [];
  const waiters = [];
  let buf = '';
  let closed = false;
  const req = http.request({ host: '127.0.0.1', port, path: '/api/event', method: 'GET', headers: {
    authorization: 'Basic ' + Buffer.from('opencode:' + password).toString('base64'),
  } }, (res) => {
    res.setEncoding('utf8');
    const wake = () => { closed = true; for (const w of waiters.splice(0)) w(); };
    res.on('data', (chunk) => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data:')) continue;
          let rec;
          try { rec = JSON.parse(line.slice(5).trim()); } catch { continue; }
          if (!rec || typeof rec.type !== 'string') continue;
          if (filter && !filter(rec)) continue;
          events.push(rec);
          log.dbg('oc', `event ${rec.type} sid=${rec.data && rec.data.sessionID}`);
          for (const w of waiters.splice(0)) w();
        }
      }
    });
    res.on('end', wake);
    res.on('close', wake);
    res.on('error', wake);
  });
  req.on('error', () => { closed = true; for (const w of waiters.splice(0)) w(); });
  req.end();
  return {
    events,
    waiters,
    isClosed: () => closed || req.destroyed,
    close: () => { closed = true; try { req.destroy(); } catch { /* ignore */ } },
  };
}

function sessionKey(providerId, model, executor, workdir, keyHash) {
  const base = [providerId, model, workdir, keyHash].join('|');
  return executor && executor !== model ? `${base}|dual:${executor}` : base;
}

/** Matikan serve lama dengan sopan. */
async function killServe() {
  const a = active;
  active = null;
  if (!a) return;
  const t0 = Date.now();
  while (a.proc.exitCode === null && Date.now() - t0 < 3000) {
    await new Promise((r) => setTimeout(r, 100));
  }
  if (a.proc.exitCode === null) a.proc.kill('SIGKILL');
  log.dbg('oc', `serve ${a.keyhash} dihentikan (${Date.now() - t0}ms)`);
}

/** Pastikan serve untuk kunci ini hidup & sehat. Melempar bila biner hilang. */
async function ensureServe({ apiKey, providerId, model, executor, workdir }) {
  const key = sessionKey(providerId, model, executor, workdir, sha(apiKey).slice(0, 12));
  const keyhash = sha(key).slice(0, 16);
  const dir = path.join(STATE_DIR, keyhash);
  if (active && active.key === key && active.proc.exitCode === null) return active;

  if (!ocBundleAvailable()) {
    throw new Error(`biner opencode tidak ditemukan di ${OC_BIN} (aset APK gagal diekstrak?)`);
  }
  const provider = providerById(providerId);
  if (!provider) throw new Error(`Provider tidak dikenal: ${providerId}`);

  await killServe();
  writeOpencodeConfig(dir, { provider, model, apiKey, executor });

  const port = await freePort();
  const password = crypto.randomBytes(24).toString('base64url');
  const args = [
    'serve', '--hostname', '127.0.0.1', '--port', String(port), '--print-logs',
  ];
  log.inf('oc', `spawn serve ${keyhash} port=${port} model=${model}${executor && executor !== model ? ` executor=${executor}` : ''} workdir=${workdir}`);
  const proc = spawn(OC_BIN, args, {
    cwd: workdir,
    env: {
      ...process.env,
      OPENCODE_SERVER_PASSWORD: password,
      XDG_CONFIG_HOME: dir,
      OPENCODE_DISABLE_AUTOUPDATE: '1',
      OPENCODE_DISABLE_MODELS_FETCH: '1',
      NO_COLOR: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let errTail = '';
  proc.stderr.setEncoding('utf8');
  proc.stderr.on('data', (d) => { errTail = (errTail + d).slice(-4000); });
  proc.stdout.setEncoding('utf8');
  proc.stdout.on('data', (d) => {
    for (const line of String(d).split('\n')) if (line.trim()) log.dbg('oc', 'serve:', line.slice(0, 200));
  });
  const entry = { key, keyhash, dir, proc, port, password, errTail: () => errTail };
  active = entry;

  // Health: GET /api/config → 200 (Basic). Maks ~30s (cold start Bun).
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    if (proc.exitCode !== null) {
      await killServe();
      throw new Error(`opencode serve keluar saat boot (kode ${proc.exitCode})${errTail ? ' — ' + errTail.slice(-300) : ''}`);
    }
    const r = await ocRequest(port, password, 'GET', '/api/config', null, 4000);
    if (r.status === 200) { log.dbg('oc', `serve ${keyhash} siap`); return entry; }
    await new Promise((res) => setTimeout(res, 400));
  }
  await killServe();
  throw new Error('opencode serve tidak menjawab /api/config dalam 30s');
}

// ---------------------------------------------------------------- translasi

/** Ubah satu event OpenCode (rec) menjadi 0..N event app, dikirim live.
 *  state: { msgs:Map messageID→akumulator, emitted:Set, dualExecutor,
 *           executorHanded, handoffPending, added, emit }
 *
 *  OpenCode v2.2.0 (diverifikasi live di guest proot) emits event SSE dengan
 *  tipe:
 *    session.reasoning.started/delta/ended  — rantai reasoning
 *    session.text.started/delta/ended        — teks jawaban
 *    session.tool.input.started              — input tool mulai {id, name}
 *    session.tool.called                     — model meminta tool {id, input.args}
 *    session.tool.succeeded / session.tool.failed {error} — hasil eksekusi tool
 *    session.step.ended                      — akhir langkah {finish, tokens}
 *    session.usage.updated                   — token usage
 *    session.execution.succeeded / failed    — terminal run
 *    session.error                           — terminal error
 *
 *  Kontrak output sama persis dengan pi-bridge / legacy: think, text, tool,
 *  toolend, toolresult, message, usage, model, done, error. ChatEngine /
 *  UI TIDAK berubah. Varian OpenCode lain yang masih emits message.part.*
 *  diangani sebagai fallback. */
function translate(rec, st) {
  const d = rec.data || {};
  // Akumulator pesan aktif — dipakai pompa fase utk mengenali "tool masih
  // berjalan" (diam panjang saat eksekusi tool adalah normal, bukan macet).
  const accOf = (msgId) => {
    const a = st.msgs.get(msgId) || newAcc();
    st.msgs.set(msgId, a);
    st.lastAcc = a;
    return a;
  };
  switch (rec.type) {
    case 'session.reasoning.delta': {
      const acc = accOf(d.assistantMessageID);
      if (d.delta) {
        st.emit({ type: 'think', delta: d.delta });
        acc.thinking += d.delta;
      }
      break;
    }
    case 'session.text.delta': {
      const acc = accOf(d.assistantMessageID);
      if (d.delta) {
        st.emit({ type: 'text', delta: d.delta });
        acc.text += d.delta;
      }
      break;
    }
    case 'session.tool.input.started': {
      const acc = accOf(d.assistantMessageID);
      acc.toolIndex[d.id] = { name: d.name, args: {} };
      break;
    }
    case 'session.tool.called': {
      const acc = accOf(d.assistantMessageID);
      const info = acc.toolIndex[d.id];
      const name = (info && info.name) || 'tool';
      const args = d.input ?? {};
      if (info) info.args = args;
      acc.toolCalls.push({ id: d.id, name, args });
      st.emit({ type: 'tool', id: d.id, name, args });
      break;
    }
    case 'session.tool.succeeded': {
      const acc = accOf(d.assistantMessageID);
      const output = d.output != null ? String(d.output)
        : (d.content != null ? String(d.content) : '(kosong)');
      st.emit({ type: 'toolend', id: d.id, ok: true, output: output.slice(0, 4000) });
      acc.results.set(d.id, { content: output, ok: true });
      // Dual-model: handoff deterministik setelah tool putaran pertama selesai.
      if (st.dualExecutor && !st.executorHanded && !st.handoffPending) {
        st.executorHanded = true;
        st.handoffPending = true;
        log.inf('oc', `dual-model: tool ${d.id} selesai — siap serahkan ke executor ${st.dualExecutor}`);
      }
      break;
    }
    case 'session.tool.failed': {
      const acc = accOf(d.assistantMessageID);
      const err = d.error || {};
      const message = typeof err === 'string' ? err
        : (err && err.message) ? err.message : String(err);
      st.emit({ type: 'toolend', id: d.id, ok: false, output: message.slice(0, 4000) });
      acc.results.set(d.id, { content: message, ok: false });
      break;
    }
    case 'session.step.ended': {
      const acc = accOf(d.assistantMessageID);
      const msg = {
        role: 'assistant',
        content: d.text != null ? d.text : acc.text,
        ...(acc.thinking ? { thinking: acc.thinking } : {}),
        ...(acc.toolCalls.length ? { toolCalls: acc.toolCalls } : {}),
      };
      st.emit({ type: 'message', msg });
      st.added.push(msg);
      // toolresult untuk tiap tool call pada pesan ini.
      for (const tc of acc.toolCalls) {
        const r = acc.results.get(tc.id) || { content: '(kosong)', ok: true };
        const toolMsg = { role: 'tool', content: r.content, toolCallId: tc.id, name: tc.name };
        st.emit({ type: 'toolresult', msg: toolMsg });
        st.added.push(toolMsg);
      }
      // Dual-model: handoff setelah putaran tool pertama selesai.
      const finish = d.finish || d.rawFinish || '';
      if (finish === 'tool-calls' && st.dualExecutor && !st.executorHanded && !st.handoffPending) {
        st.executorHanded = true;
        st.handoffPending = true;
        log.inf('oc', `dual-model: langkah tool selesai — siap serahkan ke executor ${st.dualExecutor}`);
      }
      break;
    }
    case 'session.usage.updated': {
      const u = d.tokens;
      if (u && (u.input || u.output || u.reasoning)) {
        st.emit({
          type: 'usage',
          promptTokens: u.input ?? 0,
          completionTokens: u.output ?? 0,
          ...(u.reasoning != null ? { reasoningTokens: u.reasoning } : {}),
          totalTokens: (u.input ?? 0) + (u.output ?? 0),
        });
      }
      break;
    }
    // Fallback untuk varian OpenCode lain yang masih emits message.part.*.
    case 'message.part.updated': {
      const part = d.part;
      if (!part) break;
      const msgId = (d.message && d.message.id) || d.messageID;
      const acc = st.msgs.get(msgId) || newAcc();
      switch (part.type) {
        case 'reasoning':
          if (part.content) {
            st.emit({ type: 'think', delta: part.content });
            acc.thinking += part.content;
          }
          break;
        case 'text':
          if (part.content) {
            st.emit({ type: 'text', delta: part.content });
            acc.text += part.content;
          }
          break;
        case 'tool': {
          const id = part.id;
          const name = part.name || 'tool';
          const args = part.args ?? {};
          if (part.state === 'pending' || part.state === 'running') {
            acc.toolCalls.push({ id, name, args });
            st.emit({ type: 'tool', id, name, args });
          } else if (part.state === 'completed' || part.state === 'failed') {
            const ok = part.state !== 'failed' && !(part.error || part.status === 'error');
            const output = part.output != null ? String(part.output) : '(kosong)';
            st.emit({ type: 'toolend', id, ok, output: output.slice(0, 4000) });
            acc.results.set(id, { content: output, ok });
          }
          break;
        }
      }
      break;
    }
    case 'message.updated': {
      if (d.message?.role !== 'assistant') break;
      const msgId = d.message.id;
      const acc = accOf(msgId);
      const msg = {
        role: 'assistant',
        content: d.message.content != null ? d.message.content : acc.text,
        ...(acc.thinking ? { thinking: acc.thinking } : {}),
        ...(acc.toolCalls.length ? { toolCalls: acc.toolCalls } : {}),
      };
      st.emit({ type: 'message', msg });
      st.added.push(msg);
      for (const tc of acc.toolCalls) {
        const r = acc.results.get(tc.id) || { content: '(kosong)', ok: true };
        const toolMsg = { role: 'tool', content: r.content, toolCallId: tc.id, name: tc.name };
        st.emit({ type: 'toolresult', msg: toolMsg });
        st.added.push(toolMsg);
      }
      break;
    }
    default:
      break;
  }
}
function newAcc() { return { text: '', thinking: '', toolCalls: [], results: new Map(), toolIndex: {} }; }

/** Deteksi event terminal run dari OpenCode v2.2.0. Beberapa nama bisa
 *  bervariasi minor antar patch — coba sekelompok kemungkinan agar run tidak
 *  pernah menggantung karena nama event yang tidak dikenal. */
function isTerminalEvent(rec) {
  const t = rec && rec.type;
  if (!t) return false;
  if (t === 'session.execution.succeeded' || t === 'session.execution.failed' || t === 'session.error') return true;
  if (t === 'session.ended' || t === 'session.completed' || t === 'run.completed' || t === 'run.failed') return true;
  // session.error dengan data berisi error message
  if (t === 'error' && rec.data && (rec.data.error || rec.data.message)) return true;
  return false;
}

/** Ekstrak pesan error dari event terminal, bila ada. */
function terminalError(rec) {
  if (!rec) return null;
  const d = rec.data || {};
  const m = d.error || d.message || d.detail || null;
  return m != null ? (typeof m === 'string' ? m : JSON.stringify(m)) : null;
}

/** Error retryable: transient jaringan/gateway 5xx (bukan 4xx config, bukan
 *  child mati). */
function isRetryableError(msg) {
  if (!msg) return false;
  if (/biner opencode tidak ditemukan|Provider tidak dikenal|masih sibuk/.test(msg)) return false;
  if (/serve keluar saat boot|tidak menjawab \/api\/config/.test(msg)) return false;
  if (/\b4\d\d\b/.test(msg) && !/\b5\d\d\b/.test(msg)) return false;
  return /\b5\d{2}\b|bad gateway|service unavailable|upstream|overloaded|econnreset|etimedout|socket hang up|eai_again|fetch failed|network error|terminated|stream event opencode putus|stream putus|connection (reset|refused|closed)|socket hang up/i.test(msg);
}

/** Sesi opencode sedang sibuk menjalankan prompt lain (zombie run)?
 *  OpenCode menolak prompt baru saat run sebelumnya masih dieksekusi. */
function isBusyRejection(status, body) {
  if (status !== 409 && status !== 400) return false;
  return /sibuk|busy|in progress|already running|conflict|concurrent/i.test(body || '');
}

/** Matikan run zombie di sesi opencode (interrupt) lalu beri waktu beres.
 *  T-ML71: dulu satu-satunya penyembuh adalah reset server agent dari UI —
 *  kini bridge menyembuhkan dirinya sendiri. */
async function interruptSession(port, password, sid) {
  try {
    await ocRequest(port, password, 'POST', `/api/session/${sid}/interrupt`, null, 8000);
    log.inf('oc', `interrupt sesi ${sid} terkirim (self-heal run zombie)`);
  } catch { /* best effort */ }
  // Beri OpenCode waktu menghentikan eksekusi sebelum prompt berikutnya.
  await new Promise((r) => setTimeout(r, 1500));
}

function waitAbortable(ms, signal) {
  if (signal && signal.aborted) return Promise.resolve();
  if (!signal) return new Promise((r) => setTimeout(r, ms));
  return new Promise((r) => {
    const fin = () => { clearTimeout(t); signal.removeEventListener('abort', fin); r(); };
    const t = setTimeout(fin, ms);
    signal.addEventListener('abort', fin, { once: true });
  });
}

/** Prompt untuk turn ini (sama semantik dengan pi-bridge.promptFor). */
function promptFor(messages, seen, fresh) {
  const tail = messages.slice(seen);
  const lastUser = [...tail].reverse().find((m) => m && m.role === 'user');
  const sys = APPEND_SYSTEM;
  if (fresh && seen === 0 && messages.length > 1) {
    const lines = messages
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .map((m) => `${m.role === 'user' ? 'Pengguna' : 'Asisten'}: ${(m.content || '').slice(0, 400)}`)
      .join('\n');
    const trimmed = lines.length > 8000 ? lines.slice(0, 4000) + '\n…(dipangkas)…\n' + lines.slice(-4000) : lines;
    const body = lastUser
      ? `Konteks percakapan sebelumnya (ringkas):\n${trimmed}\n\nPesan terbaru pengguna: ${lastUser.content}`
      : `Konteks percakapan sebelumnya (ringkas):\n${trimmed}\n\nLanjutkan pekerjaan dari titik terakhir.`;
    return `${sys}\n\n${body}`;
  }
  if (lastUser) return `${sys}\n\n${String(lastUser.content || '')}`;
  return `${sys}\n\n[lanjutan] Lanjutkan pekerjaan dari titik terakhir. Jangan mengulang langkah yang sudah selesai.`;
}

// ---------------------------------------------------------------- API utama

/** Satu run chat lewat engine OpenCode — kontrak sama dengan runPiChat. */
async function runOcChat({ apiKey, providerId, model, executor, messages, signal, emit, workdir }) {
  if (!Array.isArray(messages)) throw new Error('messages wajib array');
  const wd = resolveWorkdir(workdir);

  const probeKey = sessionKey(providerId, model, executor, wd, sha(apiKey).slice(0, 12));
  const probeHash = sha(probeKey).slice(0, 16);
  const probeDir = path.join(STATE_DIR, probeHash);
  let st = readState(probeDir);
  if (st.seen > 0 && (messages.length < st.seen || hashPrefix(messages, st.seen) !== st.prefix)) {
    st = { sid: '', seen: 0, prefix: '', gen: (st.gen || 0) + 1 };
    writeState(probeDir, st);
    log.wrn('oc', `history app menyimpang dari sesi OpenCode — sesi baru gen=${st.gen} (konteks lama dibuang)`);
  }
  const fresh = st.seen === 0;

  const added = [];

  const runAttempt = async (attempt) => {
    const entry = await ensureServe({ apiKey, providerId, model, executor, workdir: wd });
    const { port, password } = entry;
    const dualExecutor = executor && executor !== model ? executor : null;

    // Sesi: buat baru bila belum ada / kosong.
    let sid = st.sid;
    const reused = !!sid;
    if (!sid) {
      const r = await ocRequest(port, password, 'POST', '/api/session',
        { title: 'sachet', model: { providerID: OC_PROVIDER_ID, id: model } });
      if (r.status !== 200 && r.status !== 201) throw new Error(`buat sesi gagal: HTTP ${r.status} ${r.body.slice(0, 160)}`);
      sid = JSON.parse(r.body).data.id;
      st.sid = sid;
      log.inf('oc', `sesi baru ${sid} (gen=${st.gen})`);
    } else if (dualExecutor) {
      // T-ML70 fix: switch model ke executor PERSISTEN di sesi OpenCode.
      // Sesi yang dipakai ulang masih membawa model executor dari run
      // sebelumnya, sehingga turn berikutnya melewati orchestrator sepenuhnya
      // (hanya executor yang jalan). Setel ulang ke orchestrator di AWAL tiap
      // run agar orchestrator selalu memimpin putaran pertama.
      const mr = await ocRequest(port, password, 'POST', `/api/session/${sid}/model`,
        { model: { providerID: OC_PROVIDER_ID, id: model } }, 15000);
      if (mr.status !== 204 && mr.status !== 200)
        log.wrn('oc', `reset model ke orchestrator gagal: HTTP ${mr.status} ${mr.body.slice(0, 120)}`);
      else
        log.inf('oc', `model sesi direset ke orchestrator ${model} (sesi dipakai ulang)`);
    }

    const prompt = promptFor(messages, fresh ? 0 : st.seen, fresh);
    log.inf('oc', `prompt ${prompt.length}ch (history ${messages.length} msg, seen ${st.seen}${attempt > 1 ? `, percobaan ${attempt}` : ''})`);
    // Buka SSE SEBELUM prompt agar tidak ada event awal yang terlewat.
    const stream = openEventStream(port, password, (rec) => (rec.data && rec.data.sessionID ? rec.data.sessionID === sid : true));
    const tstate = {
      msgs: new Map(),
      dualExecutor, executorHanded: false, handoffPending: false, added,
      emit, port, password, sid,
    };

    const onAbort = () => { ocRequest(port, password, 'POST', `/api/session/${sid}/interrupt`, null, 8000).catch(() => {}); };
    signal && signal.addEventListener('abort', onAbort, { once: true });

    // `consumed` dibagi lintas fase: stream.events kumulatif, jadi jangan
    // menerjemahkan ulang event lama saat fase executor mulai.
    let consumed = 0;
    let terminal = null;

    // Satu fase = satu prompt + pompa event sampai terminal ATAU handoffPending.
    // T-ML71: `openTools` = tool call yang belum punya hasil — selama itu
    // berjalan, diam adalah NORMAL (bash/apt panjang tidak mengirim event),
    // jadi silence timer memakai TOOL_SILENCE_MS yang jauh lebih longgar.
    const runPhase = (text) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout menunggu session.execution terminal')), config.requestTimeoutMs * 12);
      let lastEventTime = Date.now();
      let silenceTimer = null;
      const armSilence = (openTools) => {
        if (silenceTimer) clearTimeout(silenceTimer);
        const budget = openTools ? TOOL_SILENCE_MS : SILENCE_MS;
        silenceTimer = setTimeout(() => {
          if (!terminal && !tstate.handoffPending) {
            log.wrn('oc', `diam ${Date.now() - lastEventTime}ms > ${Math.round(budget / 1000)}s tanpa terminal (openTools=${openTools}) — run dinyatakan macet`);
            clearTimeout(timer);
            silenceTimer = null;
            resolve();
          }
        }, budget);
      };
      const pump = () => {
        if (stream.isClosed()) {
          if (silenceTimer) clearTimeout(silenceTimer);
          if (!terminal) { clearTimeout(timer); reject(new Error('stream event opencode putus (koneksi)')); }
          else { clearTimeout(timer); resolve(); }
          return;
        }
        try {
          while (consumed < stream.events.length) {
            const rec = stream.events[consumed++];
            lastEventTime = Date.now();
            if (isTerminalEvent(rec)) {
              if (rec.type === 'session.error' || rec.type === 'error') {
                terminal = { type: 'error', message: terminalError(rec) || 'session error' };
              } else {
                terminal = { type: 'done' };
              }
              break;
            }
            translate(rec, tstate);
          }
        } catch (err) { if (silenceTimer) clearTimeout(silenceTimer); clearTimeout(timer); reject(err); return; }
        if (terminal || tstate.handoffPending) {
          if (silenceTimer) clearTimeout(silenceTimer);
          clearTimeout(timer);
          resolve();
          return;
        }
        // Tool berjalan tanpa hasil = diam yang sah; beri anggaran lebih.
        const openTools = tstate.lastAcc ? tstate.lastAcc.toolCalls.some((tc) => !tstate.lastAcc.results.has(tc.id)) : false;
        armSilence(openTools);
        stream.waiters.push(pump);
      };
      stream.waiters.push(pump);
      pump();
      armSilence(false);
    });

    // Jalankan prompt (orchestrator) lalu, bila handoff diminta, drain terminal
    // run orchestrator yang di-interrupt sebelum switch model + re-prompt.
    // T-ML71: sesi sibuk (run zombie dari turn sebelumnya) menyembuhkan diri —
    // interrupt, tunggu, coba prompt LAGI; dulu satu-satunya obat adalah reset
    // server agent dari UI. `sendPrompt` melempar 'sesi-sibuk' agar loop retry
    // luar menangani respawn bila interrupt pun gagal.
    const sendPrompt = async (text) => {
      for (let i = 0; i < 2; i++) {
        const pr = await ocRequest(port, password, 'POST', `/api/session/${sid}/prompt`, { text }, 30000);
        if (pr.status === 200 || pr.status === 201) {
          log.dbg('oc', `prompt terkirim sid=${sid}, status=${pr.status}${i ? ' (setelah self-heal)' : ''}`);
          return;
        }
        if (i === 0 && isBusyRejection(pr.status, pr.body)) {
          log.wrn('oc', `prompt ditolak: sesi sibuk (HTTP ${pr.status}) — interrupt zombie lalu coba lagi`);
          await interruptSession(port, password, sid);
          continue;
        }
        throw new Error(`prompt ditolak: HTTP ${pr.status} ${pr.body.slice(0, 200)}`);
      }
      throw new Error('prompt ditolak: sesi tetap sibuk setelah interrupt');
    };

    try {
      await sendPrompt(prompt);
      await runPhase(prompt);

      // Handoff deterministik: OpenCode mengunci model per-run, jadi tak bisa
      // switch di tengah. Interrupt run orchestrator, buang terminal-nya, switch
      // model ke executor, lalu re-prompt agar executor melanjutkan.
      if (tstate.handoffPending && dualExecutor && !terminal) {
        log.inf('oc', `dual-model: interrupt run orchestrator untuk serahkan ke executor ${dualExecutor}`);
        await ocRequest(port, password, 'POST', `/api/session/${sid}/interrupt`, null, 8000).catch(() => {});
        // Beri jeda singkat & drain event yang sudah tiba (terminal run
        // orchestrator yang di-interrupt). Time-bounded — jangan gantung bila
        // OpenCode tak mengirim terminal setelah interrupt.
        tstate.handoffPending = false;
        await new Promise((r) => setTimeout(r, 1500));
        try {
          while (consumed < stream.events.length) {
            const rec = stream.events[consumed++];
            if (isTerminalEvent(rec)) break;
            translate(rec, tstate);
          }
        } catch { /* abaikan sisa event orchestrator */ }
        terminal = null; // terminal orchestrator bukan terminal run kita

        const mr = await ocRequest(port, password, 'POST', `/api/session/${sid}/model`,
          { model: { providerID: OC_PROVIDER_ID, id: dualExecutor } }, 15000);
        if (mr.status !== 204 && mr.status !== 200)
          log.wrn('oc', `switch model executor gagal: HTTP ${mr.status} ${mr.body.slice(0, 120)}`);
        else {
          log.inf('oc', `dual-model: model sesi → executor ${dualExecutor}`);
          emit({ type: 'model', model: dualExecutor, role: 'executor' });
        }
        // Fase executor: lanjutkan dari titik terakhir (riwayat tersimpan server).
        await sendPrompt(OC_CONTINUE_PROMPT);
        await runPhase(OC_CONTINUE_PROMPT);
      }

      // T-ML71: fase berakhir TANPA terminal event = run macet (silence/timeout
      // fase) ATAU run masih dieksekusi di server OpenCode (zombie). Dulu bridge
      // memalsukan {type:'done'} — turn "selesai bersih" dengan teks setengah
      // jadi, atau diam total saat belum ada output; user harus reset server
      // manual. Sekarang: interrupt zombie, buang sesi setengah jalan, dan
      // laporkan error TERLIHAT (app menawarkan Lanjutkan bila ada pekerjaan
      // yang sudah tersimpan) — stuck tidak lagi butuh campur tangan manusia.
      if (!terminal && !tstate.handoffPending) {
        await interruptSession(port, password, sid);
        st.sid = ''; // sesi setengah jalan tidak dipercaya → turn berikutnya sesi baru
        // T-ML71: error di-EMIT (bukan throw) agar app menerima error + done
        // dalam satu stream yang rapi — throw hanya disurface server.js dan
        // membuang `added`; dengan emit, keluaran sebagian yang sudah
        // ter-persist lewat event message/toolresult tetap hidup (tombol
        // Lanjutkan), dan kasus tanpa keluaran pun berakhir tertib.
        const produced = added.some((m) => m.role === 'assistant');
        emit({
          type: 'error',
          message: produced
            ? 'run OpenCode berhenti di tengah (tidak ada event terminal) — tekan Lanjutkan untuk meneruskan'
            : 'run OpenCode macet tanpa keluaran (tidak ada terminal event) — sesi direset, coba lagi',
        });
        emit({ type: 'done' });
        return added;
      }
    } finally {
      signal && signal.removeEventListener('abort', onAbort);
      stream.close();
    }

    if (terminal && terminal.type === 'error') {
      emit({ type: 'error', message: terminal.message });
      emit({ type: 'done' });
      // Sesi yang mengalami error mid-run tidak dipercaya — turn berikutnya
      // pakai sesi baru (run zombie bisa masih hidup di dalamnya).
      st.sid = '';
      // Bukan return added — server menghitung added hanya saat sukses; error
      // sudah disurface ke app lewat event.
      throw new Error(terminal.message);
    }
    emit({ type: 'done' });

    // State sesi maju hanya setelah sukses.
    const seen = messages.length + added.length;
    const combined = messages.concat(added);
    writeState(entry.dir, { sid, seen, prefix: hashPrefix(combined, seen), gen: readState(entry.dir).gen });
    return added;
  };

  for (let attempt = 1; ; attempt++) {
    try {
      return await runAttempt(attempt);
    } catch (e) {
      log.err('oc', 'run gagal — serve dibuang agar respawn bersih:', e.message);
      await killServe();
      // Sesi yang setengah jalan tidak dipercaya → buang sid agar sesi baru.
      if (st.sid) { st.sid = ''; }
      if (attempt <= RETRY_MAX && !(signal && signal.aborted) && isRetryableError(e.message)) {
        log.inf('oc', `error retryable — ulang otomatis ${attempt}/${RETRY_MAX} setelah ${Math.round(RETRY_DELAY_MS / 1000)}s`);
        await waitAbortable(RETRY_DELAY_MS, signal);
        if (signal && signal.aborted) throw new Error('dibatalkan');
        continue;
      }
      throw e;
    }
  }
}

module.exports = {
  runOcChat,
  ocBundleAvailable,
  parseDualConfig,
  killServe,
  _internals: { canonicalMsg, hashPrefix, promptFor, writeOpencodeConfig, isRetryableError, APPEND_SYSTEM, translate, newAcc, isTerminalEvent, terminalError, isBusyRejection, interruptSession, SILENCE_MS, TOOL_SILENCE_MS },
};