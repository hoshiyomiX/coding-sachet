'use strict';
// Shared network helpers for the gateway paths.
//
// The guest's outbound fetches travel proot → Android kernel. Two things
// make the FIRST attempt fail transiently in the field:
//   * a VPN coming up or switching (the resolver config changes under us),
//   * carrier networks cold-starting DNS/TLS slowly.
// Both surface as a connect-level `fetch failed` long before any byte of
// the response exists, so a short bounded retry heals them without ever
// duplicating streamed content.

/**
 * fetch() with bounded retries for connection-level failures.
 * AbortError (caller cancel / timeout signal) is never retried.
 * HTTP error statuses are NOT retried — the caller sees them and decides.
 */
async function fetchWithRetry(url, opts = {}, { retries = 2, delays = [400, 900] } = {}) {
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (attempt > 0) {
      await new Promise((r) => setTimeout(r, delays[Math.min(attempt - 1, delays.length - 1)]));
    }
    try {
      return await fetch(url, opts);
    } catch (e) {
      lastErr = e;
      if (e && e.name === 'AbortError') throw e;
    }
  }
  throw lastErr;
}

/**
 * fetch() with a FRESH timeout budget per attempt.
 *
 * The cold path through a VPN (first DNS lookup + TLS handshake inside
 * proot) can exceed a short timeout once and then run fast forever after.
 * Unlike fetchWithRetry — which never retries an AbortError because it may
 * be a caller cancel — here the timeout signal is OURS, so a timed-out
 * attempt is always worth one more try. A caller-supplied signal stays
 * final: when it aborts, the error is rethrown unretried.
 */
async function fetchRetryTimeout(url, opts = {}, { timeoutMs = 20000, retries = 2, delays = [500, 1000] } = {}) {
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (attempt > 0) {
      await new Promise((r) => setTimeout(r, delays[Math.min(attempt - 1, delays.length - 1)]));
    }
    // Combine a fresh per-attempt timeout with the caller's signal (if any).
    const timeoutSignal = AbortSignal.timeout(timeoutMs);
    const signal = opts.signal ? AbortSignal.any([opts.signal, timeoutSignal]) : timeoutSignal;
    try {
      return await fetch(url, { ...opts, signal });
    } catch (e) {
      lastErr = e;
      // A caller cancel is final; never retried.
      if (opts.signal && opts.signal.aborted) throw e;
      // Anything else (our timeout, a connect-level failure) is retryable.
    }
  }
  throw lastErr;
}

/**
 * fetch() for STREAMED responses: a fresh timeout budget per attempt that
 * only guards the connect/headers phase.
 *
 * The chat path streams SSE for minutes, so a plain per-attempt timeout
 * (fetchRetryTimeout) would abort a healthy long stream. Here the attempt
 * timer is cleared the moment headers arrive — after that only the CALLER's
 * signal can stop the body. A cold VPN path whose first connect hangs or a
 * connect-level `fetch failed` is retried with a fresh budget, so the user
 * sees a reconnect hint instead of a silent spinner up to the global
 * request timeout (the NIM "no response" field report).
 */
async function fetchWithHeaderBudget(url, opts = {}, { timeoutMs = 30000, retries = 2, delays = [500, 1500] } = {}, onRetry) {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const wire = (callerSignal, ctl) => {
    // Caller abort must still kill an in-flight attempt (and, after headers,
    // the body stream): keep the listener attached for the response's life.
    const onAbort = () => ctl.abort();
    callerSignal.addEventListener('abort', onAbort, { once: true });
    return onAbort;
  };
  for (let attempt = 0; ; attempt++) {
    const callerSignal = opts.signal || null;
    if (callerSignal && callerSignal.aborted) {
      const e = new Error('dibatalkan');
      e.name = 'AbortError';
      throw e;
    }
    const ctl = new AbortController();
    const detach = callerSignal ? wire(callerSignal, ctl) : null;
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    let res;
    try {
      res = await fetch(url, { ...opts, signal: ctl.signal });
      clearTimeout(timer); // headers in — the stream may legitimately run long
      return res;
    } catch (e) {
      clearTimeout(timer);
      if (detach) callerSignal.removeEventListener('abort', detach);
      const callerAborted = !!(callerSignal && callerSignal.aborted);
      if (callerAborted) throw e; // stop button / caller timeout: final
      if (attempt >= retries) {
        if (e.name === 'AbortError') {
          // Our header-phase timeout, exhausted: mark it so the caller can
          // say "connect timeout" instead of the global request timeout.
          e.connectTimeoutMs = timeoutMs;
        }
        throw e;
      }
      if (onRetry) onRetry(attempt + 1, e);
      await sleep(delays[Math.min(attempt, delays.length - 1)]);
    }
  }
}

/**
 * Human detail for a connect-level failure. undici collapses every socket
 * problem into "fetch failed"; the useful fact (ENOTFOUND, ECONNREFUSED,
 * UNABLE_TO_VERIFY_LEAF_SIGNATURE, …) hides in e.cause — surface it so a
 * VPN/DNS problem is diagnosable from the error bubble alone.
 */
function netErrorDetail(e) {
  const cause = e && e.cause;
  const code = cause && (cause.code || cause.message);
  const detail = typeof code === 'string' && code ? ` (${code})` : '';
  const msg = (e && e.message) || 'fetch failed';
  return `${msg}${detail}`;
}

module.exports = { fetchWithRetry, fetchRetryTimeout, fetchWithHeaderBudget, netErrorDetail };
