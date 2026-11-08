'use strict';
// Standardized server-side logging (owner order T-ML16 #2: one format for
// every debug line, so field reports can be read at a glance).
//
// Format:  HH:MM:SS [LVL] [module] message
//   12:34:56 [INF] [srv] chat nvidia z-ai/glm-5.3 msgs=4
//
// Levels:  DBG (verbose lifecycle), INF (key events), WRN (recovered
// problems), ERR (failures the user can see). DBG lines are silenced by
// SACHET_QUIET=1; everything else is always printed.
//
// The lines travel to the Android app through the server's stdout (the
// RuntimeController pipes them into its in-app log buffer and logcat), so
// logging must stay single-line and cheap. A logging failure must NEVER
// crash the server (uncaughtException exits the process) — emit() guards
// every write.
//
// Modules in use: cfg config.js · srv server.js · agt agent.js ·
// net net.js · rlm ratelimit.js · tool tools.js

const QUIET = process.env.SACHET_QUIET === '1';

function stamp() {
  return new Date().toISOString().slice(11, 19);
}

/** Build one line and print it. Never throws. */
function emit(level, mod, args) {
  try {
    const msg = args.map((a) => {
      if (a instanceof Error) return a.message;
      if (typeof a === 'object' && a !== null) {
        try { return JSON.stringify(a); } catch { return String(a); }
      }
      return String(a);
    }).join(' ');
    // One physical line: embedded newlines would break the app-side
    // line reader and the logcat view.
    const line = `${stamp()} [${level}] [${mod}] ${msg.replace(/\s+/g, ' ')}`;
    console.log(line);
    return line;
  } catch {
    return ''; // a dead pipe or a formatter failure must not kill the server
  }
}

const log = {
  dbg: QUIET ? () => {} : (mod, ...a) => emit('DBG', mod, a),
  inf: (mod, ...a) => emit('INF', mod, a),
  wrn: (mod, ...a) => emit('WRN', mod, a),
  err: (mod, ...a) => emit('ERR', mod, a),
  /** The exact line format, for the test suite. */
  LINE_RE: /^\d{2}:\d{2}:\d{2} \[(DBG|INF|WRN|ERR)\] \[[a-z]+\] .+$/,
};

module.exports = log;
