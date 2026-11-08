'use strict';
// Tools the coding agent may use inside the Ubuntu guest.
// Everything runs under the proot filesystem; paths are resolved and
// sandboxed before any filesystem access.

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { config } = require('./config');

// ---------- path sandbox ----------

/** Resolve `p` and make sure it stays inside one of the allowed roots.
 *  The root "/" itself is a valid allowed root (root mode): every absolute
 *  path lives under it — the naive `startsWith('/' + sep)` would never
 *  match, so it needs its own branch. */
function safePath(p) {
  if (typeof p !== 'string' || p.length === 0 || p.includes('\0')) return null;
  const abs = path.resolve(p);
  if (!path.isAbsolute(abs)) return null;
  const ok = config.allowedRoots.some((r) =>
    r === '/' ? true : (abs === r || abs.startsWith(r + path.sep)));
  return ok ? abs : null;
}

/** Same, but for a path that is allowed not to exist yet (parents must). */
function safeNewPath(p) {
  const abs = safePath(p);
  if (abs === null) return null;
  // The closest existing ancestor must itself be inside the sandbox.
  let dir = abs;
  while (!fs.existsSync(dir)) dir = path.dirname(dir);
  return safePath(dir) === null ? null : abs;
}

// ---------- tool implementations ----------

/** List a directory: names, sizes, types. Depth-first, bounded. */
function listDir(dirPath, depth = 2) {
  const abs = safePath(dirPath);
  if (abs === null) return { ok: false, error: 'Path is outside the sandbox' };
  let st;
  try { st = fs.statSync(abs); } catch (e) { return { ok: false, error: `stat: ${e.message}` }; }
  if (!st.isDirectory()) return { ok: false, error: 'Not a directory' };

  const walk = (dir, level, budget) => {
    const out = [];
    let names;
    try { names = fs.readdirSync(dir).sort(); } catch (e) { return [{ name: `(unreadable: ${e.message})`, dir: false, size: 0 }]; }
    for (const name of names) {
      if (budget.count-- <= 0) { out.push({ name: '…', dir: false, size: 0, more: true }); break; }
      const full = path.join(dir, name);
      let s = null;
      try { s = fs.lstatSync(full); } catch { /* raced or unreadable */ }
      const entry = { name, dir: !!(s && s.isDirectory()), size: s ? s.size : 0 };
      if (s && s.isSymbolicLink()) entry.symlink = true;
      out.push(entry);
      if (entry.dir && !entry.symlink && level > 0) {
        for (const child of walk(full, level - 1, budget)) {
          out.push({ name: name + '/' + child.name, dir: child.dir, size: child.size });
        }
      }
    }
    return out;
  };
  return { ok: true, path: abs, entries: walk(abs, depth, { count: 2000 }) };
}

/** Read a file as text (best effort for binary files). Only the first
 *  readMaxBytes are ever read from disk, so a huge file cannot blow up
 *  the server's memory (the old code slurped the whole file first). */
function readFile(filePath) {
  const abs = safePath(filePath);
  if (abs === null) return { ok: false, error: 'Path is outside the sandbox' };
  let st;
  try { st = fs.statSync(abs); } catch (e) { return { ok: false, error: `stat: ${e.message}` }; }
  if (!st.isFile()) return { ok: false, error: 'Not a file' };
  const truncated = st.size > config.readMaxBytes;
  let buf;
  if (truncated) {
    const fd = fs.openSync(abs, 'r');
    try {
      buf = Buffer.alloc(config.readMaxBytes);
      fs.readSync(fd, buf, 0, config.readMaxBytes, 0);
    } finally {
      fs.closeSync(fd);
    }
  } else {
    buf = fs.readFileSync(abs);
  }
  const text = buf.toString('utf8');
  // A NUL byte early in the file almost always means binary content.
  const binary = text.slice(0, 8000).includes('\u0000');
  return {
    ok: true,
    path: abs,
    size: st.size,
    binary,
    truncated,
    content: binary ? `(file biner, ${st.size} byte)` : text,
  };
}

