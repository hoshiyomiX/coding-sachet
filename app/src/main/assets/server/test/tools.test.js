'use strict';
// Unit tests for the tool layer: sandbox, file ops, bash.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

// Point the sandbox at a temp dir before requiring config/tools.
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sachet-tools-'));
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
  const w = writeFile('/etc/sachet-evil', 'nope');
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

// ---------- T-ML27 P1: process-group hygiene ----------

const { lockHint } = require('../tools');

/** Resolve after ms; the reaper timers are short but real. */
const nap = (ms) => new Promise((r) => setTimeout(r, ms));

/** True when a PID is gone (ESRCH). */
function pidGone(pid) {
  try { process.kill(pid, 0); return false; } catch (e) { return e.code === 'ESRCH'; }
}

test('bash timeout kills the whole process group, not just bash (T-ML27 P1)', async () => {
  const old = config.bashTimeoutMs;
  config.bashTimeoutMs = 700;
  try {
    // A grandchild that outlives bash and ignores nothing: the T-ML27
    // field shape was `apt-get install` hanging past the timeout while
    // only bash died, leaving the orphan holding the dpkg lock.
    const r = await runBash('sleep 30 & echo $! > slow.pid; sleep 30');
    assert.strictEqual(r.timedOut, true);
    const orphan = Number(fs.readFileSync(path.join(config.workdir, 'slow.pid'), 'utf8').trim());
    assert.ok(Number.isInteger(orphan) && orphan > 1, 'pid tertulis');
    // TERM fires at ~700ms, KILL at ~+2000ms; poll until the orphan is gone.
    const deadline = Date.now() + 6000;
    while (Date.now() < deadline && !pidGone(orphan)) await nap(100);
    assert.ok(pidGone(orphan), 'proses yatim ikut dibunuh bersama grupnya');
  } finally {
    config.bashTimeoutMs = old;
  }
});

test('a leaked background job is reaped when the command finishes (T-ML27 P1)', async () => {
  // bash exits instantly; `sleep 30 &` would normally survive as an orphan
  // (and keep the pipes open). The tool call must stay one-shot.
  const t0 = Date.now();
  const r = await runBash('sleep 30 & echo $! > leak.pid; echo selesai');
  assert.strictEqual(r.ok, true);
  assert.ok(r.output.includes('selesai'));
  assert.ok(Date.now() - t0 < 5000, 'tidak menggantung menunggu proses bocor');
  const leaked = Number(fs.readFileSync(path.join(config.workdir, 'leak.pid'), 'utf8').trim());
  const deadline = Date.now() + 4000;
  while (Date.now() < deadline && !pidGone(leaked)) await nap(100);
  assert.ok(pidGone(leaked), 'proses latar belakang dibersihkan setelah perintah selesai');
});

test('lockHint flags dpkg lock contention and leaves clean output alone (T-ML27 P1)', () => {
  const locked = lockHint('E: Could not get lock /var/lib/dpkg/lock-frontend');
  assert.ok(locked.includes('catatan: ada proses apt/dpkg'));
  const clean = lockHint('Semua paket terpasang tanpa galat');
  assert.strictEqual(clean, 'Semua paket terpasang tanpa galat');
});
