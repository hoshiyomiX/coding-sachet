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

module.exports = { fetchWithRetry, netErrorDetail };
