'use strict';
// Coding Sachet agent server — HTTP entry point.
// Listens on 127.0.0.1 only. The Android app is its sole client.
//
// Routes:
//   GET  /health              liveness + versions + providers
//   GET  /api/models          provider model list (X-Sachet-Key + X-Sachet-Provider)
//   POST /api/chat            agent loop, SSE events (X-Sachet-Key + X-Sachet-Provider)
//   POST /api/verify-models   live model check, SSE events {start|result|done}
//   GET  /api/quota           remaining tokens (?fresh=1 skips cache)
//   GET  /api/dns             current guest resolver servers
//   POST /api/dns             rewrite the guest resolver {servers:[...]} (VPN-aware)
//   GET  /api/fs?path=        list directory           (file explorer)
//   GET  /api/fs/file?path=   read a file
//   PUT  /api/fs/file         write a file {path, content}
//   POST /api/fs              mkdir | rm | mv {action, path, to?}

const http = require('http');
const fs = require('fs');
const path = require('path');
const { config, providerById, HOME } = require('./config');
const { runAgent, verifyModels } = require('./agent');
const { safePath, listDir, readFile, writeFile } = require('./tools');
const { fetchWithRetry, fetchRetryTimeout, netErrorDetail } = require('./net');
const log = require('./log');

// Fire-and-forget gateway warm-up after a resolver rewrite is only wanted
// in production; the test suite sets SACHET_NO_WARMUP=1 to stay hermetic.
const WARMUP_ENABLED = process.env.SACHET_NO_WARMUP !== '1';

// Back-compat shim: the old single-argument log() stays exported so any
// caller (tests, ad-hoc scripts) keeps working; it now lands in the
// standardized format as an INF line from the server module.
const logLine = (...a) => log.inf('srv', ...a);

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
// The gateways are fast, but the guest's network path (proot + carrier DNS)
// can be slow on the first call. A short per-key cache makes retries and
// repeated opens of the model picker instant.
const modelsCache = new Map(); // `${providerId}|${baseUrl}|${key}` -> { at, ids }
const MODELS_CACHE_TTL = 5 * 60 * 1000;

// ---------- upstream helpers ----------

function gatewayKey(req) {
  const h = req.headers['x-sachet-key'];
  if (typeof h !== 'string' || !h.trim()) return null;
  return h.trim();
}

/** The X-Sachet-Provider header selects the upstream; default "apinex". */
function gatewayProviderId(req) {
  const h = req.headers['x-sachet-provider'];
  if (typeof h !== 'string' || !h.trim()) return config.defaultProvider;
  return h.trim().toLowerCase();
}

/** GET {provider.baseUrl}/models with the caller's key.
 *
 * A short fresh-timeout retry absorbs the cold VPN path (first DNS + TLS
 * through the tunnel can be slow once, then runs fast). Worst case
 * 15 s + 0.5 s + 15 s stays under the app-side 35 s read timeout, so the
 * app always receives our error JSON instead of its own "timeout". */
