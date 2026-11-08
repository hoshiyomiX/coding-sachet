'use strict';
// Coding Sachet agent server — HTTP entry point.
// Listens on 127.0.0.1 only. The Android app is its sole client.
//
// Routes:
//   GET  /health              liveness + versions + providers
//   GET  /api/models          provider model list (X-Sachet-Key + X-Sachet-Provider)
//   POST /api/chat            agent loop, SSE events (X-Sachet-Key + X-Sachet-Provider)
//   GET  /api/quota           remaining tokens/balance (?fresh=1 skips cache)
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
const { runAgent } = require('./agent');
const { safePath, listDir, readFile, writeFile } = require('./tools');
const { fetchWithRetry, fetchRetryTimeout, netErrorDetail } = require('./net');

// Fire-and-forget gateway warm-up after a resolver rewrite is only wanted
// in production; the test suite sets SACHET_NO_WARMUP=1 to stay hermetic.
const WARMUP_ENABLED = process.env.SACHET_NO_WARMUP !== '1';

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
 * Remaining quota for the caller's key.
 * Primary: APInex-native /subscription + /balance (token counters).
 * Fallback: the OpenAI-style billing pair new-api deployments expose —
 * only when the native endpoints answer 404/405 (not deployed). A network
 * failure is surfaced directly: the billing endpoints live on the same
 * host, so retrying them would only burn the caller's timeout.
 */
async function fetchQuota(apiKey, provider) {
  const q = provider.quota;
  const headers = { Authorization: `Bearer ${apiKey}` };
  const t = () => ({ signal: AbortSignal.timeout(12000) });

  if (q.subscriptionPath) {
    let sub;
    try {
      sub = await fetchWithRetry(provider.baseUrl + q.subscriptionPath, { headers, ...t() });
    } catch (e) {
      throw new Error(`Koneksi ke gateway gagal: ${netErrorDetail(e)}`);
    }

    if (sub.ok) {
      const j = (await getJsonBody(sub)) || {};
      // Balance is best-effort: any failure just hides the USD hint.
      let balanceUsd = null;
      if (q.balancePath) {
        try {
          const bal = await fetchWithRetry(provider.baseUrl + q.balancePath, { headers, ...t() });
          if (bal.ok) balanceUsd = num(((await getJsonBody(bal)) || {}).balance_usd);
        } catch { /* best effort */ }
      }
      return {
        supported: true,
        source: 'native',
        unlimited: j.unlimited === true,
        tokensRemaining: num(j.tokens_remaining),
        tokensUsed: num(j.tokens_used),
        tokenLimit: num(j.token_limit),
        balanceUsd,
      };
    }

    if (sub.status !== 404 && sub.status !== 405) {
      const text = await sub.text().catch(() => '');
      throw new Error(`HTTP ${sub.status}: ${gatewayErrorText(text)}`);
    }
  }

  if (!q.billingFallback) throw new Error('Gateway tidak menyediakan info kuota');

  const [s2, u2] = await Promise.all([
    fetchWithRetry(provider.baseUrl + '/dashboard/billing/subscription', { headers, ...t() }),
    fetchWithRetry(provider.baseUrl + '/dashboard/billing/usage?start_date=1970-01-01&end_date=2038-01-01', { headers, ...t() }),
  ]);
  if (!s2.ok) {
    const text = await s2.text().catch(() => '');
    throw new Error(`HTTP ${s2.status}: ${gatewayErrorText(text)}`);
  }
  if (!u2.ok) {
    const text = await u2.text().catch(() => '');
    throw new Error(`HTTP ${u2.status}: ${gatewayErrorText(text)}`);
  }
  const sub2 = (await getJsonBody(s2)) || {};
  const usage2 = (await getJsonBody(u2)) || {};
  const hardLimit = num(sub2.hard_limit_usd) || 0;
  const usedUsd = (num(usage2.total_usage) || 0) / 100;
  const remainingUsd = Math.max(0, hardLimit - usedUsd);
  const unlimited = hardLimit >= 99999999;
  return {
    supported: true,
    source: 'billing',
    unlimited,
    tokensRemaining: unlimited ? null : Math.round(remainingUsd * q.quotaPerUnit),
    tokensUsed: Math.round(usedUsd * q.quotaPerUnit),
    tokenLimit: unlimited ? null : Math.round(hardLimit * q.quotaPerUnit),
    balanceUsd: remainingUsd,
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
      .then((r) => log(`warm-up ${p.baseUrl}: HTTP ${r.status}`))
      .catch((e) => log(`warm-up ${p.baseUrl} gagal:`, e.message));
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
      return sendJson(res, 400, { error: `Provider tidak dikenal: ${providerId}` });
    }
    const cacheKey = `${providerId}|${provider.baseUrl}|${key}`;
    const hit = modelsCache.get(cacheKey);
    if (hit && Date.now() - hit.at < MODELS_CACHE_TTL) {
      return sendJson(res, 200, { models: hit.ids, cached: true });
    }
    try {
      const ids = await fetchModels(key, provider);
      modelsCache.set(cacheKey, { at: Date.now(), ids });
      return sendJson(res, 200, { models: ids });
    } catch (e) {
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
    } catch (e) {
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
        return sendJson(res, 200, { ...hit.payload, cached: true });
      }
    }
    try {
      const payload = await fetchQuota(key, provider);
      quotaCache.set(cacheKey, { at: Date.now(), payload });
      return sendJson(res, 200, payload);
    } catch (e) {
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
      return sendJson(res, 500, { error: e.message });
    }
    log(`resolv.conf diperbarui: ${nameservers.join(', ')}`);
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
  try {
    fs.mkdirSync(config.workdir, { recursive: true });
  } catch (e) {
    // Shared storage vanished between config resolution and startup
    // (unmounted / permission revoked) — fall back to the private rootfs.
    config.workdir = path.join(HOME, 'work');
    fs.mkdirSync(config.workdir, { recursive: true });
    log(`workdir fallback /root/work (${e.message})`);
  }
  const srv = http.createServer((req, res) => {
    route(req, res).catch((e) => {
      log('route error:', e.message);
      try { sendJson(res, 500, { error: e.message }); } catch { /* headers sent */ }
    });
  });
  srv.listen(config.port, config.host, () => {
    log(`sachet-agent ${config.version} di ${config.host}:${config.port}`);
    for (const [id, p] of Object.entries(config.providers)) {
      log(`provider ${id}: ${p.baseUrl}`);
    }
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
    // The app pushes the system's (VPN-aware) DNS servers via POST /api/dns
    // once the server answers /health, and again on every network change.
    // Only seed the fallback file when nothing managed is present yet, so a
    // supervisor restart never clobbers a working VPN resolver config.
    let current = null;
    try { current = fs.readFileSync(config.resolvConfPath, 'utf8'); } catch { /* missing */ }
    if (!current || !current.includes('# managed by coding-sachet')) {
      const nameservers = writeResolvConf([]);
      log(`resolv.conf fallback: ${nameservers.join(', ')}`);
    }
  } catch (e) {
    log('resolv.conf tidak bisa diubah:', e.message);
  }

  try { require('node:dns').setDefaultResultOrder('ipv4first'); } catch { /* older node */ }

  if (WARMUP_ENABLED) warmUpGateways();
}

if (require.main === module) main();

module.exports = { main, start, route, fetchModels, fetchQuota, writeResolvConf, currentResolvServers, warmUpGateways, log };
