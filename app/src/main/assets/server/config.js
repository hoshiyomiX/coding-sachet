'use strict';
// Coding Sachet agent server — configuration.
// Two upstream providers are supported (both OpenAI-compatible):
//   apinex — https://api.apinex.bond
//   nvidia — https://build.nvidia.com (NIM)
// The app picks one per request via the X-Sachet-Provider header.
// Env overrides exist only for the automated test suite (mock gateways).

const os = require('os');
const path = require('path');
const fs = require('fs');
const log = require('./log');

const HOME = '/root';

// Default agent workspace. /sdcard is the Android shared storage bound in
// by proot when reachable — a project folder there is visible from any file
// manager, which is what the owner asked for. The probe doubles as creation:
// mkdirSync fails on a missing bind (no /sdcard in the guest) or a denied
// permission, and the private /root/work is used instead — never a crash.
function defaultWorkdir() {
  try {
    fs.mkdirSync('/sdcard/coding-sachet', { recursive: true });
    return '/sdcard/coding-sachet';
  } catch (e) {
    log.wrn('cfg', `/sdcard/coding-sachet tidak tersedia (${e.code || e.message}) — workdir default /root/work`);
    return path.join(HOME, 'work');
  }
}

// Extra sandbox roots, pushed by the app for rooted devices (SACHET_EXTRA_ROOTS="/,/device"):
// "/" lets the file explorer navigate the whole guest, "/device" is the
// Android filesystem root bound in by proot (see ProotLauncher).
const extraRoots = (process.env.SACHET_EXTRA_ROOTS || '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

const providers = {
  apinex: {
    label: 'APInex',
    baseUrl: process.env.SACHET_APINEX_BASE_URL || 'https://api.apinex.bond/v1',
    // Client-side RPM cap (owner order, hardcoded): the APInex free tier
    // allows 15 chat requests per minute per account (fixed 60s window —
    // apinex.bond/developers/errors). ratelimit.js spaces requests so the
    // agent loop can never trip a 429 on its own.
    rpmLimit: 15,
    rateLimitHint:
      'Batas laju APInex tercapai. ' +
      'Tunggu sebentar lalu kirim ulang pesanmu.',
    // The gateway applies its own default output limit.
    maxTokens: null,
    // Remaining-quota endpoint — THE one officially documented API this app
    // calls for quota (owner order T-ML20: single official API per feature;
    // apinex.bond/developers documents GET /v1/subscription → token counters
    // tokens_remaining/tokens_used/token_limit/unlimited). The former
    // best-effort /v1/balance call and the new-api-style billing fallback
    // pair were removed: non-official paths are gone by design.
    quota: {
      subscriptionPath: '/subscription',
    },
    // Live model-verification source (T-ML26): the PUBLIC catalogue on the
    // website host (not the API host) carries per-model health + allowFree
    // — one unauthenticated GET gives the actual state of the whole relay
    // without spending a single chat token. Env override: test suite only.
    publicCatalogUrl:
      process.env.SACHET_APINEX_CATALOG_URL || 'https://apinex.bond/api/public/models',
  },
  nvidia: {
    label: 'NVIDIA NIM',
    baseUrl: process.env.SACHET_NVIDIA_BASE_URL || 'https://integrate.api.nvidia.com/v1',
    // Same hardcoded 15 RPM client cap as APInex (owner order): safely
    // under NIM's per-key limits too.
    rpmLimit: 15,
    rateLimitHint:
      'Batas laju NVIDIA NIM tercapai. ' +
      'Tunggu sebentar lalu kirim ulang pesanmu.',
    // NIM integrations should set max_tokens explicitly on chat requests.
    maxTokens: 4096,
  },
};

const config = {
  providers,
  defaultProvider: 'apinex',

  port: Number(process.env.SACHET_PORT || 8765),
  host: '127.0.0.1',

  version: '1.0.0',

  // The directory the coding agent works in. Default: the shared-storage
  // project folder /sdcard/coding-sachet (visible from any file manager);
  // when the storage bind is missing or unwritable (no storage permission,
  // unmounted) the private /root/work is used instead. SACHET_WORKDIR wins.
  workdir: process.env.SACHET_WORKDIR || defaultWorkdir(),

  // NO turn ceiling: maxTurns (24) was removed by owner order in T-ML34 —
  // "hapus semua limit pada agent terkecuali 15 RPM" (field evidence
  // 2026-11-11: a healthy 25-round run was cut at putaran 24/24 mid-work,
  // finish=tool_calls, run 475 k tokens). The agent loop now runs until
  // the model stops calling tools, the user stops, or the connection
  // drops. The only agent-side limit left is the client-side 15 RPM cap
  // (ratelimit.js DEFAULT_RPM + providers.*.rpmLimit, owner-ordered).

  // Upstream request limits.
  requestTimeoutMs: Number(process.env.SACHET_TIMEOUT_MS || 300000),
  // Connect/headers-phase budget per chat attempt (fresh per attempt, see
  // net.js fetchWithHeaderBudget). Env override is for the test suite only.
  connectTimeoutMs: Number(process.env.SACHET_CONNECT_TIMEOUT_MS || 30000),

  // Gateway connect LADDER (T-ML37 P0-1, owner order "10x10s retry",
  // 2026-11-12): how many times the stream-resilient wrapper retries a
  // WHOLE gateway call after the connect phase exhausted its quick
  // in-fetch retries, and the fixed delay between those rounds. Field
  // evidence: a minimized window kills the VPN path for 15-60 s while the
  // quick retries only tolerate ~13 s — the ladder buys minutes of
  // patience for a mid-run chat (each round gets a FRESH request timer
  // and goes through the 15 RPM limiter again, P0-2). Env overrides:
  // test suite only (0 = ladder disabled, pins the pre-T-ML37 behaviour).
  gatewayConnectRetries: Number(process.env.SACHET_GATEWAY_CONNECT_RETRIES ?? 10),
  gatewayRetryDelayMs: Number(process.env.SACHET_GATEWAY_RETRY_DELAY_MS || 10000),

  // Minimum spacing between fire-and-forget warm-up rounds. The app pushes
  // the system DNS right after the server answers /health — without a
  // spacing floor that second round duplicates every warm-up log line.
  warmupMinIntervalMs: Number(process.env.SACHET_WARMUP_MIN_INTERVAL_MS || 15000),

  // Live model-verification sweep (T-ML26): how long a probe waits for the
  // model's FIRST SSE data chunk before calling it dead. Env override:
  // test suite only (fast timeouts).
  verifyFirstChunkMs: Number(process.env.SACHET_VERIFY_FIRST_CHUNK_MS || 15000),

  // Tool limits.
  bashTimeoutMs: Number(process.env.SACHET_BASH_TIMEOUT_MS || 120000),
  // Package-manager commands get a far longer leash (T-ML31 P1-4): apt/
  // dpkg maintainer scripts under proot on a phone outrun 120 s, and a
  // timeout kill leaves dpkg half-configured — the exact state that pushes
  // the model into `dpkg --configure -a`, historically the peak-memory
  // moment the kernel OOM-killer strikes the node server (T-ML27/T-ML30).
  bashTimeoutPkgMs: Number(process.env.SACHET_BASH_TIMEOUT_PKG_MS || 600000),
  bashMaxOutput: 128 * 1024,        // bytes kept from a command's output (UI + app history)
  readMaxBytes: 256 * 1024,         // largest file returned by the read tool / fs file API
  writeMaxBytes: 2 * 1024 * 1024,   // largest accepted file write

  // Token economy (T-ML29, from the T-ML28 usage audit): the caps above
  // feed the UI and the persisted history — the copy SENT TO THE MODEL is
  // windowed to head+tail (toolModelBudgetChars), old tool results are
  // folded to one-line summaries once the prompt estimate passes
  // contextCompactTokens (the newest contextCompactKeep stay intact).
  // NO per-turn token stop: the budget guard (sessionBudgetTokens) was
  // removed by owner order in T-ML32 — APInex grants 20 M free tokens
  // per day, so the 100 k/turn cap only cut healthy long runs. Usage is
  // still tracked and displayed.
  toolModelBudgetChars: Number(process.env.SACHET_TOOL_MODEL_BUDGET_CHARS || 4096),
  contextCompactTokens: Number(process.env.SACHET_CONTEXT_COMPACT_TOKENS || 16000),
  // T-ML39 (audit §6.4 R2b, owner order sesi 27): 3 → 6 — read results
  // now survive compaction twice as long, breaking the window↔re-read
  // loop (40/40 field reads were bash cat/sed whose middles the 4 KB
  // window cut, forcing re-reads). Together with the estimator
  // calibration below, the compact threshold finally matches reality.
  contextCompactKeep: Number(process.env.SACHET_CONTEXT_COMPACT_KEEP || 6),

  // T-ML40 baseline elision (audit §6.7, owner order sesi 28: "hemat
  // token, fokus konteks"): the 16 K threshold above only fired on LONG
  // runs — the 2026-11-14 field session (19 putaran, 149.739 tok) sat at
  // est 14,3 K and never compacted, so EVERY round resent the whole
  // history (98,5% of the run was prompt tokens). Elision now runs on
  // EVERY round: tool results older than the newest contextCompactKeep
  // are shrunk to a short head summary (contextElideHeadChars) as soon
  // as they are bigger than contextElideMinChars — tiny outputs (exit
  // codes, one-liners) stay untouched, they are already cheap. The
  // 16 K threshold remains as the second tier (folds even the kept
  // window to one-liners). Env overrides: test suite only.
  contextElideHeadChars: Number(process.env.SACHET_CONTEXT_ELIDE_HEAD_CHARS || 160),
  contextElideMinChars: Number(process.env.SACHET_CONTEXT_ELIDE_MIN_CHARS || 400),

  // T-ML39 TODO-protocol enforcement: how many times the agent loop may
  // bounce a "finished" answer back because RENCANA.md still has
  // unchecked steps. Bounded so a stubborn model can never trap the run
  // (owner order: no limits except 15 RPM — this is a prompt-side nudge,
  // not a turn ceiling; after the budget the answer is accepted).
  todoNudgeMax: Number(process.env.SACHET_TODO_NUDGE_MAX ?? 2),

  // T-ML40 persistent project memory (owner order sesi 28): MEMORI.md in
  // the session workdir survives across chats in the same folder. Its
  // head (memoryInjectMaxChars) is injected into the system prompt at
  // run start; a long run (≥ memoryNudgeMinTurns) that never touched the
  // file gets ONE wire-only reminder to write it before the final answer
  // — the todo-nudge pattern, bounded by memoryNudgeMax. Env overrides:
  // test suite only.
  memoryFilename: 'MEMORI.md',
  memoryInjectMaxChars: Number(process.env.SACHET_MEMORY_INJECT_MAX_CHARS || 1200),
  memoryNudgeMax: Number(process.env.SACHET_MEMORY_NUDGE_MAX ?? 1),
  memoryNudgeMinTurns: Number(process.env.SACHET_MEMORY_NUDGE_MIN_TURNS || 5),

  // T-ML41 (#3, owner order sesi 28 "optimisasi lanjutan"): how many times
  // the agent loop may bounce a finish_reason=length answer back with a
  // wire-only "lanjutkan dari titik terputus" so a token-limit-cut answer
  // assembles itself instead of ending mid-sentence. Bounded so a model
  // that re-truncates without progress can never trap the run; a bounce
  // only happens when the round produced real content. Env override:
  // test suite only.
  lengthContinueMax: Number(process.env.SACHET_LENGTH_CONTINUE_MAX ?? 3),

  // T-ML39 syntax harness: the write_file tool auto-gates .js/.mjs/.cjs
  // (node --check), .sh/.bash (bash -n) and .json (in-process parse)
  // after every write. Files larger than this skip the gate (spawn cost,
  // not every write deserves one); the check itself gets this timeout.
  syntaxGateMaxBytes: 512 * 1024,
  syntaxGateTimeoutMs: Number(process.env.SACHET_SYNTAX_GATE_TIMEOUT_MS || 10000),

  // File API sandbox: paths must resolve under one of these roots.
  allowedRoots: [HOME, '/tmp', '/sdcard', '/storage/emulated/0', ...extraRoots],

  jsonBodyLimit: 5 * 1024 * 1024,

  // Where the /api/dns route writes the guest resolver config. The Android
  // app pushes the system's (VPN-aware) DNS servers there; public
  // resolvers are only the fallback, because many VPNs block direct
  // UDP/53 to them ("fetch failed" under VPN).
  resolvConfPath: process.env.SACHET_RESOLV_CONF || '/etc/resolv.conf',
  dnsFallbackServers: ['1.1.1.1', '8.8.8.8'],
};

/** Resolve a provider id ("apinex" | "nvidia") to its spec, or null. */
function providerById(id) {
  return (id && Object.prototype.hasOwnProperty.call(providers, id)) ? providers[id] : null;
}

module.exports = { config, providers, providerById, HOME };
