'use strict';
// APInex agent server — HTTP entry point.
// Listens on 127.0.0.1 only. The Android app is its sole client.
//
// Routes:
//   GET  /health              liveness + versions
//   GET  /api/models          gateway model list (needs X-Apinex-Key)
//   POST /api/chat            agent loop, SSE events (needs X-Apinex-Key)
//   GET  /api/fs?path=        list directory           (file explorer)
//   GET  /api/fs/file?path=   read a file
//   PUT  /api/fs/file         write a file {path, content}
//   POST /api/fs              mkdir | rm | mv {action, path, to?}

const http = require('http');
const fs = require('fs');
const { config } = require('./config');
const { runAgent } = require('./agent');
const { safePath, listDir, readFile, writeFile } = require('./tools');

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function readJson(req, limit) {
  return readBody(req, limit).then((s) => (s ? JSON.parse(s) : {}));
}

// ---------- models cache ----------
// The gateway is fast, but the guest's network path (proot + carrier DNS)
// can be slow on the first call. A short per-key cache makes retries and
// repeated opens of the model picker instant.
const modelsCache = new Map(); // `${key}|${baseUrl}` -> { at, ids }
const MODELS_CACHE_TTL = 5 * 60 * 1000;

// ---------- upstream helpers ----------

function gatewayKey(req) {
  const h = req.headers['x-apinex-key'];
  if (typeof h !== 'string' || !h.trim()) return null;
  return h.trim();
}

/** GET {baseUrl}/models through the gateway with the caller's key. */
async function fetchModels(apiKey) {
  const res = await fetch(config.baseUrl + '/models', {
    headers: { Authorization: `Bearer ${apiKey}` },
    // Keep this comfortably below the app-side read timeout (35 s) so the
    // app always receives our error JSON instead of its own "timeout".
    signal: AbortSignal.timeout(20000),
  }).catch((e) => { throw new Error(`Koneksi ke gateway gagal: ${e.message}`); });

  const text = await res.text().catch(() => '');
  if (!res.ok) {
    let msg = text.slice(0, 300);
    try { msg = JSON.parse(text).error?.message || msg; } catch { /* not JSON */ }
    throw new Error(`HTTP ${res.status}: ${msg}`);
  }
  let j;
  try { j = JSON.parse(text); } catch { throw new Error('Balasan /models bukan JSON'); }
  const ids = Array.isArray(j.data)
    ? j.data.map((m) => m && m.id).filter((x) => typeof x === 'string')
    : Array.isArray(j) ? j.filter((x) => typeof x === 'string') : [];
  return ids.sort((a, b) => a.localeCompare(b));
}

// ---------- routes ----------

