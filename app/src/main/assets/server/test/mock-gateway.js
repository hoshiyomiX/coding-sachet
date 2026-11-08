'use strict';
// A scripted mock gateway (OpenAI-compatible SSE), used by the test
// suite to impersonate any provider (APInex or NVIDIA NIM). No network.

const http = require('http');

/**
 * Start a mock gateway.
 *
 * script: list of replies, one per received /chat/completions request:
 *   { kind: 'text', text }                → plain assistant text
 *   { kind: 'tools', text, calls: [{id, name, args}] } → tool_calls round
 *   { kind: 'error', status, message }    → non-200 reply (e.g. 429 rate limit)
 *
 * opts.key:    the bearer token the mock accepts (default 'sk-test-123')
 * opts.models: model ids served by /v1/models
 * opts.flakyChat:  destroy the socket of the first N chat requests before
 *                  replying (simulates a VPN bring-up connect failure)
 * opts.flakyModels: same for /v1/models
 * opts.quota:  serve the APInex-native quota endpoints:
 *              { mode, unlimited, tokenLimit, tokensUsed, tokensRemaining, balanceUsd }
 * opts.billing: serve the OpenAI-style billing pair instead of the native
 *              endpoints (which answer 404): { hardLimitUsd, totalUsage }
 *
 * Returns { server, port, requests, chatAuths, modelRequests, chatAttempts,
 *           modelAttempts, quotaRequests, close() };
 * `requests` collects parsed chat bodies, `chatAuths` their Authorization
 * headers, `modelRequests` the /v1/models hits, `chatAttempts`/`modelAttempts`
 * count every connection (including flaky-destroyed ones).
 */
function startMockGateway(script, opts = {}) {
  const key = opts.key || 'sk-test-123';
  const models = opts.models || ['test-mini', 'test-pro'];
  const requests = [];
  const chatAuths = [];
  const modelRequests = [];
  let chatAttempts = 0;
  let modelAttempts = 0;
  let quotaRequests = 0;
  let n = 0;
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const unauthorized = () => {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'Invalid token' } }));
      };

      if (req.url === '/v1/models' && req.method === 'GET') {
        modelAttempts++;
        if (opts.flakyModels && modelAttempts <= opts.flakyModels) {
          res.socket.destroy();
          return;
        }
        modelRequests.push({ auth: req.headers.authorization });
        if (req.headers.authorization !== `Bearer ${key}`) return unauthorized();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ data: models.map((id) => ({ id })) }));
        return;
      }

      // APInex-native quota endpoints (apinex.bond/developers/balance).
      if (req.method === 'GET' && (req.url === '/v1/subscription' || req.url === '/v1/balance')) {
        quotaRequests++;
        if (!opts.quota) {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'not found' } }));
          return;
        }
        if (req.headers.authorization !== `Bearer ${key}`) return unauthorized();
        if (req.url === '/v1/subscription') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            object: 'subscription',
            mode: opts.quota.mode || 'free',
            plan: null,
            plan_label: 'Free',
            unlimited: opts.quota.unlimited === true,
            token_limit: opts.quota.tokenLimit ?? null,
            tokens_used: opts.quota.tokensUsed ?? 0,
            tokens_remaining: opts.quota.unlimited === true ? null : opts.quota.tokensRemaining,
            expires_at_utc: null,
            resets_at_utc: null,
            allowed: true,
          }));
        } else {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({
            object: 'balance',
            balance_usd: opts.quota.balanceUsd ?? 0,
            reserved_usd: 0,
            rpm: 40,
            rpm_plan: 'free',
            api_key: { id: 'k_1', spent_usd: 1.4, reserved_usd: 0 },
          }));
        }
        return;
      }

      // OpenAI-style billing pair (the new-api fallback).
      if (req.method === 'GET' && req.url.startsWith('/v1/dashboard/billing/')) {
        quotaRequests++;
        if (!opts.billing) {
          res.writeHead(404);
          res.end('not found');
          return;
        }
        if (req.headers.authorization !== `Bearer ${key}`) return unauthorized();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.startsWith('/v1/dashboard/billing/subscription')) {
          res.end(JSON.stringify({
            object: 'billing_subscription',
            has_payment_method: true,
            soft_limit_usd: opts.billing.hardLimitUsd,
            hard_limit_usd: opts.billing.hardLimitUsd,
            system_hard_limit_usd: opts.billing.hardLimitUsd,
            access_until: 0,
          }));
        } else {
          res.end(JSON.stringify({ object: 'list', total_usage: opts.billing.totalUsage }));
        }
        return;
      }

      if (req.url === '/v1/chat/completions' && req.method === 'POST') {
        chatAttempts++;
        if (opts.flakyChat && chatAttempts <= opts.flakyChat) {
          res.socket.destroy();
          return;
        }
        const parsed = JSON.parse(body);
        requests.push(parsed);
        chatAuths.push(req.headers.authorization);

        const reply = script[Math.min(n, script.length - 1)];
        n++;
        if (!reply) {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'script exhausted' } }));
          return;
        }
        if (reply.kind === 'error') {
          res.writeHead(reply.status, {
            'Content-Type': 'application/json',
            ...(reply.headers || {}), // e.g. { 'retry-after': '1' } on 429s
          });
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
            // Gateways that omit the tool_call id (some models do) send
            // only index + function — mirror that when c.id is falsy.
            const head = { index: i };
            if (c.id) head.id = c.id;
            send({ choices: [{ delta: { tool_calls: [{ ...head, function: { name: c.name, arguments: '' } }] }, finish_reason: null }] });
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
        chatAuths,
        modelRequests,
        // counters must be read live (a plain property would snapshot 0)
        get chatAttempts() { return chatAttempts; },
        get modelAttempts() { return modelAttempts; },
        get quotaRequests() { return quotaRequests; },
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

/** JSON POST; resolves with { status, body }. */
function postJson(port, path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1', port, path, method: 'POST',
        headers: { ...headers, 'content-type': 'application/json' },
      },
      (res) => {
        let b = '';
        res.on('data', (c) => { b += c; });
        res.on('end', () => {
          try { resolve({ status: res.statusCode, body: JSON.parse(b) }); }
          catch { resolve({ status: res.statusCode, body: b }); }
        });
      }
    );
    req.on('error', reject);
    req.write(typeof body === 'string' ? body : JSON.stringify(body));
    req.end();
  });
}

module.exports = { startMockGateway, collectSse, getJson, postJson };
