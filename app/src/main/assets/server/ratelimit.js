'use strict';
// Client-side rate limiter for gateway chat requests.
//
// APInex enforces RPM per ACCOUNT over a fixed 60-second window
// (apinex.bond/developers/errors): the free tier answers
// "Rate limit exceeded. Max 15 requests per minute" with HTTP 429. The
// agent loop can easily burst past that (one request per tool round), so
// every chat request first acquires a slot here.
//
// The limit is HARDCODED at 15 RPM by owner order — a sliding client
// window of 15/60s is strictly contained in the gateway's fixed window,
// so a compliant client can never trip a 429 on its own. The env
// overrides exist ONLY for the automated test suite (fast windows);
// production never sets them.

const { providerById } = require('./config');

// Owner-ordered hard limit: max requests per 60s sliding window.
const DEFAULT_RPM = 15;
const DEFAULT_WINDOW_MS = 60 * 1000;

const RPM = Number(process.env.SACHET_TEST_RPM || DEFAULT_RPM);
const WINDOW_MS = Number(process.env.SACHET_TEST_WINDOW_MS || DEFAULT_WINDOW_MS);
// Small scheduling margin so a timer that fires a tick early can never
// let a request start inside the previous window.
const MARGIN_MS = 50;

// Per-provider state: the bucket is really per ACCOUNT, and this app
// uses exactly one key per provider, so providerId is the right key.
//   starts:   timestamps (ms) of chat-request starts inside the window
//   pausedUntil: ms epoch; a 429 pushes this forward (backpressure)
const buckets = new Map();

function bucketFor(id) {
  let b = buckets.get(id);
  if (!b) {
    b = { starts: [], pausedUntil: 0 };
    buckets.set(id, b);
  }
  return b;
}

function prune(b, now) {
  while (b.starts.length && now - b.starts[0] >= WINDOW_MS) b.starts.shift();
}

/** Effective RPM limit for a provider (hardcoded default 15). */
function limitFor(providerId) {
  // The test override wins so the suite can use fast windows.
  if (process.env.SACHET_TEST_RPM) return RPM;
  const p = providerById(providerId);
  return (p && Number.isFinite(p.rpmLimit) && p.rpmLimit > 0) ? p.rpmLimit : RPM;
}

/**
 * How long the NEXT acquire() for this provider would wait right now
 * (0 = a slot is free). Lets the caller surface a "menunggu batas laju"
 * hint before going silent.
 */
function peekWaitMs(providerId) {
  const b = bucketFor(providerId);
  const now = Date.now();
  prune(b, now);
  let wait = 0;
  if (now < b.pausedUntil) wait = b.pausedUntil - now;
  if (b.starts.length >= limitFor(providerId)) {
    const free = b.starts[0] + WINDOW_MS + MARGIN_MS - now;
    if (free > wait) wait = free;
  }
  return Math.max(0, Math.ceil(wait));
}

/** Sleep that rejects promptly when `signal` aborts. Named AbortError so
 *  callers treat it exactly like a fetch-level abort (stop button, timeout). */
function abortableSleep(ms, signal) {
  if (!signal) return new Promise((r) => setTimeout(r, ms));
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(t);
      reject(Object.assign(new Error('dibatalkan'), { name: 'AbortError' }));
    };
    if (signal.aborted) return onAbort();
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * Wait for a free slot, then record the request start. Resolves when the
 * request may proceed; rejects 'dibatalkan' when `signal` fires while
 * waiting. Concurrency-safe: waiters re-check the window after every
 * sleep, so parallel acquires can never exceed the limit.
 */
async function acquire(providerId, signal) {
  const b = bucketFor(providerId);
  for (;;) {
    if (signal && signal.aborted) throw new Error('dibatalkan');
    const now = Date.now();
    prune(b, now);
    let wait = 0;
    if (now < b.pausedUntil) wait = b.pausedUntil - now;
    if (wait === 0 && b.starts.length >= limitFor(providerId)) {
      wait = b.starts[0] + WINDOW_MS + MARGIN_MS - now;
    }
    if (wait <= 0) {
      b.starts.push(Date.now());
      return;
    }
    await abortableSleep(Math.ceil(wait), signal);
  }
}

/**
 * Backpressure after a real 429 (e.g. another device on the same account
 * burned the bucket): every later acquire waits out the pause too.
 */
function pauseFor(providerId, ms) {
  const b = bucketFor(providerId);
  const until = Date.now() + Math.max(0, ms);
  if (until > b.pausedUntil) b.pausedUntil = until;
}

/** Test hook: forget all windows and pauses. */
function reset() {
  buckets.clear();
}

module.exports = { acquire, peekWaitMs, pauseFor, limitFor, abortableSleep, reset };