async function route(req, res) {
  const url = new URL(req.url, `http://${config.host}:${config.port}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, {
      ok: true, service: 'apinex-agent', version: config.version,
      node: process.version, baseUrl: config.baseUrl, workdir: config.workdir,
    });
  }

  if (req.method === 'GET' && url.pathname === '/api/models') {
    const key = gatewayKey(req);
    if (!key) return sendJson(res, 401, { error: 'X-Apinex-Key hilang' });
    const cacheKey = `${key}|${config.baseUrl}`;
    const hit = modelsCache.get(cacheKey);
    if (hit && Date.now() - hit.at < MODELS_CACHE_TTL) {
      return sendJson(res, 200, { models: hit.ids, cached: true });
    }
    try {
      const ids = await fetchModels(key);
      modelsCache.set(cacheKey, { at: Date.now(), ids });
      return sendJson(res, 200, { models: ids });
    } catch (e) {
      return sendJson(res, 502, { error: e.message });
    }
  }

  if (req.method === 'POST' && url.pathname === '/api/chat') {
    const key = gatewayKey(req);
    if (!key) return sendJson(res, 401, { error: 'X-Apinex-Key hilang' });
    let body;
    try { body = await readJson(req, config.jsonBodyLimit); } catch (e) {
      return sendJson(res, 400, { error: `body: ${e.message}` });
    }
    if (!body.model || !Array.isArray(body.messages)) {
      return sendJson(res, 400, { error: 'model dan messages wajib' });
    }

    // SSE response headers
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    const emit = (ev) => {
      try { res.write(`data: ${JSON.stringify(ev)}\n\n`); } catch { /* client gone */ }
    };
    const keepAlive = setInterval(() => { try { res.write(': ping\n\n'); } catch { /* ignore */ } }, 15000);

    const abort = new AbortController();
    req.on('close', () => abort.abort());

    try {
      await runAgent({
        apiKey: key,
        model: body.model,
        messages: body.messages,
        systemPrompt: body.systemPrompt,
        signal: abort.signal,
        emit,
      });
    } catch (e) {
      emit({ type: 'error', message: e.message || String(e) });
      emit({ type: 'done' });
    } finally {
      clearInterval(keepAlive);
      try { res.end(); } catch { /* ignore */ }
    }
    return;
  }

  // ---------- file explorer ----------

  if (req.method === 'GET' && url.pathname === '/api/fs') {
    const p = safePath(url.searchParams.get('path') || config.workdir);
    if (p === null) return sendJson(res, 400, { error: 'Path di luar sandbox' });
    const r = listDir(p, 1);
    if (!r.ok) return sendJson(res, 404, { error: r.error });
    return sendJson(res, 200, r);
  }

  if (req.method === 'GET' && url.pathname === '/api/fs/file') {
    const r = readFile(url.searchParams.get('path') || '');
    if (!r.ok) return sendJson(res, r.error.startsWith('stat:') ? 404 : 400, { error: r.error });
    return sendJson(res, 200, r);
  }

  if (req.method === 'PUT' && url.pathname === '/api/fs/file') {
    let body;
    try { body = await readJson(req, config.jsonBodyLimit); } catch (e) {
      return sendJson(res, 400, { error: `body: ${e.message}` });
    }
    const r = writeFile(body.path, body.content);
    if (!r.ok) return sendJson(res, 400, { error: r.error });
    return sendJson(res, 200, r);
  }

  if (req.method === 'POST' && url.pathname === '/api/fs') {
    let body;
    try { body = await readJson(req, config.jsonBodyLimit); } catch (e) {
      return sendJson(res, 400, { error: `body: ${e.message}` });
    }
    const p = safePath(body.path || '');
    if (p === null) return sendJson(res, 400, { error: 'Path di luar sandbox' });
    try {
      if (body.action === 'mkdir') {
        fs.mkdirSync(p, { recursive: true });
        return sendJson(res, 200, { ok: true });
      }
      if (body.action === 'rm') {
        fs.rmSync(p, { recursive: true, force: true });
        return sendJson(res, 200, { ok: true });
      }
      if (body.action === 'mv') {
        const to = safePath(body.to || '');
        if (to === null) return sendJson(res, 400, { error: 'Tujuan di luar sandbox' });
        fs.mkdirSync(require('path').dirname(to), { recursive: true });
        fs.renameSync(p, to);
        return sendJson(res, 200, { ok: true });
      }
      return sendJson(res, 400, { error: 'action tidak dikenal' });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  sendJson(res, 404, { error: 'not found' });
}

/** Start the server; returns the http.Server (useful for tests). */
function start() {
  fs.mkdirSync(config.workdir, { recursive: true });
  const srv = http.createServer((req, res) => {
    route(req, res).catch((e) => {
      log('route error:', e.message);
      try { sendJson(res, 500, { error: e.message }); } catch { /* headers sent */ }
    });
  });
  srv.listen(config.port, config.host, () => {
    log(`apinex-agent ${config.version} di ${config.host}:${config.port}`);
    log(`gateway: ${config.baseUrl}`);
    log(`workdir: ${config.workdir}`);
  });
  // Die loudly if the event loop breaks: the app's supervisor restarts us.
  process.on('uncaughtException', (e) => { log('uncaught:', e.stack || e.message); process.exit(1); });
  process.on('unhandledRejection', (e) => { log('unhandled:', e); process.exit(1); });
  return srv;
}

function main() {
  start();
  productionSetup();
}

/**
 * Production-only hardening (runs inside the proot guest; never in tests):
 * 1. resolv.conf options that keep DNS fast on Android carrier networks,
 * 2. IPv4-first outbound ordering (avoids IPv6 black-holes),
 * 3. a fire-and-forget warm-up that primes DNS + TLS before first use.
 */
function productionSetup() {
  try {
    const wanted = [
      'nameserver 1.1.1.1',
      'nameserver 8.8.8.8',
      'nameserver 9.9.9.9',
      'options timeout:2 attempts:2 single-request-reopen',
      '',
    ].join('\n');
    let current = null;
    try { current = fs.readFileSync('/etc/resolv.conf', 'utf8'); } catch { /* missing */ }
    if (current !== wanted) {
      fs.writeFileSync('/etc/resolv.conf', wanted);
      log('resolv.conf diperbarui (timeout:2 attempts:2 single-request-reopen)');
    }
  } catch (e) {
    log('resolv.conf tidak bisa diubah:', e.message);
  }

  try { require('node:dns').setDefaultResultOrder('ipv4first'); } catch { /* older node */ }

  fetch(config.baseUrl + '/models', { signal: AbortSignal.timeout(10000) })
    .then((r) => log(`warm-up gateway: HTTP ${r.status}`))
    .catch((e) => log('warm-up gateway gagal:', e.message));
}

if (require.main === module) main();

module.exports = { main, start, route, fetchModels, log };
