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

module.exports = { fetchWithRetry, fetchRetryTimeout, netErrorDetail };
