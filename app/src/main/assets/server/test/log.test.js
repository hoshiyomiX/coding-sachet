'use strict';
// T-ML16: the standardized log format is a contract — one shape for every
// server line, so logcat / the in-app server log stays machine-greppable.

const test = require('node:test');
const assert = require('node:assert');

// Capture console.log output without letting it pollute the test report.
function capture(fn) {
  const orig = console.log;
  const lines = [];
  console.log = (s) => lines.push(String(s));
  try {
    fn();
  } finally {
    console.log = orig;
  }
  return lines;
}

test('log lines follow the standardized format HH:MM:SS [LVL] [mod] msg', () => {
  const { LINE_RE } = require('../log');
  const lines = capture(() => {
    require('../log').dbg('agt', 'gateway NIM mulai');
    require('../log').inf('srv', 'chat mulai nvidia model msgs=4');
    require('../log').wrn('net', 'retry 1/3 integrate.api.nvidia.com:', 'fetch failed (ENOTFOUND)');
    require('../log').err('tool', 'bash spawn gagal:', 'ENOENT');
  });
  assert.equal(lines.length, 4);
  for (const line of lines) assert.match(line, LINE_RE, `baris tidak sesuai format: ${line}`);
});

test('multi-arg messages are joined and flattened to one line', () => {
  const lines = capture(() => require('../log').inf('srv', 'dua', 'argumen', { a: 1 }));
  assert.equal(lines.length, 1);
  assert.match(lines[0], /^\d{2}:\d{2}:\d{2} \[INF\] \[srv\] dua argumen \{"a":1\}$/);
});

test('Error objects log their message, never a stack', () => {
  const lines = capture(() => require('../log').err('agt', 'gagal:', new Error('boom')));
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes('boom'));
  assert.ok(!lines[0].includes('at '));
});

test('embedded newlines are collapsed so the app-side line reader stays in sync', () => {
  const lines = capture(() => require('../log').inf('srv', 'baris\nbaru\tdan tab'));
  assert.equal(lines.length, 1);
  assert.ok(!lines[0].includes('\n'));
});
