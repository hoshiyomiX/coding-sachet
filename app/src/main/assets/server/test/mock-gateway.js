'use strict';
// A scripted mock of the APInex gateway (OpenAI-compatible SSE)
// used by the test suite. No network access needed.

const http = require('http');

/**
 * Start a mock gateway.
 *
 * script: list of replies, one per received /chat/completions request:
 *   { kind: 'text', text }                → plain assistant text
 *   { kind: 'tools', text, calls: [{id, name, args}] } → tool_calls round
 *   { kind: 'error', status, message }    → non-200 reply (e.g. 429 rate limit)
 *
 * Returns { server, port, requests, close() }; `requests` collects the
 * parsed bodies of every chat request the mock received.
 */
function startMockGateway(script) {
  const requests = [];
  const modelRequests = [];
  let n = 0;
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      if (req.url === '/v1/models' && req.method === 'GET') {
        modelRequests.push({ auth: req.headers.authorization });
        if (req.headers.authorization !== 'Bearer sk-test-123') {
          res.writeHead(401, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'Invalid token' } }));
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ data: [{ id: 'test-mini' }, { id: 'test-pro' }] }));
        return;
      }

      if (req.url === '/v1/chat/completions' && req.method === 'POST') {
        const parsed = JSON.parse(body);
        requests.push(parsed);

        const reply = script[Math.min(n, script.length - 1)];
        n++;
        if (!reply) {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'script exhausted' } }));
          return;
        }
        if (reply.kind === 'error') {
          res.writeHead(reply.status, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: { message: reply.message } }));
          return;
        }

        // SSE reply
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        const send = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);
        if (reply.kind === 'tools') {
          if (reply.text) {
            send({ choices: [{ delta: { role: 'assistant', content: reply.text }, finish_reason: null }] });
          }
          reply.calls.forEach((c, i) => {
            send({ choices: [{ delta: { tool_calls: [{ index: i, id: c.id, function: { name: c.name, arguments: '' } }] }, finish_reason: null }] });
            // arguments arrive in two chunks, like real gateways do
            const s = JSON.stringify(c.args);
            const mid = Math.ceil(s.length / 2);
            send({ choices: [{ delta: { tool_calls: [{ index: i, function: { arguments: s.slice(0, mid) } }] }, finish_reason: null }] });
            send({ choices: [{ delta: { tool_calls: [{ index: i, function: { arguments: s.slice(mid) } }] }, finish_reason: null }] });
          });
          send({ choices: [{ delta: {}, finish_reason: 'tool_calls' }] });
        } else {
          const t = reply.text || '';
          send({ choices: [{ delta: { role: 'assistant', reasoning_content: 'berpikir sejenak…' }, finish_reason: null }] });
          send({ choices: [{ delta: { content: t.slice(0, Math.ceil(t.length / 2)) }, finish_reason: null }] });
          send({ choices: [{ delta: { content: t.slice(Math.ceil(t.length / 2)) }, finish_reason: null }] });
          send({ choices: [{ delta: {}, finish_reason: 'stop' }] });
        }
        res.write('data: [DONE]\n\n');
        res.end();
        return;
      }

      res.writeHead(404);
      res.end('not found');
    });
  });

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      resolve({
        server,
        port: server.address().port,
        requests,
        modelRequests,
        close: () => new Promise((r) => server.close(r)),
      });
    });
  });
}

/** Collect the SSE `data:` events of a POST into an array, resolving on done/error. */
function collectSse(port, path, headers, body) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port, path, method: 'POST', headers },
      (res) => {
        if (res.statusCode !== 200) {
          let b = '';
          res.on('data', (c) => { b += c; });
          res.on('end', () => reject(new Error(`HTTP ${res.statusCode}: ${b}`)));
          return;
        }
        const events = [];
        let buf = '';
        res.on('data', (c) => {
          buf += c.toString('utf8');
          let nl;
          while ((nl = buf.indexOf('\n')) >= 0) {
            const line = buf.slice(0, nl).replace(/\r$/, '');
            buf = buf.slice(nl + 1);
            if (line.startsWith('data:')) {
              const p = line.slice(5).trim();
              if (!p) continue;
              try { events.push(JSON.parse(p)); } catch { /* comment line */ }
            }
          }
        });
        res.on('end', () => resolve(events));
        res.on('error', reject);
      }
    );
    req.on('error', reject);
    if (body) req.write(typeof body === 'string' ? body : JSON.stringify(body));
    req.end();
  });
}

/** Simple JSON GET. */
function getJson(port, path, headers = {}) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path, headers }, (res) => {
      let b = '';
      res.on('data', (c) => { b += c; });
      res.on('end', () => {
        try { resolve({ status: res.statusCode, body: JSON.parse(b) }); }
        catch { resolve({ status: res.statusCode, body: b }); }
      });
    }).on('error', reject);
  });
}

module.exports = { startMockGateway, collectSse, getJson };
