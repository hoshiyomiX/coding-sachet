'use strict';
// The agent loop: talks to the selected provider gateway (APInex or
// NVIDIA NIM — both OpenAI-compatible, SSE streaming) and executes the
// tool calls the model asks for.

const { config, providerById } = require('./config');
const { toolByName } = require('./tools');
const { fetchWithRetry, netErrorDetail } = require('./net');

// Strict gateways (NIM, OpenAI) require every tool_call to carry an id,
// but some models stream tool calls without one. Ids are synthesized
// before anything is persisted or echoed, so a whole round stays
// consistent even across a mid-session model switch.
let synthCounter = 0;
function synthToolCallId() {
  synthCounter = (synthCounter + 1) % 1e9;
  return `call_s${Date.now().toString(36)}_${synthCounter}`;
}

/** One streamed call to the provider. Returns the assembled assistant turn. */
async function callGateway({ apiKey, providerId, model, messages, tools, signal, onDelta }) {
  const provider = providerById(providerId) || config.providers.apinex;
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) throw new Error('aborted');
    signal.addEventListener('abort', onAbort, { once: true });
  }
  const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);

  let res;
  try {
    // Connect-level retries only: the fetch either resolves with headers
    // (never retried afterwards — streamed content must not duplicate) or
    // rejects before a byte exists. VPN bring-up races fail exactly here.
    res = await fetchWithRetry(provider.baseUrl + '/chat/completions', {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        model,
        messages,
        stream: true,
        // NIM asks integrations to set max_tokens explicitly; APInex uses
        // its own default, so the field is only sent when configured.
        ...(provider.maxTokens ? { max_tokens: provider.maxTokens } : {}),
        ...(tools && tools.length ? { tools, tool_choice: 'auto' } : {}),
      }),
    });
  } catch (e) {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
    if (e.name === 'AbortError') throw new Error(signal && signal.aborted ? 'dibatalkan' : `gateway timeout (${config.requestTimeoutMs / 1000}s)`);
    throw new Error(`Koneksi ke gateway gagal: ${netErrorDetail(e)}`);
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
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
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
    try { res.body.cancel(); } catch { /* already closed */ }
  }

  turn.toolCalls = turn.toolCalls.filter((t) => t.name || t.id);
  return turn;
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
async function runAgent({ apiKey, providerId, model, messages, signal, emit, systemPrompt }) {
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
      apiKey, providerId, model, messages: openai, tools: openaiTools, signal,
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

module.exports = { runAgent, callGateway, toOpenAiMessages, safeJson };
