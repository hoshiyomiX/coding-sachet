'use strict';
// The agent loop: talks to the active provider gateway (OpenAI-compatible,
// SSE streaming) and executes the tool calls the model asks for.

const { config } = require('./config');
const { toolByName } = require('./tools');

// APInex free-tier rate limit: free accounts are capped at 15 requests per
// minute across the free/ models (documented at apinex.bond/developers/models).
// When the cap trips the gateway answers HTTP 429 — surface it with a clear
// hint instead of a bare status line.
const RATE_LIMIT_HINT =
  'Batas laju APInex tercapai (akun gratis: 15 permintaan/menit). ' +
  'Tunggu sebentar lalu kirim ulang pesanmu.';

/** One streamed call to the gateway. Returns the assembled assistant turn. */
async function callGateway({ apiKey, model, messages, tools, signal, onDelta }) {
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) throw new Error('aborted');
    signal.addEventListener('abort', onAbort, { once: true });
  }
  const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);

  let res;
  try {
    res = await fetch(config.baseUrl + '/chat/completions', {
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
        ...(tools && tools.length ? { tools, tool_choice: 'auto' } : {}),
      }),
    });
  } catch (e) {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
    if (e.name === 'AbortError') throw new Error(signal && signal.aborted ? 'dibatalkan' : `gateway timeout (${config.requestTimeoutMs / 1000}s)`);
    throw new Error(`Koneksi ke gateway gagal: ${e.message}`);
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
    let msg = text.slice(0, 500);
    try { msg = JSON.parse(text).error?.message || msg; } catch { /* not JSON */ }
    const err = new Error(res.status === 429 ? `HTTP ${res.status}: ${msg} — ${RATE_LIMIT_HINT}` : `HTTP ${res.status}: ${msg}`);
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

/** Convert our wire messages into OpenAI chat format. */
function toOpenAiMessages(messages) {
  const out = [];
  for (const m of messages) {
    if (m.role === 'system') {
      out.push({ role: 'system', content: m.content || '' });
    } else if (m.role === 'user') {
      out.push({ role: 'user', content: m.content || '' });
    } else if (m.role === 'assistant') {
      if (Array.isArray(m.toolCalls) && m.toolCalls.length) {
        out.push({
          role: 'assistant',
          content: m.content || '',
          tool_calls: m.toolCalls.map((t) => ({
            id: t.id,
            type: 'function',
            function: { name: t.name, arguments: typeof t.args === 'string' ? t.args : JSON.stringify(t.args ?? {}) },
          })),
        });
      } else {
        out.push({ role: 'assistant', content: m.content || '' });
      }
    } else if (m.role === 'tool') {
      out.push({ role: 'tool', tool_call_id: m.toolCallId, name: m.name || 'tool', content: m.content || '' });
    }
  }
  return out;
}

/**
 * Run the full agent loop, emitting events through `emit`.
 * `messages` is the app-owned history in our wire format; the returned
 * list of new messages lets the app extend its persisted history.
 */
async function runAgent({ apiKey, model, messages, signal, emit, systemPrompt }) {
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
      apiKey, model, messages: openai, tools: openaiTools, signal,
      onDelta: (e) => emit(e),
    });

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
