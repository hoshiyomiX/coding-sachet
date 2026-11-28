'use strict';
// verify.js — live model verification sweep (T-ML26), extracted from the
// former legacy agent loop (engine legacy dihapus — engine utama kini
// OpenCode, lihat AUDIT-OPENCODE.md §12). Route /api/verify-models tetap
// gateway-direct; tidak menyentuh engine chat.

const { config, providerById } = require('./config');
const { fetchWithRetry, fetchWithHeaderBudget, netErrorDetail } = require('./net');
const { acquire } = require('./ratelimit');
const log = require('./log');

const abortErr = () => Object.assign(new Error('dibatalkan'), { name: 'AbortError' });

/** Attach .catch to a cancel() promise — cancelling an errored stream
 *  rejects asynchronously, and an unhandled rejection kills the server
 *  (T-ML33). */
function cancelQuietly(cancelable) {
  try {
    const p = cancelable.cancel();
    if (p && typeof p.catch === 'function') p.catch(() => {});
  } catch { /* already closed */ }
}

/** Model billing tier dari katalog publik — T-ML49 (2026-11-20), bukti
 *  PROBE LANGSUNG dengan key free-plan (bukan lagi asumsi dokumen):
 *  - allowFree:false pada 8 model free/ → HTTP 402 "only available with
 *    a subscription" — prefix free/ TIDAK menjamin gratis lagi;
 *  - allowFree:true premium ($0.04-$1/1M) → 200 OK, billed dari dompet
 *    USD (pay-as-you-go/top-up);
 *  - allowFree:true free/ → 402 "top-up $5 to unlock 25M free tokens
 *    daily" untuk key yang belum unlock (keadaan KEY, bukan model —
 *    lihat chip sisa token).
 *  Tiga tier: 'free' (jatah harian, intrinsik DAN masih allowFree),
 *  'subscription' (allowFree:false — butuh paket langganan), 'topup'
 *  (premium, bayar dari saldo USD). null = tak dikenal. */
const catalogueTier = (e) => {
  if (!e) return null;
  if (e.allowFree === false) return 'subscription';
  const intrinsic = e.provider === 'Free' ||
    (typeof e.id === 'string' && e.id.startsWith('free/'));
  return intrinsic ? 'free' : 'topup';
};

// Backward-compat alias: badge free = tier 'free' saja (T-ML48→T-ML49).
const isCatalogueFree = (e) => catalogueTier(e) === 'free';

/** Fetch + parse the APInex public catalogue
 *  (apinex.bond/api/public/models): ONE unauthenticated GET returning
 *  per-model health + the intrinsic free tier — the relay's own liveness
 *  truth, zero chat tokens spent. */
async function fetchPublicCatalogue(provider, signal) {
  const res = await fetchWithRetry(
    provider.publicCatalogUrl,
    { signal: AbortSignal.any([signal, AbortSignal.timeout(20000)]) },
    { retries: 1, delays: [500] }
  );
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const j = await res.json().catch(() => null);
  if (!j || !Array.isArray(j.models)) throw new Error('bentuk katalog tidak dikenal');
  return j.models;
}

/** Fetch the AUTHENTICATED model catalogue (GET {baseUrl}/models with the
 *  bearer key — NgodeAI T-ML64): one request returns per-model status
 *  ('active'|'inactive' = maintenance) and pricing ({input,output} per 1M,
 *  IDR), which is everything the sweep needs — zero chat probes spent.
 *  Shape mirrors the live gateway (verified 2026-11-21): {data:[{id,
 *  status, endpoint, pricing:{input,output}, …}]}. */
async function fetchAuthCatalogue(apiKey, provider, signal) {
  const res = await fetchWithRetry(
    provider.baseUrl + '/models',
    { headers: { Authorization: `Bearer ${apiKey}` }, signal: AbortSignal.any([signal, AbortSignal.timeout(20000)].filter(Boolean)) },
    { retries: 1, delays: [500] }
  );
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const e = new Error(`HTTP ${res.status}: ${String(text).slice(0, 200)}`);
    e.status = res.status;
    throw e;
  }
  const j = await res.json().catch(() => null);
  if (!j || !Array.isArray(j.data)) throw new Error('bentuk katalog tidak dikenal');
  return j.data.filter((m) => m && typeof m.id === 'string');
}

/** NgodeAI per-model sweep facts out of one catalogue entry (T-ML64):
 *  ok = status 'active'; free = the gateway's OWN pricing is 0 (paid
 *  models also answer once the wallet has funds, so a live answer can
 *  never mark them free — unlike seek/NIM freeByProbe); tier = free for
 *  pricing 0, topup otherwise (no subscription tier on this gateway). */
