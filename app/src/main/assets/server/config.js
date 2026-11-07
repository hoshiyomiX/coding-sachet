'use strict';
// APInex agent server — configuration.
// The gateway is hardcoded: this app exists to talk to APInex
// (https://apinex.bond — OpenAI-compatible, free "free/*" models).
// Env overrides exist only for the automated test suite (mock gateway).

const os = require('os');
const path = require('path');

const HOME = '/root';

const config = {
  // The one and only upstream this server ever talks to.
  baseUrl: process.env.APINEX_BASE_URL || 'https://api.apinex.bond/v1',

  port: Number(process.env.APINEX_PORT || 8765),
  host: '127.0.0.1',

  version: '1.0.0',

  // The directory the coding agent works in.
  workdir: process.env.APINEX_WORKDIR || path.join(HOME, 'work'),

  // Agent loop limits.
  maxTurns: Number(process.env.APINEX_MAX_TURNS || 24),

  // Upstream request limits.
  requestTimeoutMs: Number(process.env.APINEX_TIMEOUT_MS || 300000),
  connectTimeoutMs: 30000,

  // Tool limits.
  bashTimeoutMs: Number(process.env.APINEX_BASH_TIMEOUT_MS || 120000),
  bashMaxOutput: 128 * 1024,        // bytes kept from a command's output
  readMaxBytes: 256 * 1024,         // largest file returned by the read tool / fs file API
  writeMaxBytes: 2 * 1024 * 1024,   // largest accepted file write

  // File API sandbox: paths must resolve under one of these roots.
  allowedRoots: [HOME, '/tmp', '/sdcard', '/storage/emulated/0'],

  jsonBodyLimit: 5 * 1024 * 1024,
};

module.exports = { config, HOME };
