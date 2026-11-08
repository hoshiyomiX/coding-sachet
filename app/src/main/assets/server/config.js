'use strict';
// Coding Sachet agent server — configuration.
// Two upstream providers are supported (both OpenAI-compatible):
//   apinex — https://api.apinex.bond
//   nvidia — https://build.nvidia.com (NIM)
// The app picks one per request via the X-Sachet-Provider header.
// Env overrides exist only for the automated test suite (mock gateways).

const os = require('os');
const path = require('path');

const HOME = '/root';

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
    // Remaining-quota endpoints (apinex.bond/developers/balance):
    //   GET /v1/subscription → token counters (tokens_remaining, unlimited)
    //   GET /v1/balance      → spendable wallet USD
    // If the native endpoints ever disappear (404/405), the OpenAI-style
    // billing pair new-api deployments expose is used as a fallback.
    quota: {
      subscriptionPath: '/subscription',
      balancePath: '/balance',
      billingFallback: true,
      // new-api internal quota units per USD (QuotaPerUnit default).
      quotaPerUnit: 500000,
    },
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

  // The directory the coding agent works in.
  workdir: process.env.SACHET_WORKDIR || path.join(HOME, 'work'),

  // Agent loop limits.
  maxTurns: Number(process.env.SACHET_MAX_TURNS || 24),

  // Upstream request limits.
  requestTimeoutMs: Number(process.env.SACHET_TIMEOUT_MS || 300000),
  connectTimeoutMs: 30000,

  // Tool limits.
  bashTimeoutMs: Number(process.env.SACHET_BASH_TIMEOUT_MS || 120000),
  bashMaxOutput: 128 * 1024,        // bytes kept from a command's output
  readMaxBytes: 256 * 1024,         // largest file returned by the read tool / fs file API
  writeMaxBytes: 2 * 1024 * 1024,   // largest accepted file write

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