function authCatalogueFacts(e) {
  if (!e) return { ok: null, free: null, tier: null };
  const active = typeof e.status === 'string' ? e.status === 'active' : null;
  const p = e.pricing && (typeof e.pricing.input === 'number' || typeof e.pricing.output === 'number')
    ? e.pricing
    : (e.pricing && e.pricing_usd && (typeof e.pricing_usd.input === 'number' || typeof e.pricing_usd.output === 'number') ? e.pricing_usd : null);
  if (!p) return { ok: active, free: null, tier: null };
  const free = Number(p.input) === 0 && Number(p.output) === 0;
  return { ok: active, free: active === false ? null : free, tier: free ? 'free' : 'topup' };
}

/** Read the probe response body until it proves the model is generating:
 *  for SSE the first `data: <payload>` line, for a plain JSON body (gateway
 *  ignored stream:true) any non-whitespace bytes. Resolves {ok,reason?};
 * the first-chunk budget [firstChunkMs] turns a hanging model into a
 *  clean "not answering" instead of a stalled sweep. */
async function readUntilAlive(res, firstChunkMs, signal) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    const finish = (v) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      cancelQuietly(reader);
      resolve(v);
    };
    const timer = setTimeout(() => finish({ ok: false, reason: 'timeout' }), firstChunkMs);
    const isSse = String(res.headers.get('content-type') || '').includes('event-stream');
    (async () => {
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) { finish({ ok: false, reason: 'stream berakhir tanpa data' }); return; }
          buffer += decoder.decode(value, { stream: true });
          let nl;
          while ((nl = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, nl).replace(/\r$/, '');
            buffer = buffer.slice(nl + 1);
            if (line.startsWith('data:')) {
              const payload = line.slice(5).trim();
              if (payload) { finish({ ok: true }); return; }
            }
          }
          // A gateway that ignored stream:true answers with a JSON body:
          // any real bytes already prove the model is serving.
          if (!isSse && buffer.trim()) { finish({ ok: true }); return; }
        }
      } catch (e) {
        if (signal && signal.aborted) reject(abortErr());
        else finish({ ok: false, reason: e.message });
      }
    })();
  });
}

/** Probe ONE model: a tiny streaming chat call, alive when the FIRST data
 *  chunk arrives — mirroring the app's actual chat path (the T-ML24 probe
 *  found two NIM models that only ever answer through SSE). Returns
 *  { ok, ms, reason? } with ok:null = cannot tell. Throws on a dead
 *  gateway path (no point painting every model red) and on 401/403 (the
 *  key itself is bad — the sweep must stop). */
async function probeModel({ apiKey, provider, model, signal }) {
  const t0 = Date.now();
  const body = JSON.stringify({
    model,
    messages: [{ role: 'user', content: 'ping' }],
    // Tiny on purpose: liveness only needs the model to START answering
    // (16 tokens leave reasoning models room to open their stream — the
    // same budget validateKey uses in the app).
    max_tokens: 16,
    stream: true,
  });
  let res;
  try {
    res = await fetchWithHeaderBudget(
      provider.baseUrl + '/chat/completions',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
        body,
        signal,
      },
      { timeoutMs: config.connectTimeoutMs, retries: 1, delays: [500] }
    );
  } catch (e) {
    if (signal && signal.aborted) throw abortErr();
    throw new Error(`Koneksi ke gateway gagal: ${netErrorDetail(e)}`);
  }
  const ms = Date.now() - t0;

  if (res.status === 401 || res.status === 403) {
    const err = new Error(`Key ditolak gateway (HTTP ${res.status})`);
    err.status = res.status;
    throw err;
  }
  if (!res.ok) {
    await res.text().catch(() => {}); // drain the error body
    if (res.status === 429) return { ok: null, ms, reason: 'HTTP 429' };
    return { ok: false, ms, reason: `HTTP ${res.status}` };
  }

  const alive = await readUntilAlive(res, config.verifyFirstChunkMs, signal);
  return { ok: alive.ok, ms: Date.now() - t0, ...(alive.reason ? { reason: alive.reason } : {}) };
}

/** Live verification sweep (T-ML26): decide for every model id whether it
 *  actually answers RIGHT NOW, emitting progress events. APInex takes the
 *  public-catalogue fast path (health → dot, intrinsic free tier → free
 *  badge, no tokens); every other provider (and APInex when the catalogue
 *  is unreachable) probes each model, paced by the SAME 15 RPM bucket as
 *  real chats so a sweep can never starve the user's own messages.
 *  Events: {type:'start',total} → {type:'result',model,ok,free,ms,reason?}
 *  → {type:'done',ok,dead,unknown}. ok/free are null = cannot tell. */
