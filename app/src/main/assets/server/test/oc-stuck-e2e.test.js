'use strict';
// T-ML71 E2E: runOcChat melawan mock `opencode serve` — memverifikasi flow
// lengkap prompt >> thinking >> tool call >> hasil TANPA reset server manual:
//   A. flow normal: prompt → reasoning delta → text delta → tool → toolend →
//      step.ended → execution.succeeded → event app kontrak (done di akhir)
//   B. sesi sibuk: prompt pertama ditolak 409 → interrupt otomatis →
//      prompt kedua sukses (self-heal, tanpa reset server)
//   C. run macet: OpenCode berhenti mengirim event tanpa terminal → bridge
//      membatalkan zombie + memancarkan error TERLIHAT (bukan done palsu)
const { test, describe } = require('node:test');
const assert = require('node:assert');
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

function mockServe(handlers) {
  const state = { events: [], listeners: new Set(), sid: null, interrupted: 0 };
  const srv = http.createServer((req, res) => {
    // SSE HARUS di-attach sinkron di level request, BUKAN di dalam
    // `req.on('end')`: untuk GET tanpa body, 'end' (dan 'close'
    // IncomingMessage) menyala segera setelah header diparse, sehingga
    // listener yang didaftarkan di situ langsung terhapus → event tidak
    // pernah sampai ke bridge → watchdog silence salah menyala.
    if (req.url === '/api/event') {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write(': hi\n\n');
      state.listeners.add(res);
      res.on('close', () => state.listeners.delete(res));
      return;
    }
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const url = req.url;
      if (url === '/api/config') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end('{}');
        return;
      }
      if (url === '/api/session' && req.method === 'POST') {
        state.sid = 'ses_mock' + crypto.randomBytes(4).toString('hex');
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ data: { id: state.sid } }));
        return;
      }
      const m = url.match(/^\/api\/session\/[^/]+\/(prompt|interrupt|model)$/);
      if (m) {
        if (m[1] === 'interrupt') { state.interrupted++; res.writeHead(200); res.end(); return; }
        if (m[1] === 'model') { state.models = (state.models || 0) + 1; res.writeHead(204); res.end(); return; }
        handlers(JSON.parse(body || '{}'), state, res);
        return;
      }
      res.writeHead(404); res.end();
    });
  });
  const emit = (rec) => {
    state.events.push(rec);
    const frame = `data: ${JSON.stringify(rec)}\n\n`;
    for (const r of state.listeners) r.write(frame);
  };
  return new Promise((resolve) => srv.listen(0, '127.0.0.1', () => resolve({ srv, emit, state, port: srv.address().port })));
}

async function runChat(port, { messages, executor } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'oc-e2e-'));
  // "biner opencode" palsu: proxy HTTP yang meneruskan semua request ke mock
  // serve di `port` (bridge spawn proses ini dan memakai port dari --port).
  const proxy = [
    'const http=require("http");',
    `const target={host:"127.0.0.1",port:${port}};`,
    'const args=process.argv;const i=args.indexOf("--port");const listen=args[i+1];',
    'http.createServer((req,res)=>{',
    ' const r=http.request({...target,path:req.url,method:req.method,headers:req.headers},(p)=>{',
    '  res.writeHead(p.statusCode,p.headers);p.pipe(res);});',
    ' req.pipe(r);r.on("error",()=>{res.writeHead(502);res.end();});',
    '}).listen(Number(listen),"127.0.0.1");',
  ].join('');
  const bin = path.join(dir, 'fake-oc.js');
  fs.writeFileSync(bin, '#!/usr/bin/env node\n' + proxy);
  fs.chmodSync(bin, 0o755);
  process.env.SACHET_OC_BIN = bin; // lolos ocBundleAvailable + jadi child asli
  process.env.SACHET_OC_STATE_DIR = dir;
  process.env.SACHET_OC_SILENCE_MS = '700';
  process.env.SACHET_OC_TOOL_SILENCE_MS = '1200';
  delete require.cache[require.resolve('../oc-bridge')];
  const oc = require('../oc-bridge');
  const { runOcChat } = oc;
  const events = [];
  // Wrapper setia pada kontrak server.js: throw dari bridge (stuck, dsb.)
  // dikonversi menjadi event `error` + `done` yang terlihat di app.
  const emit = (e) => events.push(e);
  const added = await runOcChat({
    apiKey: 'k', providerId: 'ngodeai', model: 'm',
    executor,
    messages: messages || [{ role: 'user', content: 'kerjakan' }],
    emit,
    workdir: fs.mkdtempSync(path.join(os.tmpdir(), 'oc-wd-')),
  }).catch((e) => { emit({ type: 'error', message: e.message || String(e) }); emit({ type: 'done' }); return []; });
  // Bersihkan child serve (proxy) agar test tidak menggantung event loop.
  await oc.killServe();
  return { events, added, dir };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

