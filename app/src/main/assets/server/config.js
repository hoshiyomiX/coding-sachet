'use strict';
// Poket Coding agent server — configuration.
// Two upstream providers are supported (both OpenAI-compatible):
//   apinex — https://apinex.bond        (free/ models, 20M tokens/day)
//   nvidia — https://build.nvidia.com   (NIM, ~40 requests/minute)
// The app picks one per request via the X-Poket-Provider header.
// Env overrides exist only for the automated test suite (mock gateways).

const os = require('os');
const path = require('path');

const HOME = '/root';

const providers = {
  apinex: {
    label: 'APInex',
    baseUrl: process.env.POKET_APINEX_BASE_URL || 'https://api.apinex.bond/v1',
    // APInex free tier: 15 requests per minute across the free/ models
    // (documented at apinex.bond/developers/models).
    rateLimitHint:
      'Batas laju APInex tercapai (akun gratis: 15 permintaan/menit). ' +
      'Tunggu sebentar lalu kirim ulang pesanmu.',
    // The gateway applies its own default output limit.
    maxTokens: null,
  },
  nvidia: {
    label: 'NVIDIA NIM',
    baseUrl: process.env.POKET_NVIDIA_BASE_URL || 'https://integrate.api.nvidia.com/v1',
    // NIM free (prototype) tier: up to 40 requests per minute for most
    // models, no per-token billing (build.nvidia.com FAQ).
    rateLimitHint:
      'Batas laju NVIDIA NIM tercapai (akun gratis: sekitar 40 permintaan/menit). ' +
      'Tunggu sebentar lalu kirim ulang pesanmu.',
    // NIM integrations should set max_tokens explicitly on chat requests.
    maxTokens: 4096,
  },
};

const config = {
  providers,
  defaultProvider: 'apinex',

  port: Number(process.env.POKET_PORT || 8765),
  host: '127.0.0.1',

  version: '1.0.0',

  // The directory the coding agent works in.
  workdir: process.env.POKET_WORKDIR || path.join(HOME, 'work'),

  // Agent loop limits.
  maxTurns: Number(process.env.POKET_MAX_TURNS || 24),

  // Upstream request limits.
  requestTimeoutMs: Number(process.env.POKET_TIMEOUT_MS || 300000),
  connectTimeoutMs: 30000,

  // Tool limits.
  bashTimeoutMs: Number(process.env.POKET_BASH_TIMEOUT_MS || 120000),
  bashMaxOutput: 128 * 1024,        // bytes kept from a command's output
  readMaxBytes: 256 * 1024,         // largest file returned by the read tool / fs file API
  writeMaxBytes: 2 * 1024 * 1024,   // largest accepted file write

  // File API sandbox: paths must resolve under one of these roots.
  allowedRoots: [HOME, '/tmp', '/sdcard', '/storage/emulated/0'],

  jsonBodyLimit: 5 * 1024 * 1024,
};

/** Resolve a provider id ("apinex" | "nvidia") to its spec, or null. */
function providerById(id) {
  return (id && Object.prototype.hasOwnProperty.call(providers, id)) ? providers[id] : null;
}

module.exports = { config, providers, providerById, HOME };
