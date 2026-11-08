'use strict';
// The agent loop: talks to the selected provider gateway (APInex or
// NVIDIA NIM — both OpenAI-compatible, SSE streaming) and executes the
// tool calls the model asks for.

const { config, providerById } = require('./config');
const { toolByName } = require('./tools');
const { fetchWithHeaderBudget, netErrorDetail } = require('./net');
const { acquire, peekWaitMs, pauseFor, abortableSleep } = require('./ratelimit');

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
const RESERVED_OPTION_KEYS = ['model', 'messages', 'stream', 'tools', 'tool_choice', 'n'];
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
 *  sanitized user options last (they may override max_tokens etc.). */
function buildBody({ model, messages, tools, provider, options }) {
  const body = { model, messages, stream: true };
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

/** One streamed call to the provider. Returns the assembled assistant turn. */
async function callGateway({ apiKey, providerId, model, messages, tools, options, signal, onDelta }) {
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
    const body = JSON.stringify(buildBody({ model, messages, tools, provider, options: opts }));
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
          { timeoutMs: config.connectTimeoutMs, retries: 2, delays: [500, 1500] },
          (attemptNo, err) => {
            if (onDelta) onDelta({ type: 'netretry', attempt: attemptNo, provider: provider.label, detail: netErrorDetail(err).slice(0, 120) });
          }
        );
        if (res.ok || res.status !== 429 || attempt >= MAX_429_RETRIES) break;
        await res.text().catch(() => {}); // drain the error body
        const waitMs = retryAfterMs(res);
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
          throw new Error(
            `gateway timeout ke ${provider.label} (koneksi ${Math.round(e.connectTimeoutMs / 1000)}s × 3 percobaan — cek jaringan/VPN)`
          );
        }
        throw new Error(`gateway timeout (${config.requestTimeoutMs / 1000}s)`);
      }
      throw new Error(`Koneksi ke gateway ${provider.label} gagal: ${netErrorDetail(e)}`);
    }

    if (!res.ok) {
      const text = await res.text().catch(() => '');
      let msg = text.slice(0, 500);
      try { msg = JSON.parse(text).error?.message || msg; } catch { /* not JSON */ }
      // 429 carries a provider-specific free-tier hint instead of a bare status line.
      const err = new Error(
        res.status === 429 ? `HTTP ${res.status}: ${msg} — ${provider.rateLimitHint}` : `HTTP ${res.status}: ${msg}`
      );
      err.status = res.status;
      throw err;
    }

    // ---- parse the SSE stream ----
    const turn = { content: '', reasoning: '', toolCalls: [], finishReason: null };

    try {
      const decoder = new TextDecoder();
      let buffer = '';
      let done = false;

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
          if (j.error) throw new Error(j.error.message || 'gateway error');
          const ch = j.choices && j.choices[0];
          if (!ch) continue;
          if (ch.finish_reason) turn.finishReason = ch.finish_reason;

          const d = ch.delta || {};
          if (typeof d.reasoning_content === 'string' && d.reasoning_content) {
            turn.reasoning += d.reasoning_content;
            onDelta({ type: 'think', delta: d.reasoning_content });
          }
          if (typeof d.reasoning === 'string' && d.reasoning) {
            turn.reasoning += d.reasoning;
            onDelta({ type: 'think', delta: d.reasoning });
          }
          if (typeof d.content === 'string' && d.content) {
            turn.content += d.content;
            onDelta({ type: 'text', delta: d.content });
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
    } finally {
      try { res.body.cancel(); } catch { /* already closed */ }
    }

    turn.toolCalls = turn.toolCalls.filter((t) => t.name || t.id);
    return turn;
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
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
              content: r.content || '',
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
async function runAgent({ apiKey, providerId, model, messages, options, signal, emit, systemPrompt }) {
  const openai = toOpenAiMessages(messages);
  if (systemPrompt) openai.unshift({ role: 'system', content: systemPrompt });

  const { tools } = require('./tools');
  const openaiTools = tools.map((t) => ({
    type: 'function',
    function: { name: t.name, description: t.description, parameters: t.parameters },
  }));

  const added = [];   // messages the app should persist
  let turn = 0;

  while (turn < config.maxTurns) {
    turn++;
    emit({ type: 'turn', n: turn });

    const t = await callGateway({
      apiKey, providerId, model, messages: openai, tools: openaiTools, options, signal,
      onDelta: (e) => emit(e),
    });

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

    if (!wantsTools) {
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
      const tool = toolByName[call.name];
      if (!tool) {
        result = { ok: false, output: `Tool tidak dikenal: ${call.name}` };
      } else {
        try {
          result = await tool.run(args);
        } catch (e) {
          result = { ok: false, output: `tool error: ${e.message}` };
        }
      }
      const output = typeof result?.output === 'string'
        ? result.output
        : JSON.stringify(result ?? {}).slice(0, config.bashMaxOutput);

      emit({ type: 'toolend', id: call.id, ok: !!result?.ok, output: output.slice(0, 4000) });

      const toolMsg = { role: 'tool', toolCallId: call.id, name: call.name, content: output };
      emit({ type: 'toolresult', msg: toolMsg });
      added.push(toolMsg);
      openai.push({ role: 'tool', tool_call_id: call.id, name: call.name, content: output });
    }
  }

  emit({ type: 'error', message: `Batas ${config.maxTurns} putaran agent tercapai.` });
  emit({ type: 'done', turns: turn });
  return added;
}

function safeJson(s) {
  if (typeof s !== 'string' || !s.trim()) return {};
  try { return JSON.parse(s); } catch { return { _raw: s }; }
}

module.exports = { runAgent, callGateway, toOpenAiMessages, sanitizeOptions, buildBody, safeJson };