async function verifyModels({ apiKey, providerId, models, signal, emit }) {
  const provider = providerById(providerId);
  if (!provider) throw new Error(`Provider tidak dikenal: ${providerId}`);
  emit({ type: 'start', total: models.length });

  // ---- fast path: the APInex public catalogue ----
  if (provider.publicCatalogUrl) {
    try {
      const entries = await fetchPublicCatalogue(provider, signal);
      const byId = new Map(entries.map((e) => [e.id, e]));
      let ok = 0, dead = 0, unknown = 0;
      for (const m of models) {
        if (signal && signal.aborted) throw abortErr();
        const e = byId.get(m);
        const alive = e ? e.health === 'live' : null;
        if (alive === true) ok++; else if (alive === false) dead++; else unknown++;
        emit({
          type: 'result', model: m, ok: alive, ms: 0,
          tier: e ? catalogueTier(e) : null,
          free: e ? isCatalogueFree(e) : null,
          ...(alive === false && e ? { reason: `health=${e.health}` } : {}),
        });
      }
      log.inf('vrf', `verifikasi katalog ${providerId}: ${ok} hidup / ${dead} mati / ${unknown} tak ada di katalog`);
      emit({ type: 'done', ok, dead, unknown });
      return;
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      log.wrn('vrf', `verifikasi katalog ${providerId} gagal (${e.message}) — lanjut probe per model`);
    }
  }

  // ---- fast path: the AUTHENTICATED catalogue (NgodeAI, T-ML64) ----
  if (provider.catalogueSweep) {
    try {
      const entries = await fetchAuthCatalogue(apiKey, provider, signal);
      const byId = new Map(entries.map((e) => [e.id, e]));
      let ok = 0, dead = 0, unknown = 0;
      for (const m of models) {
        if (signal && signal.aborted) throw abortErr();
        const e = byId.get(m);
        const facts = authCatalogueFacts(e);
        if (facts.ok === true) ok++; else if (facts.ok === false) dead++; else unknown++;
        emit({
          type: 'result', model: m, ok: facts.ok, free: facts.free, tier: facts.tier, ms: 0,
          ...(facts.ok === false && e && e.status ? { reason: `status=${e.status}` } : {}),
        });
      }
      log.inf('vrf', `verifikasi katalog auth ${providerId}: ${ok} hidup / ${dead} mati / ${unknown} tak ada di katalog`);
      emit({ type: 'done', ok, dead, unknown });
      return;
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      log.wrn('vrf', `verifikasi katalog auth ${providerId} gagal (${e.message}) — lanjut probe per model`);
    }
  }

  // ---- probe path ----
  let ok = 0, dead = 0, unknown = 0;
  let lastConnectFail = null;
  for (const m of models) {
    if (signal && signal.aborted) throw abortErr();
    await acquire(providerId, signal); // same bucket as real chats (15 RPM)
    const t0 = Date.now();
    let r;
    try {
      r = await probeModel({ apiKey, provider, model: m, signal });
      // Any answer — even a 404 — proves the gateway path itself is up.
      lastConnectFail = null;
    } catch (e) {
      if (e && (e.name === 'AbortError' || e.status === 401 || e.status === 403)) throw e;
      if (lastConnectFail) {
        // Two connect-level failures in a row: the network/gateway path is
        // down — abort the sweep with the error instead of painting every
        // remaining model red.
        throw e;
      }
      lastConnectFail = e;
      r = { ok: false, ms: Date.now() - t0, reason: String(e.message).slice(0, 140) };
    }
    if (r.ok === true) ok++; else if (r.ok === false) dead++; else unknown++;
    const free = provider.freeByProbe ? (r.ok === true ? true : null) : null;
    emit({ type: 'result', model: m, ok: r.ok, free, tier: null, ms: Date.now() - t0, ...(r.reason ? { reason: r.reason } : {}) });
    log.dbg('vrf', `verifikasi ${providerId} ${m}: ${r.ok === true ? 'OK' : r.ok === false ? 'MATI' : 'TAK PASTI'} ${r.reason ? `(${r.reason})` : ''} ${Date.now() - t0}ms`);
  }
  log.inf('vrf', `verifikasi probe ${providerId}: ${ok} hidup / ${dead} mati / ${unknown} tak pasti`);
  emit({ type: 'done', ok, dead, unknown });
}

module.exports = { verifyModels, probeModel, authCatalogueFacts, isCatalogueFree, catalogueTier };