async function fetchModels(apiKey, provider, retryOpts) {
  const res = await fetchRetryTimeout(
    provider.baseUrl + '/models',
    { headers: { Authorization: `Bearer ${apiKey}` } },
    retryOpts || { timeoutMs: 15000, retries: 1, delays: [500] }
  ).catch((e) => { throw new Error(`Koneksi ke gateway gagal: ${netErrorDetail(e)}`); });

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

// ---------- quota cache ----------
// The app shows a small "sisa token" chip; it refreshes on screen open,
// after every finished turn, and on tap. A per-key cache keeps that cheap.
const quotaCache = new Map(); // `${providerId}|${key}` -> { at, payload }
const QUOTA_CACHE_TTL = 60 * 1000;

const num = (x) => {
  const n = Number(x);
  return Number.isFinite(n) ? n : null;
};

async function getJsonBody(res) {
  const text = await res.text().catch(() => '');
  try { return JSON.parse(text); } catch { return null; }
}

/** Error message out of a gateway error body (OpenAI {error}, {detail}, raw). */
function gatewayErrorText(text) {
  let msg = String(text || '').slice(0, 300);
  try {
    const j = JSON.parse(msg);
    msg = (j.error && (j.error.message || j.error)) || j.detail || j.message || msg;
  } catch { /* not JSON */ }
  return String(msg);
}

/**
 * Remaining quota for the caller's key — ONE officially documented API
 * (owner order T-ML20): GET /v1/subscription (apinex.bond/developers)
 * returns the token counters. No fallback chain: when the documented
 * endpoint fails, the failure surfaces directly.
 */
async function fetchQuota(apiKey, provider) {
  const q = provider.quota;
  if (!q || !q.subscriptionPath) throw new Error('Gateway tidak menyediakan info kuota');
  const headers = { Authorization: `Bearer ${apiKey}` };

  let sub;
  try {
    sub = await fetchWithRetry(provider.baseUrl + q.subscriptionPath, {
      headers,
      signal: AbortSignal.timeout(12000),
    });
  } catch (e) {
    throw new Error(`Koneksi ke gateway gagal: ${netErrorDetail(e)}`);
  }

  if (!sub.ok) {
    const text = await sub.text().catch(() => '');
    throw new Error(`HTTP ${sub.status}: ${gatewayErrorText(text)}`);
  }

  const j = (await getJsonBody(sub)) || {};
  return {
    supported: true,
    source: 'native',
    unlimited: j.unlimited === true,
    tokensRemaining: num(j.tokens_remaining),
    tokensUsed: num(j.tokens_used),
    tokenLimit: num(j.token_limit),
  };
}

// ---------- guest resolver (VPN-aware DNS) ----------

// IPv4/IPv6 literals only — no hostnames, no zone indices (%wlan0).
const DNS_IP_RE = /^[0-9A-Fa-f:.]+$/;

/** Rewrite the guest's resolv.conf: system DNS first, public fallbacks last. */
function writeResolvConf(systemServers) {
  const seen = new Set();
  const valid = [];
  for (const s of systemServers || []) {
    const v = String(s || '').trim();
    if (!v || !DNS_IP_RE.test(v) || seen.has(v)) continue;
    seen.add(v);
    valid.push(v);
    if (valid.length >= 3) break;
  }
  const fallbacks = (config.dnsFallbackServers || []).filter((f) => !seen.has(f));
  const nameservers = [...valid, ...fallbacks];
  const lines = ['# managed by coding-sachet (/api/dns)'];
  for (const ns of nameservers) lines.push(`nameserver ${ns}`);
  lines.push('options timeout:1 attempts:2 single-request-reopen', '');
  fs.writeFileSync(config.resolvConfPath, lines.join('\n'));
  return nameservers;
}

function currentResolvServers() {
  let text = '';
  try { text = fs.readFileSync(config.resolvConfPath, 'utf8'); } catch { /* missing */ }
  return text.split('\n')
    .filter((l) => l.startsWith('nameserver '))
    .map((l) => l.slice('nameserver '.length).trim())
    .filter(Boolean);
}

// ---------- gateway warm-up ----------

// The app pushes the system DNS right after the server answers /health,
// which used to fire a second warm-up round in the same second — every
// log line appeared twice. A minimum spacing between rounds keeps the log
// readable while still re-warming after real network changes. Tests can
// zero it via config (SACHET_WARMUP_MIN_INTERVAL_MS).
let lastWarmupAt = 0;

/** Fire-and-forget DNS + TLS warm-up for every provider. A cold VPN path
 * (first TLS handshake through the tunnel) can outrun a short timeout —
 * each attempt gets a fresh budget, so a transient timeout heals instead
 * of logging a scary "gagal" line. Returns false when throttled out. */
function warmUpGateways() {
  const now = Date.now();
  if (now - lastWarmupAt < config.warmupMinIntervalMs) return false;
  lastWarmupAt = now;
  for (const p of Object.values(config.providers)) {
    fetchRetryTimeout(p.baseUrl + '/models', {}, { timeoutMs: 20000, retries: 2, delays: [500, 1500] })
      .then((r) => log.dbg('net', `warm-up ${p.baseUrl}: HTTP ${r.status}`))
      .catch((e) => log.wrn('net', `warm-up ${p.baseUrl} gagal:`, e.message));
  }
  return true;
}

// ---------- routes ----------

async function route(req, res) {
  const url = new URL(req.url, `http://${config.host}:${config.port}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, {
      ok: true, service: 'sachet-agent', version: config.version,
      node: process.version, workdir: config.workdir,
      providers: Object.fromEntries(
        Object.entries(config.providers).map(([id, p]) => [id, p.baseUrl])
      ),
    });
  }

  if (req.method === 'GET' && url.pathname === '/api/models') {
    const key = gatewayKey(req);
    if (!key) return sendJson(res, 401, { error: 'X-Sachet-Key hilang' });
    const providerId = gatewayProviderId(req);
    const provider = providerById(providerId);
    if (!provider) {
      log.wrn('srv', 'models: provider tidak dikenal', providerId);
      return sendJson(res, 400, { error: `Provider tidak dikenal: ${providerId}` });
    }
    const cacheKey = `${providerId}|${provider.baseUrl}|${key}`;
    const hit = modelsCache.get(cacheKey);
    if (hit && Date.now() - hit.at < MODELS_CACHE_TTL) {
      log.dbg('srv', `models ${providerId} cache ${hit.ids.length} model`);
      return sendJson(res, 200, { models: hit.ids, cached: true });
    }
    const t0 = Date.now();
    try {
      const ids = await fetchModels(key, provider);
      modelsCache.set(cacheKey, { at: Date.now(), ids });
      log.inf('srv', `models ${providerId} OK ${ids.length} model ${Date.now() - t0}ms`);
      return sendJson(res, 200, { models: ids });
    } catch (e) {
      log.err('srv', `models ${providerId} gagal ${Date.now() - t0}ms:`, e.message);
      return sendJson(res, 502, { error: e.message });
    }
  }

  if (req.method === 'POST' && url.pathname === '/api/chat') {
    const key = gatewayKey(req);
    if (!key) return sendJson(res, 401, { error: 'X-Sachet-Key hilang' });
    const providerId = gatewayProviderId(req);
    const provider = providerById(providerId);
    if (!provider) {
      return sendJson(res, 400, { error: `Provider tidak dikenal: ${providerId}` });
    }
    let body;
    try { body = await readJson(req, config.jsonBodyLimit); } catch (e) {
      return sendJson(res, 400, { error: `body: ${e.message}` });
    }
    if (!body.model || !Array.isArray(body.messages)) {
      log.wrn('srv', 'chat: body tanpa model/messages');
      return sendJson(res, 400, { error: 'model dan messages wajib' });
    }

    log.inf('srv', `chat mulai ${providerId} ${body.model} msgs=${body.messages.length}`);
    const chatT0 = Date.now();
    let chatTurns = 0;

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
      const added = await runAgent({
        apiKey: key,
        providerId,
        model: body.model,
        messages: body.messages,
        systemPrompt: body.systemPrompt,
        // OpenAI-compatible request options (Settings → Konfigurasi JSON):
        // sanitized inside the agent; reserved keys can never pass.
        options: body.options,
        signal: abort.signal,
        emit,
      });
      chatTurns = added.filter((m) => m.role === 'assistant').length;
      log.inf('srv', `chat selesai ${providerId} ${body.model} turns=${chatTurns} msgs+${added.length} ${Date.now() - chatT0}ms`);
    } catch (e) {
      // A user stop (stop button / screen close) aborts the run — that is a
      // normal exit, not a failure; keep the error channel for real faults.
      if (e.message === 'dibatalkan') log.dbg('srv', `chat dibatalkan user ${providerId} ${body.model} ${Date.now() - chatT0}ms`);
      else log.err('srv', `chat gagal ${providerId} ${body.model} ${Date.now() - chatT0}ms:`, e.message);
      emit({ type: 'error', message: e.message || String(e) });
      emit({ type: 'done' });
    } finally {
      clearInterval(keepAlive);
      try { res.end(); } catch { /* ignore */ }
    }
    return;
  }

  if (req.method === 'POST' && url.pathname === '/api/verify-models') {
    // Live model verification (T-ML26, owner order: "mengecek actual bukan
    // dari membaca hardcoded list"). The app sends the model list it is
    // displaying; results stream back as SSE so the dots fill progressively.
    const key = gatewayKey(req);
    if (!key) return sendJson(res, 401, { error: 'X-Sachet-Key hilang' });
    const providerId = gatewayProviderId(req);
    const provider = providerById(providerId);
    if (!provider) {
      return sendJson(res, 400, { error: `Provider tidak dikenal: ${providerId}` });
    }
    let body;
    try { body = await readJson(req, 64 * 1024); } catch (e) {
      return sendJson(res, 400, { error: `body: ${e.message}` });
    }
    let models = Array.isArray(body.models)
      ? body.models.filter((m) => typeof m === 'string' && m && m.length <= 256)
      : [];
    models = [...new Set(models)].slice(0, 1000);
    if (!models.length) {
      // No list passed: verify the provider's own catalogue instead.
      try { models = await fetchModels(key, provider); } catch (e) {
        return sendJson(res, 502, { error: e.message });
      }
    }
    log.inf('srv', `verify ${providerId} mulai ${models.length} model`);
    const vT0 = Date.now();

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
      await verifyModels({ apiKey: key, providerId, models, signal: abort.signal, emit });
    } catch (e) {
      // A user cancel (sheet closed / cancel button) aborts the sweep — a
      // normal exit, not a failure; results collected so far stay valid.
      if (e.message === 'dibatalkan') log.dbg('srv', `verify dibatalkan user ${providerId} ${Date.now() - vT0}ms`);
      else log.err('srv', `verify gagal ${providerId} ${Date.now() - vT0}ms:`, e.message);
      emit({ type: 'error', message: e.message || String(e) });
      emit({ type: 'done' });
    } finally {
      clearInterval(keepAlive);
      try { res.end(); } catch { /* ignore */ }
    }
    return;
  }

  // ---------- quota + guest resolver ----------

  if (req.method === 'GET' && url.pathname === '/api/quota') {
    const key = gatewayKey(req);
    if (!key) return sendJson(res, 401, { error: 'X-Sachet-Key hilang' });
    const providerId = gatewayProviderId(req);
    const provider = providerById(providerId);
    if (!provider) {
      return sendJson(res, 400, { error: `Provider tidak dikenal: ${providerId}` });
    }
    // NIM has no per-key balance API; the app hides the chip.
    if (!provider.quota) return sendJson(res, 200, { supported: false });
    const cacheKey = `${providerId}|${key}`;
    const fresh = url.searchParams.get('fresh') === '1';
    if (!fresh) {
      const hit = quotaCache.get(cacheKey);
      if (hit && Date.now() - hit.at < QUOTA_CACHE_TTL) {
        log.dbg('srv', `quota ${providerId} cache`);
        return sendJson(res, 200, { ...hit.payload, cached: true });
      }
    }
    const qT0 = Date.now();
    try {
      const payload = await fetchQuota(key, provider);
      quotaCache.set(cacheKey, { at: Date.now(), payload });
      log.inf('srv', `quota ${providerId} ${payload.source} ${Date.now() - qT0}ms`);
      return sendJson(res, 200, payload);
    } catch (e) {
      log.wrn('srv', `quota ${providerId} gagal ${Date.now() - qT0}ms:`, e.message);
      return sendJson(res, 502, { error: e.message });
    }
  }

  if (req.method === 'GET' && url.pathname === '/api/dns') {
    return sendJson(res, 200, { nameservers: currentResolvServers() });
  }

  if (req.method === 'POST' && url.pathname === '/api/dns') {
    let body;
    try { body = await readJson(req, 4096); } catch (e) {
      return sendJson(res, 400, { error: `body: ${e.message}` });
    }
    const servers = Array.isArray(body.servers) ? body.servers : [];
    let nameservers;
    try { nameservers = writeResolvConf(servers); } catch (e) {
      log.err('srv', 'resolv.conf gagal ditulis:', e.message);
      return sendJson(res, 500, { error: e.message });
    }
    logLine(`resolv.conf diperbarui: ${nameservers.join(', ')}`);
    if (WARMUP_ENABLED) warmUpGateways();
    return sendJson(res, 200, { ok: true, nameservers });
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
    if (p === null) {
      log.wrn('srv', `fs ${body.action || '?'}: path di luar sandbox`);
      return sendJson(res, 400, { error: 'Path di luar sandbox' });
    }
    try {
      if (body.action === 'mkdir') {
        fs.mkdirSync(p, { recursive: true });
        log.inf('srv', `fs mkdir ${p}`);
        return sendJson(res, 200, { ok: true });
      }
      if (body.action === 'rm') {
        fs.rmSync(p, { recursive: true, force: true });
        log.inf('srv', `fs rm ${p}`);
        return sendJson(res, 200, { ok: true });
      }
      if (body.action === 'mv') {
        const to = safePath(body.to || '');
        if (to === null) return sendJson(res, 400, { error: 'Tujuan di luar sandbox' });
        fs.mkdirSync(path.dirname(to), { recursive: true });
        fs.renameSync(p, to);
        log.inf('srv', `fs mv ${p} -> ${to}`);
        return sendJson(res, 200, { ok: true });
      }
      return sendJson(res, 400, { error: 'action tidak dikenal' });
    } catch (e) {
      log.err('srv', `fs ${body.action} gagal:`, e.message);
      return sendJson(res, 500, { error: e.message });
    }
  }

  sendJson(res, 404, { error: 'not found' });
}

/** Start the server; returns the http.Server (useful for tests). */
function start() {
  try {
    fs.mkdirSync(config.workdir, { recursive: true });
  } catch (e) {
    // Shared storage vanished between config resolution and startup
    // (unmounted / permission revoked) — fall back to the private rootfs.
    config.workdir = path.join(HOME, 'work');
    fs.mkdirSync(config.workdir, { recursive: true });
    log.wrn('srv', `workdir fallback /root/work (${e.message})`);
  }
  const srv = http.createServer((req, res) => {
    route(req, res).catch((e) => {
      log.err('srv', 'route error:', e.message);
      try { sendJson(res, 500, { error: e.message }); } catch { /* headers sent */ }
    });
  });
  srv.listen(config.port, config.host, () => {
    log.inf('srv', `sachet-agent ${config.version} di ${config.host}:${config.port}`);
    for (const [id, p] of Object.entries(config.providers)) {
      log.inf('srv', `provider ${id}: ${p.baseUrl}`);
    }
    log.inf('srv', `workdir: ${config.workdir}`);
  });
  // Die loudly if the event loop breaks: the app's supervisor restarts us.
  process.on('uncaughtException', (e) => { log.err('srv', 'uncaught:', e.stack || e.message); process.exit(1); });
  process.on('unhandledRejection', (e) => { log.err('srv', 'unhandled:', e); process.exit(1); });
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
    // The app pushes the system's (VPN-aware) DNS servers via POST /api/dns
    // once the server answers /health, and again on every network change.
    // Only seed the fallback file when nothing managed is present yet, so a
    // supervisor restart never clobbers a working VPN resolver config.
    let current = null;
    try { current = fs.readFileSync(config.resolvConfPath, 'utf8'); } catch { /* missing */ }
    if (!current || !current.includes('# managed by coding-sachet')) {
      const nameservers = writeResolvConf([]);
      log.inf('srv', `resolv.conf fallback: ${nameservers.join(', ')}`);
    }
  } catch (e) {
    log.wrn('srv', 'resolv.conf tidak bisa diubah:', e.message);
  }

  try { require('node:dns').setDefaultResultOrder('ipv4first'); } catch { /* older node */ }

  if (WARMUP_ENABLED) warmUpGateways();
}

if (require.main === module) main();

module.exports = { main, start, route, fetchModels, fetchQuota, writeResolvConf, currentResolvServers, warmUpGateways, log: logLine };