/** Write a text file, creating parent directories. */
function writeFile(filePath, content) {
  const abs = safeNewPath(filePath);
  if (abs === null) return { ok: false, error: 'Path is outside the sandbox' };
  if (typeof content !== 'string') return { ok: false, error: 'content must be a string' };
  if (Buffer.byteLength(content, 'utf8') > config.writeMaxBytes) return { ok: false, error: 'File too large' };
  try {
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content);
    return { ok: true, path: abs, bytes: Buffer.byteLength(content, 'utf8') };
  } catch (e) { return { ok: false, error: e.message }; }
}

/** Run a shell command in the workdir with a timeout and output cap. */
function runBash(command, onOutput) {
  return new Promise((resolve) => {
    if (typeof command !== 'string' || command.length === 0 || command.length > 64 * 1024) {
      resolve({ ok: false, error: 'Invalid command' });
      return;
    }
    fs.mkdirSync(config.workdir, { recursive: true });
    const child = spawn('/bin/bash', ['-c', command], {
      cwd: config.workdir,
      env: {
        PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
        HOME: '/root',
        LANG: 'C.UTF-8',
        TERM: 'dumb',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let out = Buffer.alloc(0);
    let killed = false;
    const timer = setTimeout(() => {
      killed = true;
      child.kill('SIGKILL');
    }, config.bashTimeoutMs);

    const push = (chunk) => {
      out = out.length + chunk.length > config.bashMaxOutput
        ? Buffer.concat([out, chunk]).subarray(0, config.bashMaxOutput)
        : Buffer.concat([out, chunk]);
      if (onOutput) onOutput(out.toString('utf8'));
    };
    child.stdout.on('data', push);
    child.stderr.on('data', push);
    child.on('error', (e) => {
      clearTimeout(timer);
      resolve({ ok: false, error: `spawn: ${e.message}` });
    });
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      resolve({
        ok: code === 0,
        code,
        signal: signal || null,
        timedOut: killed,
        output: out.toString('utf8') + (out.length >= config.bashMaxOutput ? '\n…(output dipotong)' : ''),
      });
    });
  });
}

// ---------- tool registry exposed to the model ----------

const tools = [
  {
    name: 'bash',
    description: `Run a shell command inside the Ubuntu sandbox (workdir ${config.workdir}). Use it for git, grep, find, installing packages with apt, running builds and tests. Output is capped.`,
    parameters: {
      type: 'object',
      properties: {
        command: { type: 'string', description: 'The bash command to run' },
      },
      required: ['command'],
    },
    run: async (args) => {
      const r = await runBash(args.command);
      if (r.error) return r;
      const head = `exit=${r.code}${r.signal ? ` signal=${r.signal}` : ''}${r.timedOut ? ' (timeout)' : ''}`;
      return { ok: r.ok, output: `${head}\n${r.output || '(no output)'}` };
    },
  },
  {
    name: 'read_file',
    description: 'Read a text file (source code, config, notes). Returns the content, possibly truncated for large files.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Absolute path of the file' },
      },
      required: ['path'],
    },
    run: async (args) => {
      const r = readFile(args.path);
      if (!r.ok) return r;
      return { ok: true, output: r.binary || r.truncated ? r.content : r.content, path: r.path, size: r.size, truncated: r.truncated, binary: r.binary };
    },
  },
  {
    name: 'write_file',
    description: 'Create or overwrite a text file. Parent directories are created automatically.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Absolute path of the file' },
        content: { type: 'string', description: 'Full file content' },
      },
      required: ['path', 'content'],
    },
    run: async (args) => writeFile(args.path, args.content),
  },
  {
    name: 'list_dir',
    description: 'List a directory tree (bounded depth). Use it to explore the project before editing.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Absolute path of the directory' },
        depth: { type: 'number', description: 'Depth to walk (default 2, max 4)' },
      },
      required: ['path'],
    },
    run: async (args) => {
      const r = listDir(args.path, Math.min(Number(args.depth) || 2, 4));
      if (!r.ok) return r;
      return {
        ok: true,
        output: r.entries.map((e) => `${e.dir ? 'd' : '-'} ${String(e.size).padStart(9)}  ${e.name}`).join('\n') || '(empty)',
      };
    },
  },
];

const toolByName = Object.fromEntries(tools.map((t) => [t.name, t]));

module.exports = { tools, toolByName, safePath, safeNewPath, listDir, readFile, writeFile, runBash };
