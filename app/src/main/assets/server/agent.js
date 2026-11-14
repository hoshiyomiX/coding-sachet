'use strict';
// The agent loop: talks to the selected provider gateway (APInex or
// NVIDIA NIM — both OpenAI-compatible, SSE streaming) and executes the
// tool calls the model asks for.

const { config, providerById } = require('./config');
const path = require('path');
const fs = require('fs');
const { toolsFor, resolveWorkdir, readFile, todoItemsFromText } = require('./tools');
const { fetchWithRetry, fetchWithHeaderBudget, netErrorDetail } = require('./net');
const { acquire, peekWaitMs, pauseFor, abortableSleep } = require('./ratelimit');
const log = require('./log');

// Strict gateways (NIM, OpenAI) require every tool_call to carry an id,
// but some models stream tool calls without one. Ids are synthesized
// before anything is persisted or echoed, so a whole round stays
// consistent even across a mid-session model switch.
let synthCounter = 0;
function synthToolCallId() {
  synthCounter = (synthCounter + 1) % 1e9;
  return `call_s${Date.now().toString(36)}_${synthCounter}`;
}

// ---------- per-request JSON options (Settings → Konfigurasi JSON) ----------
// The app lets the owner configure the OpenAI-compatible request body
// (apinex.bond/developers/models/chat: "Extra OpenAI fields are generally
// forwarded"). These keys belong to the agent itself and can never be
// overridden from user config.
const RESERVED_OPTION_KEYS = ['model', 'messages', 'stream', 'tools', 'tool_choice', 'n', 'stream_options'];
const REASONING_EFFORTS = ['minimal', 'low', 'medium', 'high'];
const OPTIONS_MAX_JSON = 4096;

/** Validate + clean the user's request options. Invalid values are
 *  dropped (the request proceeds on gateway defaults) rather than
 *  failing the whole turn. Reserved keys are always stripped. */
function sanitizeOptions(options) {
  if (!options || typeof options !== 'object' || Array.isArray(options)) return {};
  const out = {};
  for (const [k, v] of Object.entries(options)) {
    if (RESERVED_OPTION_KEYS.includes(k)) continue;
    if (v === undefined || v === null) continue;
    out[k] = v;
  }
  const num = (k, min, max) => {
    if (out[k] === undefined) return;
    if (typeof out[k] !== 'number' || !Number.isFinite(out[k]) || out[k] < min || out[k] > max) delete out[k];
  };
  num('temperature', 0, 2);
  num('top_p', 0, 1);
  num('presence_penalty', -2, 2);
  num('frequency_penalty', -2, 2);
  if (out.max_tokens !== undefined && (!Number.isInteger(out.max_tokens) || out.max_tokens < 1)) delete out.max_tokens;
  if (out.seed !== undefined && !Number.isInteger(out.seed)) delete out.seed;
  if (out.stop !== undefined) {
    const arr = Array.isArray(out.stop) ? out.stop : [out.stop];
    const strs = arr.filter((s) => typeof s === 'string' && s).slice(0, 4);
    if (strs.length) out.stop = strs; else delete out.stop;
  }
  if (out.reasoning_effort !== undefined && !REASONING_EFFORTS.includes(out.reasoning_effort)) delete out.reasoning_effort;
  if (JSON.stringify(out).length > OPTIONS_MAX_JSON) return {};
  return out;
}

/** The chat/completions request body: agent-owned fields first, then the
 *  sanitized user options last (they may override max_tokens etc.).
 *  `usage` adds stream_options.include_usage — the OpenAI way to receive
 *  token counts on a streamed reply (T-ML29); dropped adaptively when a
 *  relay rejects the field with a 400. */
function buildBody({ model, messages, tools, provider, options, usage = true }) {
  const body = { model, messages, stream: true };
  if (usage) body.stream_options = { include_usage: true };
  // NIM asks integrations to set max_tokens explicitly; APInex uses its own
  // default, so the field is only sent when configured — and a user-set
  // max_tokens always wins over the provider default.
  if (provider.maxTokens && options.max_tokens === undefined) body.max_tokens = provider.maxTokens;
  if (tools && tools.length) {
    body.tools = tools;
    body.tool_choice = 'auto';
  }
  Object.assign(body, options);
  return body;
}

/** Wait the gateway asked for after a 429: `retry-after` (seconds or
 *  HTTP-date) first, then x-ratelimit-reset (unix seconds); clamped to a
 *  sane range so a hostile/broken header can never stall the agent. */
function retryAfterMs(res) {
  const ra = res.headers.get('retry-after');
  if (ra != null) {
    const s = Number(ra);
    if (Number.isFinite(s) && s >= 0) return clampWait(s * 1000);
    const date = Date.parse(ra);
    if (!Number.isNaN(date)) return clampWait(date - Date.now());
  }
  const reset = Number(res.headers.get('x-ratelimit-reset'));
  if (Number.isFinite(reset) && reset > 1e9) return clampWait(reset * 1000 - Date.now());
  return 5000;
}

function clampWait(ms) {
  return Math.min(65000, Math.max(500, ms));
}

// Bounded 429 retries: the client-side 15 RPM limiter keeps a solo device
// compliant, but the APInex bucket is per ACCOUNT (every key and IP of the
// account shares it) — a second device can still burn it. No streamed
// content exists before headers, so retrying a 429 is always safe.
const MAX_429_RETRIES = 2;

