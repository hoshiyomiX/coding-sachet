#!/usr/bin/env node
'use strict';
// oc-bench.js — benchmark engine agent (Fase 3 gate): ukur latensi, putaran,
// token (ptok/ctok), jumlah tool call, dan keberhasilan run untuk engine yang
// dipilih, menghantam /api/chat server sungguhan (SSE) dengan tugas tetap.
//
// Pemakaian:
//   node scripts/oc-bench.js --engines oc --repeat 2
//   node scripts/oc-bench.js --engines oc --repeat 3
//
// Env (untuk gateway yang dipakai engine):
//   SACHET_BENCH_BASE_URL   base URL gateway OpenAI-compatible
//   SACHET_BENCH_KEY        API key (default: public — cocok provider free)
//   SACHET_BENCH_PROVIDER   provider id di config.js (default: apinex)
//   SACHET_BENCH_MODEL      id model (default: big-pickle)
//   SACHET_OC_BIN / SACHET_PI_* diteruskan ke child server sesuai engine
//
// Setiap engine dijalankan di PROSES server terpisah (config dibaca saat
// require dari env), lalu N pengulangan tugas dikirim berurutan. Hasil:
// tabel ringkas + verdict gate (oc menang ≥1 metrik tanpa kalah signifikan).

const { spawn } = require('child_process');
const http = require('http');
const path = require('path');
const net = require('net');
const os = require('os');
const fs = require('fs');

const SERVER = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'server', 'server.js');

function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt;
}
const ENGINES = String(arg('engines', 'oc')).split(',').map((s) => s.trim()).filter(Boolean);
const REPEAT = Math.max(1, parseInt(arg('repeat', '1'), 10) || 1);
const PROVIDER = process.env.SACHET_BENCH_PROVIDER || 'apinex';
const KEY = process.env.SACHET_BENCH_KEY || 'public';
const MODEL = process.env.SACHET_BENCH_MODEL || 'big-pickle';
const BASE_URL = process.env.SACHET_BENCH_BASE_URL || 'https://opencode.ai/inference/openai/v1';

// Tugas tetap (ringan tapi memaksa minimal 1 putaran tool + jawaban akhir).
const TASKS = [
  'Run the shell tool exactly once: echo BENCH_OK. Then reply with a single word: done.',
  'Create a file named bench.txt containing the text HELLO using the write tool, then reply done.',
];

function freePort() {
  return new Promise((res, rej) => {
    const s = net.createServer(); s.unref();
    s.on('error', rej);
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
  });
}

function waitHealth(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      const req = http.get({ host: '127.0.0.1', port, path: '/health' }, (r) => {
        let s = ''; r.on('data', (c) => s += c); r.on('end', () => {
          if (r.statusCode === 200) resolve(JSON.parse(s)); else retry();
        });
      });
      req.on('error', retry);
      req.setTimeout(2000, () => req.destroy(new Error('timeout')));
    };
    const retry = () => { if (Date.now() > deadline) reject(new Error('server tidak sehat dalam batas waktu')); else setTimeout(tick, 400); };
    tick();
  });
}

/** Kirim satu /api/chat, kumpulkan event SSE, kembalikan metrik. */
function runChat(port, task) {
  return new Promise((resolve) => {
    const body = JSON.stringify({ model: MODEL, messages: [{ role: 'user', content: task }] });
    const t0 = Date.now();
    let firstTokenAt = 0, rounds = 0, ptok = 0, ctok = 0, total = 0, tools = 0, errored = null, done = false;
    const req = http.request({
      host: '127.0.0.1', port, path: '/api/chat', method: 'POST',
      headers: {
        'content-type': 'application/json', 'content-length': Buffer.byteLength(body),
        'x-sachet-key': KEY, 'x-sachet-provider': PROVIDER,
      },
    }, (res) => {
      let buf = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => {
        buf += chunk;
        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const frame = buf.slice(0, idx); buf = buf.slice(idx + 2);
          for (const line of frame.split('\n')) {
            if (!line.startsWith('data:')) continue;
            let ev; try { ev = JSON.parse(line.slice(5).trim()); } catch { continue; }
            if (ev.type === 'text' || ev.type === 'think') { if (!firstTokenAt) firstTokenAt = Date.now() - t0; }
            else if (ev.type === 'tool') tools++;
            else if (ev.type === 'message' && ev.msg && ev.msg.role === 'assistant') rounds++;
            else if (ev.type === 'usage') { ptok += ev.promptTokens || 0; ctok += ev.completionTokens || 0; total += ev.totalTokens || 0; }
            else if (ev.type === 'error') errored = ev.message || 'error';
            else if (ev.type === 'done') done = true;
          }
        }
      });
      res.on('end', () => { clearTimeout(hard); resolve({
        latencyMs: Date.now() - t0, firstTokenMs: firstTokenAt, rounds, ptok, ctok, total, tools,
        success: done && !errored, error: errored,
      }); });
    });
    // Batas keras sisi klien: run yang menggantung tidak boleh memblokir bench.
    let hard = setTimeout(() => { req.destroy(new Error('client timeout 90s')); }, 90000);
    req.on('error', (e) => { clearTimeout(hard); resolve({ latencyMs: Date.now() - t0, firstTokenMs: 0, rounds: 0, ptok: 0, ctok: 0, total: 0, tools: 0, success: false, error: e.message }); });
    req.write(body); req.end();
  });
}

