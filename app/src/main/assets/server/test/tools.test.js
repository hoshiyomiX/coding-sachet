'use strict';
// Unit tests for the tool layer: sandbox, file ops, bash.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

// Point the sandbox at a temp dir before requiring config/tools.
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'apx-tools-'));
process.env.SACHET_WORKDIR = path.join(ROOT, 'root', 'work');
const { config } = require('../config');
config.allowedRoots = [path.join(ROOT, 'root'), path.join(ROOT, 'tmp')];
fs.mkdirSync(process.env.SACHET_WORKDIR, { recursive: true });
fs.mkdirSync(path.join(ROOT, 'tmp'), { recursive: true });

const { safePath, listDir, readFile, writeFile, runBash } = require('../tools');

test('safePath keeps paths inside the allowed roots', () => {
  const inside = path.join(ROOT, 'root/work/a.txt');
  assert.strictEqual(safePath(inside), inside);
  assert.strictEqual(safePath(path.join(ROOT, 'root/../../etc/passwd')), null);
  assert.strictEqual(safePath('/etc/passwd'), null);
  assert.strictEqual(safePath(''), null);
  assert.strictEqual(safePath(null), null);
});

test('write + read a file round trip', () => {
  const p = path.join(ROOT, 'root/work/hello.txt');
  const w = writeFile(p, 'halo dunia');
  assert.strictEqual(w.ok, true);
  const r = readFile(p);
  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.content, 'halo dunia');
  assert.strictEqual(r.binary, false);
});

test('write rejects paths outside the sandbox', () => {
  const w = writeFile('/etc/apx-evil', 'nope');
  assert.strictEqual(w.ok, false);
});

test('read reports a missing file as an error', () => {
  const r = readFile(path.join(ROOT, 'root/work/tidak-ada.txt'));
  assert.strictEqual(r.ok, false);
});

test('listDir walks subdirectories', () => {
  writeFile(path.join(ROOT, 'root/work/proyek/main.py'), 'print(1)');
  fs.mkdirSync(path.join(ROOT, 'root/work/proyek/util'), { recursive: true });
  writeFile(path.join(ROOT, 'root/work/proyek/util/x.py'), 'x = 1');
  const r = listDir(path.join(ROOT, 'root/work/proyek'), 2);
  assert.strictEqual(r.ok, true);
  const names = r.entries.map((e) => e.name);
  assert.ok(names.includes('main.py'));
  assert.ok(names.includes('util/x.py'));
});

test('bash runs in the workdir and reports the exit code', async () => {
  const r = await runBash('pwd && echo halo');
  assert.strictEqual(r.ok, true);
  assert.ok(r.output.includes(process.env.SACHET_WORKDIR));
  assert.ok(r.output.includes('halo'));

  const bad = await runBash('exit 3');
  assert.strictEqual(bad.ok, false);
  assert.strictEqual(bad.code, 3);
});

test('bash output is truncated to the cap', async () => {
  const r = await runBash('yes ABCDEFGH | head -c 300000');
  assert.ok(r.output.length <= config.bashMaxOutput + 100);
  assert.ok(r.output.includes('dipotong'));
});

test('bash timeout kills the process', async () => {
  const old = config.bashTimeoutMs;
  config.bashTimeoutMs = 500;
  try {
    const r = await runBash('sleep 5');
    assert.strictEqual(r.ok, false);
    assert.strictEqual(r.timedOut, true);
  } finally {
    config.bashTimeoutMs = old;
  }
});