// Quick connect-phase retries inside ONE gateway call (fetchWithHeaderBudget,
// sub-second delays): they heal a transient VPN hiccup fast. When even these
// are exhausted the error is tagged connectRetry and the callGateway LADDER
// (T-ML37 P0-1) takes over with the owner-ordered 10 × 10 s patience.
const CONNECT_QUICK_RETRIES = 2;

/** cancel() on an ALREADY-ERRORED stream returns a promise REJECTED with
 *  the stored error (WHATWG Streams spec) — a try/catch around the CALL
 *  itself can never see that. The 2026-11-11 field crash: a mid-stream
 *  socket cut left exactly such a floating rejection, and the process-level
 *  unhandledRejection handler answered with exit(1) — one transient network
 *  blip killed the whole server ("Server keluar (kode 1)") and took the
 *  turn with it. Every body/reader cleanup goes through here now; the
 *  no-op catch is NOT optional. */
function cancelQuietly(cancelable) {
  try {
    const p = cancelable && typeof cancelable.cancel === 'function'
      ? cancelable.cancel()
      : null;
    if (p && typeof p.catch === 'function') p.catch(() => {});
  } catch { /* already closed */ }
}

/** One streamed call to the provider. Returns the assembled assistant turn. */
async function callGatewayOnce({ apiKey, providerId, model, messages, tools, options, signal, onDelta }) {
  const provider = providerById(providerId) || config.providers.apinex;
  const opts = sanitizeOptions(options);
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) throw new Error('aborted');
    signal.addEventListener('abort', onAbort, { once: true });
  }
  const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);

  let res;
  try {
    // Adaptive usage field (T-ML29): a relay that rejects unknown fields
    // answers 400 — that specific failure is retried once WITHOUT the
    // field before any error reaches the user.
    let sendUsage = true;
    let body = JSON.stringify(buildBody({ model, messages, tools, provider, options: opts, usage: sendUsage }));
    log.dbg('agt', `gateway ${provider.label} ${model} msgs=${messages.length}${tools && tools.length ? ` tools=${tools.length}` : ''} est=${estimateTokens(messages)}tok`);
    try {
      for (let attempt = 0; ; attempt++) {
        // Surface a hint before a long limiter wait so the UI never looks dead.
        const hint = peekWaitMs(providerId);
        if (hint > 500 && onDelta) onDelta({ type: 'ratelimit', waitMs: hint });
        await acquire(providerId, controller.signal);
        res = await fetchWithHeaderBudget(
          provider.baseUrl + '/chat/completions',
          {
            method: 'POST',
            signal: controller.signal,
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${apiKey}`,
            },
            body,
          },
          // Fresh per-attempt budget for the connect/headers phase only:
          // a cold VPN path (first DNS + TLS through the tunnel — the NIM
          // "no response" field report) heals with a retry instead of a
          // silent spinner up to the 5-minute request timeout. Once headers
          // arrive the stream runs under the global timer alone.
          { timeoutMs: config.connectTimeoutMs, retries: CONNECT_QUICK_RETRIES, delays: [500, 1500] },
          // T-ML37 P0-2: a connect retry is a REAL gateway arrival — the
          // 2026-11-12 field 429s were self-inflicted (16-17 real arrivals
          // vs 13-14 counted in the 60 s window before each 429, because
          // acquire() only ran once per turn). The retry now acquires a
          // 15 RPM slot of its own before its fetch fires.
          async (attemptNo, err) => {
            if (onDelta) onDelta({ type: 'netretry', attempt: attemptNo, max: CONNECT_QUICK_RETRIES + 1, provider: provider.label, detail: netErrorDetail(err).slice(0, 120) });
            const hint = peekWaitMs(providerId);
            if (hint > 500 && onDelta) onDelta({ type: 'ratelimit', waitMs: hint });
            await acquire(providerId, controller.signal);
          }
        );
        if (res.status === 400 && sendUsage) {
          await res.text().catch(() => {}); // drain the error body
          sendUsage = false;
          body = JSON.stringify(buildBody({ model, messages, tools, provider, options: opts, usage: false }));
          log.wrn('agt', 'HTTP 400 — gateway menolak stream_options; coba ulang tanpa field usage');
          continue; // paced again through the limiter
        }
        if (res.ok || res.status !== 429 || attempt >= MAX_429_RETRIES) break;
        await res.text().catch(() => {}); // drain the error body
        const waitMs = retryAfterMs(res);
        log.wrn('agt', `HTTP 429 dari ${provider.label} — tunggu ${Math.round(waitMs / 1000)}s (percobaan ${attempt + 1}/${MAX_429_RETRIES})`);
        pauseFor(providerId, waitMs); // later requests wait the pause out too
        if (onDelta) onDelta({ type: 'ratelimit', waitMs, retry: attempt + 1 });
        await abortableSleep(waitMs, controller.signal);
      }
    } catch (e) {
      if (e.name === 'AbortError') {
        if (signal && signal.aborted) throw new Error('dibatalkan');
        if (e.connectTimeoutMs) {
          // Named gateway so the user can tell APInex and NIM apart in the
          // error bubble (a field report blamed NIM while the failing
          // request was actually on the APInex provider).
          const err = new Error(
            `gateway timeout ke ${provider.label} (koneksi ${Math.round(e.connectTimeoutMs / 1000)}s × ${CONNECT_QUICK_RETRIES + 1} percobaan — cek jaringan/VPN)`
          );
          err.connectRetry = true; // T-ML37 P0-1: the wrapper ladder retries this
          throw err;
        }
        throw new Error(`gateway timeout (${config.requestTimeoutMs / 1000}s)`);
      }
      const err = new Error(`Koneksi ke gateway ${provider.label} gagal: ${netErrorDetail(e)}`);
      err.connectRetry = true; // T-ML37 P0-1: the wrapper ladder retries this
      throw err;
    }

    if (!res.ok) {
      const text = await res.text().catch(() => '');
      let msg = text.slice(0, 500);
      try { msg = JSON.parse(text).error?.message || msg; } catch { /* not JSON */ }
      log.err('agt', `HTTP ${res.status} dari ${provider.label}:`, String(msg).slice(0, 160));
      // 429 carries a provider-specific free-tier hint instead of a bare status line.
      const err = new Error(
        res.status === 429 ? `HTTP ${res.status}: ${msg} — ${provider.rateLimitHint}` : `HTTP ${res.status}: ${msg}`
      );
      err.status = res.status;
      throw err;
    }

    // ---- parse the SSE stream ----
    const turn = { content: '', reasoning: '', toolCalls: [], finishReason: null, usage: null };
    let done = false;

    try {
      const decoder = new TextDecoder();
      let buffer = '';

      for await (const chunk of res.body) {
        buffer += decoder.decode(chunk, { stream: true });
        let nl;
        while ((nl = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, nl).replace(/\r$/, '');
          buffer = buffer.slice(nl + 1);
          if (!line.startsWith('data:')) continue;
          const payload = line.slice(5).trim();
          if (!payload) continue;
          if (payload === '[DONE]') { done = true; break; }

          let j;
          try { j = JSON.parse(payload); } catch { continue; }
          if (j.error) {
            const ge = new Error(j.error.message || 'gateway error');
            ge.gatewayError = true; // rethrown untouched by the stream catch
            throw ge;
          }
          // The usage chunk (stream_options.include_usage, T-ML29): it
          // carries NO choices — read it before the choices guard below.
          if (j.usage && typeof j.usage === 'object') {
            const pickNum = (v) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : null);
            turn.usage = {
              promptTokens: pickNum(j.usage.prompt_tokens),
              completionTokens: pickNum(j.usage.completion_tokens),
              reasoningTokens: pickNum(j.usage.completion_tokens_details && j.usage.completion_tokens_details.reasoning_tokens),
              cachedTokens: pickNum(j.usage.prompt_tokens_details && j.usage.prompt_tokens_details.cached_tokens),
              totalTokens: pickNum(j.usage.total_tokens),
            };
          }
          const ch = j.choices && j.choices[0];
          if (!ch) continue;
          if (ch.finish_reason) turn.finishReason = ch.finish_reason;

          const d = ch.delta || {};
          if (typeof d.reasoning_content === 'string' && d.reasoning_content) {
            turn.reasoning += d.reasoning_content;
            if (onDelta) onDelta({ type: 'think', delta: d.reasoning_content });
          }
          if (typeof d.reasoning === 'string' && d.reasoning) {
            turn.reasoning += d.reasoning;
            if (onDelta) onDelta({ type: 'think', delta: d.reasoning });
          }
          if (typeof d.content === 'string' && d.content) {
            turn.content += d.content;
            if (onDelta) onDelta({ type: 'text', delta: d.content });
          }
          if (Array.isArray(d.tool_calls)) {
            for (const tc of d.tool_calls) {
              const i = tc.index ?? 0;
              if (!turn.toolCalls[i]) turn.toolCalls[i] = { id: '', name: '', args: '' };
              if (tc.id) turn.toolCalls[i].id = tc.id;
              if (tc.function) {
                if (tc.function.name) turn.toolCalls[i].name += tc.function.name;
                if (typeof tc.function.arguments === 'string') turn.toolCalls[i].args += tc.function.arguments;
              }
            }
          }
        }
        if (done) break;
      }
    } catch (e) {
      // The stream died mid-read (socket cut → undici "terminated", or the
      // request timer aborting a stalled stream). What happens next depends
      // on how much of the round the user already SAW (T-ML33).
      if (done) {
        // [DONE] arrived — the round is complete whatever cleanup says.
        log.wrn('agt', `stream ${provider.label} selesai dengan sisa error (${e.message}) — putaran tetap dipakai`);
        turn.toolCalls = turn.toolCalls.filter((tc) => tc.name || tc.id);
        return turn;
      }
      if (signal && signal.aborted) throw new Error('dibatalkan'); // user stop: final
      if (e.gatewayError) throw e; // the gateway itself reported an error
      const sawContent = turn.content !== '' || turn.reasoning !== '';
      if (!sawContent) {
        // Nothing visible was emitted — tool-call fragments are assembled
        // silently and only surface AFTER a round completes, so a fresh
        // attempt can never duplicate anything the user saw.
        const err = new Error(`stream ${provider.label} terputus sebelum ada data: ${e.message}`);
        err.streamRetry = true;
        throw err;
      }
      throw new Error(`Koneksi ke ${provider.label} terputus di tengah balasan (${e.message}) — sebagian balasan tidak sempat tersimpan, kirim pesan lagi untuk melanjutkan`);
    } finally {
      cancelQuietly(res.body);
    }

    turn.toolCalls = turn.toolCalls.filter((t) => t.name || t.id);
    return turn;
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
  }
}

// Bounded transparent retries for streams that die before any visible
// output (T-ML33). One is enough: the 2026-11-11 field failure class is a
// single VPN-path hiccup, not a sustained outage.
const MAX_STREAM_RETRIES = 1;

/** Stream-resilient front door — TWO retry classes, neither of which can
 *  duplicate anything the user saw:
 *
 *  * streamRetry (T-ML33): the stream died BEFORE any visible output →
 *    ONE quick retry, 1 s apart.
 *
 *  * connectRetry (T-ML37 P0-1, owner order "10x10s retry", 2026-11-12):
 *    the connect phase exhausted its quick in-fetch retries — a dead
 *    VPN/radio path. A minimized window kills that path for 15-60 s in
 *    the field while the quick retries only tolerate ~13 s (turn 89 of
 *    the 2026-11-12 log: 3 attempts in 13 s, then a 2.47 M-token run
 *    died without its final answer). The WHOLE gateway call is retried
 *    up to config.gatewayConnectRetries times, config.gatewayRetryDelayMs
 *    apart: each round gets a FRESH request timer and goes through the
 *    15 RPM limiter again (a real arrival, T-ML37 P0-2), so a mid-run
 *    chat survives minutes of dead path instead of seconds.
 *
 *  A user stop during any paced wait aborts immediately instead of
 *  retrying a cancelled turn. */
async function callGateway(params) {
  let streamTries = 0;
  let connectTries = 0;
  let firstConnectFailAt = 0;
  for (;;) {
    try {
      return await callGatewayOnce(params);
    } catch (e) {
      const provider = providerById(params.providerId) || config.providers.apinex;
      if (e && e.streamRetry && streamTries < MAX_STREAM_RETRIES) {
        streamTries++;
        log.wrn('agt', `stream dari ${provider.label} terputus sebelum ada data (${e.message}) — coba ulang ${streamTries}/${MAX_STREAM_RETRIES}`);
        if (params.onDelta) {
          params.onDelta({ type: 'netretry', attempt: streamTries, max: MAX_STREAM_RETRIES, provider: provider.label, detail: e.message.slice(0, 120) });
        }
        await abortableSleep(1000, params.signal);
        continue;
      }
      if (e && e.connectRetry && connectTries < config.gatewayConnectRetries) {
        if (connectTries === 0) firstConnectFailAt = Date.now();
        connectTries++;
        log.wrn('agt', `gateway ${provider.label} tidak terjangkau (${e.message}) — coba ulang ${connectTries}/${config.gatewayConnectRetries} dalam ${Math.round(config.gatewayRetryDelayMs / 1000)}s`);
        if (params.onDelta) {
          params.onDelta({ type: 'netretry', attempt: connectTries, max: config.gatewayConnectRetries, provider: provider.label, detail: e.message.slice(0, 120) });
        }
        await abortableSleep(config.gatewayRetryDelayMs, params.signal);
        continue;
      }
      if (e && e.connectRetry && connectTries > 0) {
        // The ladder is spent and the path is still dead — say so instead
        // of implying only three attempts were ever made.
        const totalS = Math.max(1, Math.round((Date.now() - firstConnectFailAt) / 1000));
        throw new Error(`${e.message} — sudah dicoba ulang ${connectTries}× selama ~${totalS}s, jalur masih mati`);
      }
      throw e;
    }
  }
}

/**
 * Convert our wire messages into OpenAI chat format.
 *
 * The history can contain turns served by ANY model/provider — users
 * switch mid-session — and interrupted rounds (stop pressed while tools
 * were still running). Strict gateways reject such history wholesale, so
 * the conversion heals it:
 *   * empty tool_call ids get synthesized ones (paired positionally with
 *     the tool results that follow the assistant message),
 *   * a tool round missing results is folded into a plain assistant
 *     message (dangling tool_calls are a hard 400 on NIM/OpenAI),
 *   * orphan tool results (their assistant message is gone) are dropped.
 */

// ---------- token economy (T-ML29, from the T-ML28 usage audit) ----------

/** What the MODEL sees of a tool output: a head+tail window with a note.
 *  The app's persisted history and the UI keep the full text — only the
 *  gateway-bound copy is trimmed (fix #2: one 256 KB read_file used to be
 *  resent whole on EVERY later round). */
function forModel(text) {
  if (typeof text !== 'string') return String(text ?? '');
  const budget = config.toolModelBudgetChars;
  if (budget <= 0 || text.length <= budget) return text;
  const head = Math.ceil(budget / 2);
  const tail = budget - head;
  const hidden = text.length - budget;
  return text.slice(0, head) +
    `\n…[${hidden} karakter di tengah dipangkas hemat token — pakai read_lines/grep untuk bagian tertentu]…\n` +
    text.slice(-tail);
}

/** Rough token estimate of the wire messages. T-ML39 (audit §6.4 R1,
 *  owner order sesi 27): divisor 4 → 2 — the field-measured ratio on
 *  glm-5.3-flash code+ID content is 2.15 chars/token (1.86× off the old
 *  assumption), so the old /4 made the 16 K compact threshold fire at
 *  ~29.6 K REAL tokens and the context sat at 28-34 K per round. /2 is
 *  the conservative calibration: 16 K estimated ≈ 16 K actual. */
function estimateTokens(msgs) {
  let ch = 0;
  for (const m of msgs) {
    ch += (m.content || '').length;
    if (Array.isArray(m.tool_calls)) {
      for (const c of m.tool_calls) {
        ch += (c.function && c.function.name ? c.function.name.length : 0) +
              (c.function && c.function.arguments ? c.function.arguments.length : 0);
      }
    }
  }
  return Math.ceil(ch / 2);
}

/** Fold OLD tool results (all but the newest `keep`) into one-line
 *  summaries until the prompt estimate fits the threshold — the quadratic
 *  resend otherwise burns the account on long runs (T-ML28 counterfactual:
 *  24 apt rounds ≈ 6.9 M tokens). `folded` tracks indexes so already-folded
 *  entries are never re-folded; only the gateway-bound copy changes. */
function compactOldTools(openai, folded, thresholdTokens, keep) {
  keep = Math.max(1, keep | 0);
  while (estimateTokens(openai) > thresholdTokens) {
    const toolIdx = [];
    openai.forEach((m, i) => { if (m.role === 'tool') toolIdx.push(i); });
    const candidates = toolIdx.slice(0, Math.max(0, toolIdx.length - keep));
    const target = candidates.find((i) => !folded.has(i));
    if (target === undefined) break; // everything foldable is folded already
    const m = openai[target];
    folded.add(target);
    m.content = `[output ${m.name || 'tool'} dipangkas hemat token — asli ${m.content.length}ch]`;
  }
}

/** T-ML40 baseline elision (audit §6.7): shrink every tool result OLDER
 *  than the newest `keep` to a short head summary, on EVERY round — the
 *  16 K threshold alone never fired on mid-length runs (field session
 *  2026-11-14: 19 putaran × full-history resend = 98,5% prompt tokens).
 *  Results smaller than contextElideMinChars stay untouched (eliding a
 *  13-char "exit=0" into a 200-char marker would COST tokens). Idempotent
 *  via the `elided` index set; only the gateway-bound copy changes — the
 *  app history keeps everything. */
function elideOldTools(openai, elided, keep) {
  keep = Math.max(1, keep | 0);
  const toolIdx = [];
  openai.forEach((m, i) => { if (m.role === 'tool') toolIdx.push(i); });
  const candidates = toolIdx.slice(0, Math.max(0, toolIdx.length - keep));
  for (const i of candidates) {
    if (elided.has(i)) continue;
    const m = openai[i];
    const original = String(m.content || '');
    if (original.length <= config.contextElideMinChars) continue;
    elided.add(i);
    const head = original.slice(0, config.contextElideHeadChars).replace(/\s+/g, ' ').trim();
    m.content = `[${m.name || 'tool'} dipangkas hemat token — asli ${original.length}ch] ${head}…`;
  }
}

/** T-ML40 (audit §6.4 R3, the +150 tok/putaran drift): once ALL of an
 *  assistant round's tool results are elided, its tool_call arguments
 *  stop earning their keep too — each old round kept ~150-300 ch of
 *  command JSON alive forever. Compacted to a VALID-JSON {"ringkas":…}
 *  wrapper (strict gateways must never see a truncated JSON string);
 *  the id/name pairing stays intact, so history healing is unaffected.
 *  Idempotent via the `done` index set. */
function compactOldAssistantArgs(openai, elided, done) {
  for (let a = 0; a < openai.length; a++) {
    const m = openai[a];
    if (m.role !== 'assistant' || !Array.isArray(m.tool_calls) || !m.tool_calls.length) continue;
    if (done.has(a)) continue;
    let j = a + 1;
    let saw = false;
    let allElided = true;
    while (j < openai.length && openai[j].role === 'tool') {
      saw = true;
      if (!elided.has(j)) { allElided = false; break; }
      j++;
    }
    if (!saw || !allElided) continue;
    done.add(a);
    for (const c of m.tool_calls) {
      const args = c.function && c.function.arguments;
      if (typeof args === 'string' && args.length > 140) {
        c.function.arguments = JSON.stringify({ ringkas: args.slice(0, 100) });
      }
    }
  }
}

/** T-ML39 TODO protocol: the plan file the coding agent is instructed to
 *  keep in its workdir (owner order sesi 27: protocol = TODO file —
 *  "agent menulis daftar langkah ke file kerja di awal, mencentang tiap
 *  langkah, harness menagih item yang belum dicentang"). Checked lines
 *  ("- [x]") and struck-through skips count as resolved; only "- [ ]"
 *  lines are outstanding. */
const TODO_FILENAME = 'RENCANA.md';

/** T-ML40 persistent project memory: the cross-session facts file in the
 *  session workdir. Injected into the system prompt at run start, nudged
 *  (once) at the end of long runs that never touched it. */
const MEMORY_FILENAME = 'MEMORI.md';

/** Read the workdir's RENCANA.md and return its unchecked step lines.
 *  Pure read: { exists:false } when absent or unreadable — enforcement
 *  never turns a missing plan into an error. Exported for tests. */
function readTodoUnchecked(workdir) {
  if (typeof workdir !== 'string' || !workdir) return { exists: false, unchecked: [] };
  const r = readFile(path.join(workdir, TODO_FILENAME));
  if (!r.ok) return { exists: false, unchecked: [] };
  const unchecked = todoItemsFromText(r.content)
    .filter((it) => !it.checked)
    .map((it) => it.text || '(tanpa teks)');
  return { exists: true, unchecked };
}

/** Head of the workdir's MEMORI.md (capped), or null when absent/empty —
 *  the cross-session context the next run in the same folder starts from
 *  (T-ML40: "persistent memory"). Exported for tests. */
function readProjectMemory(workdir) {
  if (typeof workdir !== 'string' || !workdir) return null;
  const r = readFile(path.join(workdir, config.memoryFilename));
  if (!r.ok || r.binary) return null;
  const text = String(r.content || '').trim();
  if (!text) return null;
  return text.length > config.memoryInjectMaxChars
    ? text.slice(0, config.memoryInjectMaxChars) + '\n…(dipangkas — baca MEMORI.md utuh dengan read_lines)'
    : text;
}

/** True when MEMORI.md is missing or was not written during THIS run —
 *  the memory nudge fires at most once per run, wire-only, mirroring the
 *  todo-nudge. Exported for tests. */
function memoryStale(workdir, runStartMs) {
  if (typeof workdir !== 'string' || !workdir) return false;
  try {
    const st = fs.statSync(path.join(workdir, config.memoryFilename));
    return st.mtimeMs < runStartMs;
  } catch { return true; } // missing file → stale
}

function toOpenAiMessages(messages) {
  const out = [];
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    if (m.role === 'system') {
      out.push({ role: 'system', content: m.content || '' });
    } else if (m.role === 'user') {
      out.push({ role: 'user', content: m.content || '' });
    } else if (m.role === 'assistant') {
      const calls = Array.isArray(m.toolCalls) ? m.toolCalls : [];
      if (calls.length) {
        // The tool results of a round follow positionally, right after
        // the assistant message that requested them.
        const results = [];
        let j = i + 1;
        while (j < messages.length && messages[j].role === 'tool') {
          results.push(messages[j]);
          j++;
        }
        if (results.length >= calls.length) {
          const wireCalls = calls.map((c) => ({
            id: c.id || synthToolCallId(),
            type: 'function',
            function: {
              name: c.name,
              arguments: typeof c.args === 'string' ? c.args : JSON.stringify(c.args ?? {}),
            },
          }));
          out.push({ role: 'assistant', content: m.content || '', tool_calls: wireCalls });
          for (let k = 0; k < wireCalls.length; k++) {
            const r = results[k];
            out.push({
              role: 'tool',
              tool_call_id: wireCalls[k].id,
              name: r.name || wireCalls[k].function.name,
              content: forModel(r.content || ''),
            });
          }
        } else {
          // Interrupted round: send it as plain text so the next model
          // accepts the history instead of rejecting the whole request.
          out.push({ role: 'assistant', content: m.content || '' });
        }
        i = j - 1; // the tool run is consumed
      } else {
        out.push({ role: 'assistant', content: m.content || '' });
      }
    } else if (m.role === 'tool') {
      // Orphan tool result — dropped (see the doc block above).
      continue;
    }
  }
  return out;
}

/**
 * Run the full agent loop, emitting events through `emit`.
 * `messages` is the app-owned history in our wire format; the returned
 * list of new messages lets the app extend its persisted history.
 */
async function runAgent({ apiKey, providerId, model, messages, options, signal, emit, systemPrompt, workdir }) {
  const runStartMs = Date.now();
  const openai = toOpenAiMessages(messages);

  // Per-session project folder (T-ML28: "one project per folder"): the
  // bash tool's cwd — and the workdir the model is told about — follow the
  // session's project when the app pins one via the request body. Invalid
  // or missing values keep the server default; a chat never fails over it.
  const wd = resolveWorkdir(workdir);
  if (wd) log.inf('agt', `workdir sesi: ${wd}`);
  const runWorkdir = wd || config.workdir;

  // T-ML40 persistent project memory: the head of MEMORI.md rides the
  // system prompt, so the next run in the same folder starts from the
  // facts the previous one wrote (structure, decisions, gotchas) instead
  // of re-exploring from zero (field 2026-11-14: turns 2-5 = 17,2 K tok
  // of pure re-discovery).
  const memory = readProjectMemory(runWorkdir);
  if (memory) {
    log.inf('agt', `memori proyek dimuat: MEMORI.md ${memory.length}ch`);
    systemPrompt = `${systemPrompt || ''}\n\nINGATAN PROYEK (MEMORI.md, ditulis sesi sebelumnya di folder kerja ini):\n${memory}\n(Akhir ingatan — perbarui MEMORI.md bila fakta sudah berubah.)`;
  }
  if (systemPrompt) openai.unshift({ role: 'system', content: systemPrompt });

  const runTools = toolsFor(runWorkdir);
  const runToolByName = Object.fromEntries(runTools.map((t) => [t.name, t]));
  const openaiTools = runTools.map((t) => ({
    type: 'function',
    function: { name: t.name, description: t.description, parameters: t.parameters },
  }));

  const added = [];   // messages the app should persist
  let turn = 0;
  let todoNudges = 0; // T-ML39: TODO reminders already spent this run
  let memoryNudges = 0; // T-ML40: MEMORI.md reminders already spent this run

  // Token economy (T-ML29): gateway-reported usage of every round feeds
  // the per-run usage events the app displays. There is NO budget stop
  // anymore (T-ML32, owner order: APInex grants 20 M free tokens/day —
  // the 100 k/turn guard only cut healthy long runs).
  let usedTokens = 0;
  const folded = new Set(); // indexes of openai entries already compacted
  const elided = new Set(); // T-ML40: indexes already baseline-elided
  const argsDone = new Set(); // T-ML40: assistant rounds whose args are compacted

  // NO turn ceiling (T-ML34, owner order: "hapus semua limit pada agent
  // terkecuali 15 RPM" — field evidence 2026-11-11 21:23: a healthy run
  // was cut at putaran 24/24 while the model still asked for tools,
  // finish=tool_calls). The loop runs until the model stops calling
  // tools, the user stops it, or the app disconnects (server.js wires
  // req 'close' → AbortSignal, and callGateway rejects on abort). The
  // ONLY agent-side limit left is the client-side 15 RPM rate limiter
  // (ratelimit.js — hardcoded, owner-ordered).
  while (true) {
    turn++;
    emit({ type: 'turn', n: turn });
    log.dbg('agt', `putaran ${turn}`);

    // Token economy, two tiers (T-ML29 + T-ML40 §6.7): baseline elision
    // shrinks every tool result older than the newest keep to a head
    // summary on EVERY round; the threshold fold above 16 K remains the
    // second line of defense for extreme runs. Both run before the
    // request, so round 1 of a long RESENT history gets the same
    // protection as a long fresh run.
    elideOldTools(openai, elided, config.contextCompactKeep);
    compactOldAssistantArgs(openai, elided, argsDone);
    compactOldTools(openai, folded, config.contextCompactTokens, config.contextCompactKeep);

    const gwT0 = Date.now();
    const t = await callGateway({
      apiKey, providerId, model, messages: openai, tools: openaiTools, options, signal,
      onDelta: (e) => emit(e),
    });
    log.dbg('agt', `gateway selesai ${Date.now() - gwT0}ms content=${t.content.length}ch reasoning=${t.reasoning.length}nch toolCalls=${t.toolCalls.length} finish=${t.finishReason || '?'}`);

    // Gateways that omit tool_call ids get synthesized ones here, before
    // the round is persisted or echoed — the ids then survive in history.
    for (const c of t.toolCalls) {
      if (!c.id) c.id = synthToolCallId();
    }

    const wantsTools = t.toolCalls.length > 0 && t.finishReason !== 'stop';

    // The assistant message, exactly as the app should store it.
    const assistantMsg = {
      role: 'assistant',
      content: t.content,
      ...(t.reasoning ? { thinking: t.reasoning } : {}),
      ...(wantsTools ? {
        toolCalls: t.toolCalls.map((c) => ({
          id: c.id, name: c.name,
          args: safeJson(c.args),
        })),
      } : {}),
    };
    emit({ type: 'message', msg: assistantMsg });
    added.push(assistantMsg);

    // Usage accounting (fix #1): the gateway-reported counts of THIS round,
    // plus the running total — the app shows it as the session meter.
    if (t.usage) {
      const add = t.usage.totalTokens ??
        ((t.usage.promptTokens ?? 0) + (t.usage.completionTokens ?? 0));
      if (add > 0) usedTokens += add;
      log.inf('agt', `usage ptok=${t.usage.promptTokens ?? '?'} ctok=${t.usage.completionTokens ?? '?'}` +
        `${t.usage.reasoningTokens != null ? ` rtok=${t.usage.reasoningTokens}` : ''}` +
        `${t.usage.cachedTokens != null ? ` cache=${t.usage.cachedTokens}` : ''}` +
        ` total=${add} run=${usedTokens}`);
      emit({ type: 'usage', ...t.usage, runTotal: usedTokens });
    }

    if (!wantsTools) {
      // T-ML39 TODO enforcement: a finished-looking answer bounces back
      // while RENCANA.md still has unchecked steps — the harness "menagih"
      // the plan the model itself wrote. Bounded by config.todoNudgeMax
      // (a stubborn model can never trap the run); the reminder rides as
      // a user-role message on the WIRE ONLY (never in `added` — the app
      // history stays exactly what the human and the model produced).
      if (todoNudges < config.todoNudgeMax) {
        const todo = readTodoUnchecked(runWorkdir);
        if (todo.exists && todo.unchecked.length > 0) {
          todoNudges++;
          log.inf('agt', `todo-nudge ${todoNudges}/${config.todoNudgeMax}: ${todo.unchecked.length} langkah belum dicentang`);
          emit({ type: 'todonudge', n: todoNudges, max: config.todoNudgeMax, remaining: todo.unchecked.length });
          openai.push({ role: 'assistant', content: t.content || '' });
          openai.push({
            role: 'user',
            content: '[pengingat harness] RENCANA.md masih memiliki ' +
              `${todo.unchecked.length} langkah belum dicentang:\n` +
              todo.unchecked.map((l) => `- ${l}`).join('\n') +
              '\nTandai dengan tool todo (aksi "centang", atau "coret" bila jadi tak relevan), ' +
              'lalu berikan jawaban akhirmu.',
          });
          continue;
        }
      }
      // T-ML40 memory enforcement: a long run that never touched
      // MEMORI.md gets ONE wire-only reminder to write the cross-session
      // facts down — same shape and guarantees as the todo-nudge.
      if (memoryNudges < config.memoryNudgeMax && turn >= config.memoryNudgeMinTurns && memoryStale(runWorkdir, runStartMs)) {
        memoryNudges++;
        log.inf('agt', `memory-nudge ${memoryNudges}/${config.memoryNudgeMax}: MEMORI.md belum ditulis run ini`);
        emit({ type: 'memorynudge', n: memoryNudges, max: config.memoryNudgeMax });
        openai.push({ role: 'assistant', content: t.content || '' });
        openai.push({
          role: 'user',
          content: '[pengingat harness] Run ini sudah ' + turn + ' putaran. Tulis atau perbarui MEMORI.md di folder kerja ' +
            'dengan fakta yang berguna untuk sesi berikutnya (struktur proyek, keputusan, perintah verifikasi, ' +
            'jebakan yang ditemui) — pakai write_file, ringkas saja — lalu berikan jawaban akhirmu.',
        });
        continue;
      }
      emit({ type: 'done', turns: turn });
      return added;
    }

    // Record the assistant turn (with tool_calls) for the next round.
    openai.push({
      role: 'assistant',
      content: t.content || '',
      tool_calls: t.toolCalls.map((c) => ({
        id: c.id,
        type: 'function',
        function: { name: c.name, arguments: c.args || '{}' },
      })),
    });

    for (const call of t.toolCalls) {
      const args = safeJson(call.args);
      emit({ type: 'tool', id: call.id, name: call.name, args });

      let result;
      const tool = runToolByName[call.name];
      const toolT0 = Date.now();
      if (!tool) {
        log.wrn('agt', `tool tidak dikenal: ${call.name}`);
        result = { ok: false, output: `Tool tidak dikenal: ${call.name}` };
      } else {
        try {
          result = await tool.run(args);
        } catch (e) {
          log.err('agt', `tool ${call.name} error:`, e.message);
          result = { ok: false, output: `tool error: ${e.message}` };
        }
      }
      const output = typeof result?.output === 'string'
        ? result.output
        : JSON.stringify(result ?? {}).slice(0, config.bashMaxOutput);

      log.inf('agt', `tool ${call.name} ${result?.ok ? 'OK' : 'GAGAL'} ${Date.now() - toolT0}ms out=${output.length}ch`);

      emit({ type: 'toolend', id: call.id, ok: !!result?.ok, output: output.slice(0, 4000) });

      const toolMsg = { role: 'tool', toolCallId: call.id, name: call.name, content: output };
      emit({ type: 'toolresult', msg: toolMsg });
      added.push(toolMsg);
      // The gateway only ever sees the windowed copy (fix #2); the app's
      // persisted history keeps the full output for the UI.
      openai.push({ role: 'tool', tool_call_id: call.id, name: call.name, content: forModel(output) });
    }
  }
  // Unreachable: the loop above only exits via return (model stopped
  // asking for tools) or a thrown error (user stop / disconnect / gateway
  // failure) — kept explicit so a future edit cannot fall through silently.
  return added;
}

function safeJson(s) {
  if (typeof s !== 'string' || !s.trim()) return {};
  try { return JSON.parse(s); } catch { return { _raw: s }; }
}

// ---------- live model verification (T-ML26) ----------
// Owner order: "Providers.kt mengecek actual bukan dari membaca hardcoded
// list" — the app's model dots must reflect what the gateway answers RIGHT
// NOW, never a set baked into the APK at build time (the NIM catalogue
// fluctuates daily: the T-ML24 probe found one model dead and another
// revived within 24 h; the APInex allowFree set drifted 35 → 34 a day
// after it was baked).

const abortErr = () => Object.assign(new Error('dibatalkan'), { name: 'AbortError' });

/** Fetch + parse the APInex public catalogue
 *  (apinex.bond/api/public/models): ONE unauthenticated GET returning
 *  per-model health + allowFree — the relay's own liveness truth, zero
 *  chat tokens spent. */
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
 *  public-catalogue fast path (health → dot, allowFree → free badge, no
 *  tokens); every other provider (and APInex when the catalogue is
 *  unreachable) probes each model, paced by the SAME 15 RPM bucket as real
 *  chats so a sweep can never starve the user's own messages.
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
          free: e ? e.allowFree === true : null,
          ...(alive === false && e ? { reason: `health=${e.health}` } : {}),
        });
      }
      log.inf('agt', `verifikasi katalog ${providerId}: ${ok} hidup / ${dead} mati / ${unknown} tak ada di katalog`);
      emit({ type: 'done', ok, dead, unknown });
      return;
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      log.wrn('agt', `verifikasi katalog ${providerId} gagal (${e.message}) — lanjut probe per model`);
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
      // A single pre-headers hang is far more likely one dead model than
      // a dead network (the other probes answer): mark it and move on.
      lastConnectFail = e;
      r = { ok: false, ms: Date.now() - t0, reason: String(e.message).slice(0, 140) };
    }
    if (r.ok === true) ok++; else if (r.ok === false) dead++; else unknown++;
    // NIM models are free to use once they answer; a probe cannot see the
    // APInex allowFree flag (null → the app falls back to its free/ prefix).
    // ok:null (429/…) must stay free:null — "cannot tell", not "not free".
    const free = providerId === 'nvidia' ? (r.ok === true ? true : null) : null;
    emit({ type: 'result', model: m, ok: r.ok, free, ms: Date.now() - t0, ...(r.reason ? { reason: r.reason } : {}) });
    log.dbg('agt', `verifikasi ${providerId} ${m}: ${r.ok === true ? 'OK' : r.ok === false ? 'MATI' : 'TAK PASTI'} ${r.reason ? `(${r.reason})` : ''} ${Date.now() - t0}ms`);
  }
  log.inf('agt', `verifikasi probe ${providerId}: ${ok} hidup / ${dead} mati / ${unknown} tak pasti`);
  emit({ type: 'done', ok, dead, unknown });
}

module.exports = { runAgent, callGateway, toOpenAiMessages, sanitizeOptions, buildBody, safeJson, verifyModels, probeModel, forModel, estimateTokens, compactOldTools, elideOldTools, compactOldAssistantArgs, readTodoUnchecked, readProjectMemory, memoryStale, TODO_FILENAME, MEMORY_FILENAME };