function summarize(runs) {
  const ok = runs.filter((r) => r.success);
  const avg = (k) => ok.length ? Math.round(ok.reduce((a, r) => a + r[k], 0) / ok.length) : 0;
  return {
    runs: runs.length,
    success: ok.length,
    avgLatencyMs: avg('latencyMs'),
    avgFirstTokenMs: avg('firstTokenMs'),
    avgRounds: ok.length ? +(ok.reduce((a, r) => a + r.rounds, 0) / ok.length).toFixed(1) : 0,
    avgPtok: avg('ptok'),
    avgCtok: avg('ctok'),
    avgTotal: avg('total'),
    avgTools: ok.length ? +(ok.reduce((a, r) => a + r.tools, 0) / ok.length).toFixed(1) : 0,
  };
}

async function benchEngine(engine) { // hanya "oc" — engine lain dihapus
  const port = await freePort();
  const workdir = fs.mkdtempSync(path.join(os.tmpdir(), 'oc-bench-'));
  const env = {
    ...process.env,
    SACHET_ENGINE: "oc",
    SACHET_PORT: String(port),
    SACHET_APINEX_BASE_URL: BASE_URL,
    SACHET_WORKDIR: workdir,
  };
  const child = spawn(process.execPath, [SERVER], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let serverLog = '';
  child.stdout.on('data', (d) => serverLog += d);
  child.stderr.on('data', (d) => serverLog += d);
  let health;
  try { health = await waitHealth(port, 40000); }
  catch (e) { child.kill('SIGKILL'); throw new Error(`engine ${engine}: ${e.message}\n${serverLog.slice(-400)}`); }
  const bundleOk = health.ocBundle === true;
  const runs = [];
  for (let i = 0; i < REPEAT; i++) {
    for (const task of TASKS) runs.push(await runChat(port, task));
  }
  child.kill('SIGKILL');
  return { engine, bundleOk, summary: summarize(runs), runs };
}

function fmt(n, w = 8) { return String(n).padStart(w); }

(async () => {
  console.log(`Gateway: provider=${PROVIDER} model=${MODEL} baseUrl=${BASE_URL}`);
  console.log(`Engine: ${ENGINES.join(', ')}  repeat=${REPEAT}  tasks=${TASKS.length}\n`);
  const results = [];
  for (const engine of ENGINES) {
    process.stdout.write(`[bench] ${engine}… `);
    try {
      const r = await benchEngine(engine);
      results.push(r);
      console.log(r.bundleOk ? 'ok' : 'bundle TIDAK ada (fallback?)');
      if (r.runs.some((x) => x.error)) {
        const e = r.runs.find((x) => x.error);
        console.log(`   note: contoh error: ${String(e.error).slice(0, 140)}`);
      }
    } catch (e) {
      console.log('GAGAL');
      console.error('   ' + String(e.message).split('\n').join('\n   '));
    }
  }

  console.log('\n=== HASIL BENCHMARK ===');
  const cols = ['engine', 'success', 'avgLatencyMs', 'avgFirstTokenMs', 'avgRounds', 'avgPtok', 'avgCtok', 'avgTotal', 'avgTools'];
  console.log(cols.map((c, i) => c.padStart(i === 0 ? 8 : 13)).join(''));
  for (const r of results) {
    const s = r.summary;
    const row = [r.engine, `${s.success}/${s.runs}`, s.avgLatencyMs, s.avgFirstTokenMs, s.avgRounds, s.avgPtok, s.avgCtok, s.avgTotal, s.avgTools];
    console.log(row.map((v, i) => String(v).padStart(i === 0 ? 8 : 13)).join(''));
  }

  // Verdict gate (jika oc dibandingkan dengan engine lain).
  const oc = results.find((r) => r.engine === 'oc');
  const others = results.filter((r) => r.engine !== 'oc');
  if (oc && others.length) {
    const s = oc.summary;
    let wins = 0, badLoss = 0;
    for (const o of others) {
      const os = o.summary;
      if (os.success === 0) continue;
      const better = [];
      if (s.avgTotal && os.avgTotal && s.avgTotal < os.avgTotal) better.push('token');
      if (s.avgLatencyMs && os.avgLatencyMs && s.avgLatencyMs < os.avgLatencyMs) better.push('latensi');
      if (s.avgRounds && os.avgRounds && s.avgRounds < os.avgRounds) better.push('putaran');
      if (better.length) wins++;
      // kalah signifikan: >2x lebih boros token ATAU >2x lebih lambat
      if ((s.avgTotal > os.avgTotal * 2 && os.avgTotal > 0) || (s.avgLatencyMs > os.avgLatencyMs * 2 && os.avgLatencyMs > 0)) badLoss++;
    }
    const pass = wins >= 1 && badLoss === 0 && s.success > 0;
    console.log(`\nGATE: ${pass ? 'LULUS' : 'BELUM LULUS'} — oc menang di ${wins} engine, kalah-signifikan=${badLoss}, sukses=${s.success}/${s.runs}`);
    process.exit(pass ? 0 : 2);
  } else {
    console.log(`\n(oc saja — baseline tercatat; gate butuh engine pembanding)`);
    process.exit(oc && oc.summary.success > 0 ? 0 : 2);
  }
})();