describe('T-ML71 E2E flow', () => {
  test('A: flow normal — prompt→think→text→tool→toolend→message→done', async () => {
    const { srv, emit, state, port } = await mockServe((_, st, res) => {
      res.writeHead(200); res.end();
      (async () => {
        const sid = st.sid;
        await sleep(60);
        emit({ type: 'session.reasoning.delta', data: { sessionID: sid, assistantMessageID: 'a1', delta: 'berpikir…' } });
        emit({ type: 'session.text.delta', data: { sessionID: sid, assistantMessageID: 'a1', delta: 'mulai' } });
        emit({ type: 'session.tool.called', data: { sessionID: sid, assistantMessageID: 'a1', id: 't1', name: 'bash', input: { command: 'ls' } } });
        emit({ type: 'session.tool.succeeded', data: { sessionID: sid, assistantMessageID: 'a1', id: 't1', output: 'file.txt' } });
        emit({ type: 'session.step.ended', data: { sessionID: sid, assistantMessageID: 'a1', text: 'mulai selesai', finish: 'tool-calls' } });
        emit({ type: 'session.text.delta', data: { sessionID: sid, assistantMessageID: 'a2', delta: 'jawab akhir' } });
        emit({ type: 'session.step.ended', data: { sessionID: sid, assistantMessageID: 'a2', text: 'jawab akhir', finish: 'stop' } });
        emit({ type: 'session.usage.updated', data: { sessionID: sid, tokens: { input: 10, output: 5 } } });
        emit({ type: 'session.execution.succeeded', data: { sessionID: sid } });
      })();
    });
    const { events, added } = await runChat(port);
    srv.close();
    const types = events.map((e) => e.type);
    assert.deepStrictEqual(types, ['think', 'text', 'tool', 'toolend', 'message', 'toolresult', 'text', 'message', 'usage', 'done']);
    assert.strictEqual(added.filter((m) => m.role === 'assistant').length, 2);
    assert.ok(state.interrupted === 0, 'flow normal tidak perlu interrupt');
  });

  test('B: sesi sibuk → interrupt self-heal → prompt sukses (tanpa reset server)', async () => {
    let first = true;
    const { srv, emit, state, port } = await mockServe((_, st, res) => {
      if (first) {
        first = false;
        res.writeHead(409, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: 'Session is busy' }));
        return;
      }
      res.writeHead(200); res.end();
      (async () => {
        const sid = st.sid;
        await sleep(40);
        emit({ type: 'session.text.delta', data: { sessionID: sid, assistantMessageID: 'a1', delta: 'pulih sendiri' } });
        emit({ type: 'session.step.ended', data: { sessionID: sid, assistantMessageID: 'a1', text: 'pulih sendiri', finish: 'stop' } });
        emit({ type: 'session.execution.succeeded', data: { sessionID: sid } });
      })();
    });
    const { events } = await runChat(port);
    srv.close();
    assert.strictEqual(state.interrupted, 1, 'interrupt zombie terkirim otomatis');
    assert.deepStrictEqual(events.map((e) => e.type), ['text', 'message', 'done']);
  });


  test('C: run macet (tool tanpa akhir, tanpa terminal) → interrupt + error terlihat', async () => {
    const { srv, emit, state, port } = await mockServe((_, st, res) => {
      res.writeHead(200); res.end();
      (async () => {
        const sid = st.sid;
        await sleep(40);
        emit({ type: 'session.tool.called', data: { sessionID: sid, assistantMessageID: 'a1', id: 't1', name: 'bash', input: { command: 'apt-get install' } } });
        // TIDAK ADA toolend/step.ended/terminal — run menggantung selamanya
      })();
    });
    const { events } = await runChat(port);
    srv.close();
    assert.strictEqual(state.interrupted, 1, 'run zombie di-interrupt (bukan dibiarkan hidup)');
    const err = events.find((e) => e.type === 'error');
    assert.ok(err, 'error TERLIHAT ke app (bukan done palsu)');
    assert.match(err.message, /macet|berhenti di tengah/);
    assert.ok(events[events.length - 1].type === 'done', 'stream tetap ditutup rapi');
  });

  test('D: dual-model — rencana orchestrator pakai tool → run natural → switch executor → eksekusi tanpa interrupt', async () => {
    let phase = 0; // 0 = fase orchestrator, 1 = fase executor
    const { srv, emit, state, port } = await mockServe((body, st, res) => {
      const isExec = phase === 1;
      res.writeHead(200); res.end();
      (async () => {
        const sid = st.sid;
        await sleep(40);
        if (!isExec) {
          phase = 1;
          // Orchestrator: berpikir + rencana memakai tool (baca konteks).
          emit({ type: 'session.reasoning.delta', data: { sessionID: sid, assistantMessageID: 'a1', delta: 'analisis…' } });
          emit({ type: 'session.tool.called', data: { sessionID: sid, assistantMessageID: 'a1', id: 't1', name: 'read_file', input: { path: 'a.js' } } });
          emit({ type: 'session.tool.succeeded', data: { sessionID: sid, assistantMessageID: 'a1', id: 't1', output: 'isi' } });
          emit({ type: 'session.step.ended', data: { sessionID: sid, assistantMessageID: 'a1', text: 'rencana siap', finish: 'tool-calls' } });
          emit({ type: 'session.execution.succeeded', data: { sessionID: sid } }); // run natural
        } else {
          // Executor: menjalankan seluruh rencana (tool + selesai).
          emit({ type: 'session.tool.called', data: { sessionID: sid, assistantMessageID: 'a2', id: 't2', name: 'bash', input: { command: 'make' } } });
          emit({ type: 'session.tool.succeeded', data: { sessionID: sid, assistantMessageID: 'a2', id: 't2', output: 'ok' } });
          emit({ type: 'session.step.ended', data: { sessionID: sid, assistantMessageID: 'a2', text: 'rencana tuntas', finish: 'stop' } });
          emit({ type: 'session.execution.succeeded', data: { sessionID: sid } });
        }
      })();
    });
    const { events } = await runChat(port, { executor: 'm-exec' });
    srv.close();
    assert.strictEqual(state.interrupted, 0, 'run orchestrator TIDAK di-interrupt (selesai natural)');
    assert.strictEqual(state.models, 1, 'switch model ke executor tepat 1x setelah run selesai');
    assert.ok(events.some((e) => e.type === 'model' && e.role === 'executor'), 'event model/executor ter-emit');
    const types = events.map((e) => e.type);
    assert.deepStrictEqual(types, ['think', 'tool', 'toolend', 'message', 'toolresult', 'model', 'tool', 'toolend', 'message', 'toolresult', 'done']);
  });
});
