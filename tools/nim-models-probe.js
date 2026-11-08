'use strict';
// Re-verify which NVIDIA NIM models actually work for chat (T-ML17).
//
// The /v1/models catalogue lists ~84 entries, but most are not deployed for
// normal keys. This probe answers a minimal chat call per model:
//
//   NIM_KEY=nvapi-… node tools/nim-models-probe.js
//
// Classification:
//   ok           2xx on the non-stream POST
//   ok (stream)  non-stream timed out, but an SSE call returned 200 and a
//                first chunk — the app's real path is streaming, so these
//                ARE usable (llama-3.2-90b-vision, nemoguard-content-safety)
//   ratelimited  persistent 429 — model exists, only the per-key limit hit
//   fail         404 not deployed / 4xx wrong shape / persistent timeout
//
// The key is read from the NIM_KEY env var and never stored anywhere.
// Global pacing (~35 starts/min) keeps the probe under NIM's per-key RPM.

const BASE = process.env.NIM_BASE_URL || 'https://integrate.api.nvidia.com/v1';
const KEY = process.env.NIM_KEY;
if (!KEY) {
  console.error('usage: NIM_KEY=nvapi-… node tools/nim-models-probe.js');
  process.exit(1);
}

const PACING_MS = 1700;
const CONCURRENCY = 2;
const T_NONSTREAM = 35000;
const T_STREAM = 75000;
const RETRY_429 = 2;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function reasonOf(status, text) {
  try {
    const j = JSON.parse(text);
    const d = j.detail || j.error || j.title || j.message;
    if (typeof d === 'string') return d.slice(0, 140);
    if (d && d.message) return String(d.message).slice(0, 140);
    return JSON.stringify(d).slice(0, 140);
  } catch {
    return text.slice(0, 140);
  }
}

async function chatOnce(id, stream) {
  const body = JSON.stringify({
    model: id,
    messages: [{ role: 'user', content: 'hi' }],
    max_tokens: 16,
    stream,
  });
  const res = await fetch(BASE + '/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: 'Bearer ' + KEY,
      'Content-Type': 'application/json',
      ...(stream ? { Accept: 'text/event-stream' } : {}),
    },
    body,
    signal: AbortSignal.timeout(stream ? T_STREAM : T_NONSTREAM),
  });
  if (stream) {
    if (res.status !== 200) {
      await res.text();
      return { status: res.status, reason: '' };
    }
    // Headers arrived: read only the first data line, then stop.
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        if (buf.includes('\n')) break;
      }
    } finally {
      try { await reader.cancel(); } catch { /* already closed */ }
    }
    return { status: 200, reason: 'first chunk ok' };
  }
  const text = await res.text();
  return { status: res.status, reason: reasonOf(res.status, text) };
}

async function probeModel(id) {
  const t0 = Date.now();
  let r;
  try {
    r = await chatOnce(id, false);
  } catch (e) {
    if (e.name === 'TimeoutError') {
      // Non-stream timed out: give the streaming path (what the app uses)
      // one chance before declaring the model dead.
      try {
        const s = await chatOnce(id, true);
        if (s.status === 200) return { cls: 'ok-stream', ms: Date.now() - t0, reason: '' };
      } catch { /* stream also failed */ }
    }
    return { cls: 'fail', ms: Date.now() - t0, reason: e.name };
  }
  if (r.status >= 200 && r.status < 300) return { cls: 'ok', ms: Date.now() - t0, reason: '' };
  if (r.status === 429) {
    for (let i = 1; i <= RETRY_429; i++) {
      await sleep(8000 * i);
      try {
        r = await chatOnce(id, false);
        if (r.status >= 200 && r.status < 300) return { cls: 'ok', ms: Date.now() - t0, reason: '' };
        if (r.status !== 429) break;
      } catch (e) {
        return { cls: 'fail', ms: Date.now() - t0, reason: e.name };
      }
    }
    return { cls: 'ratelimited', ms: Date.now() - t0, reason: 'persistent 429' };
  }
  return { cls: 'fail', ms: Date.now() - t0, reason: `HTTP ${r.status} ${r.reason}` };
}

async function main() {
  const t0 = Date.now();
  const list = await fetch(BASE + '/models', {
    headers: { Authorization: 'Bearer ' + KEY },
    signal: AbortSignal.timeout(20000),
  });
  if (list.status !== 200) {
    console.error('GET /models HTTP', list.status);
    process.exit(1);
  }
  const ids = (await list.json()).data.map((m) => m.id).sort();
  console.error(`catalogue: ${ids.length} models — probing with pacing ${PACING_MS}ms…`);

  const results = new Map();
  let nextStart = 0;
  let idx = 0;

  async function worker() {
    for (;;) {
      const i = idx++;
      if (i >= ids.length) return;
      const id = ids[i];
      const now = Date.now();
      if (now < nextStart) await sleep(nextStart - now);
      nextStart = Math.max(Date.now(), nextStart) + PACING_MS;
      results.set(id, await probeModel(id));
      const r = results.get(id);
      process.stderr.write(`[${results.size}/${ids.length}] ${id} -> ${r.cls} (${r.ms}ms)\n`);
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, worker));

  const pick = (cls) => [...results.entries()].filter(([, v]) => v.cls === cls).map(([id]) => id);
  const ok = pick('ok');
  const okStream = pick('ok-stream');
  const rl = pick('ratelimited');
  const failed = [...results.entries()]
    .filter(([, v]) => v.cls === 'fail')
    .map(([id, v]) => `${id} — ${v.reason}`);

  console.log(`checked:   ${ids.length} models in ${Math.round((Date.now() - t0) / 1000)}s`);
  console.log(`ok:        ${ok.length}`);
  ok.forEach((id) => console.log(`  ${id}`));
  console.log(`ok (SSE):  ${okStream.length}`);
  okStream.forEach((id) => console.log(`  ${id}`));
  console.log(`ratelimit: ${rl.length}`);
  rl.forEach((id) => console.log(`  ${id}`));
  console.log(`failed:    ${failed.length}`);
  failed.forEach((line) => console.log(`  ${line}`));
  console.log('\nKotlin setOf(…) for Providers.kt -> NVIDIA.verifiedModels:');
  console.log([...ok, ...okStream].sort().map((id) => `    "${id}",`).join('\n'));
}

main().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
