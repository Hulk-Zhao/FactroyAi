/**
 * smoke.mjs —— 后端冒烟测试（CI / 部署前自检，零依赖）。
 *
 * 覆盖：
 *   1. /api/health 健康检查；
 *   2. 登录：错误口令 401（含限流计数提示）、正确口令发 token；
 *   3. /api/auth/check：合法 token 200、伪造 token 401；
 *   4. 防刷限流：连续失败 RATE_MAX_FAILS 次后 429 锁定；
 *   5. 样例清单 API 鉴权：未登录 401、登录后 200。
 *
 * 用法：node backend/scripts/smoke.mjs   （非 0 退出码 = 失败）
 */

import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// 冒烟环境：随机端口、随机密钥、极短延迟，不触碰真实 .env / runtime。
process.env.PORT = '0';
process.env.AUTH_SECRET = 'smoke-test-secret-smoke-test-secret-1234';
process.env.AUTH_PASSWORD = '123';
process.env.LOGIN_DELAY_MS = '10';
process.env.RATE_MAX_FAILS = '3';
process.env.RATE_LOCK_MINUTES = '1';
process.env.STATIC_DIR = mkdtempSync(join(tmpdir(), 'hogo-smoke-static-'));
writeFileSync(join(process.env.STATIC_DIR, 'index.html'), '<!doctype html><title>smoke</title>');

const { createAppServer } = await import('../server.mjs');
const config = (await import('../lib/config.mjs')).config;

const server = createAppServer();
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

async function call(pathname, opts = {}) {
  const res = await fetch(base + pathname, opts);
  let body = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  return { status: res.status, body, headers: res.headers };
}

let passed = 0;
async function check(name, fn) {
  await fn();
  passed += 1;
  console.log(`  ok ${passed} - ${name}`);
}

try {
  await check('GET /api/health → 200', async () => {
    const r = await call('/api/health');
    assert.equal(r.status, 200);
    assert.equal(r.body.ok, true);
  });

  await check('POST login 错误口令 → 401', async () => {
    const r = await call('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: 'wrong' }),
    });
    assert.equal(r.status, 401);
    assert.equal(r.body.ok, false);
  });

  let token = '';
  await check('POST login 正确口令 → 200 + token', async () => {
    const r = await call('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: config.authPassword }),
    });
    assert.equal(r.status, 200);
    assert.ok(r.body.token && r.body.token.includes('.'));
    token = r.body.token;
  });

  await check('GET /api/auth/check 合法 token → 200', async () => {
    const r = await call('/api/auth/check', { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(r.status, 200);
    assert.equal(r.body.ok, true);
  });

  await check('GET /api/auth/check 伪造 token → 401', async () => {
    const r = await call('/api/auth/check', { headers: { Authorization: 'Bearer fake.sig' } });
    assert.equal(r.status, 401);
  });

  await check('GET /api/samples 未登录 → 401', async () => {
    const r = await call('/api/samples');
    assert.equal(r.status, 401);
  });

  await check('GET /api/samples 已登录 → 200（数组）', async () => {
    const r = await call('/api/samples', { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(r.status, 200);
    assert.ok(Array.isArray(r.body.samples));
  });

  await check('防刷限流：连续失败达上限 → 429 锁定', async () => {
    // 正确口令登录已把失败计数清零，此后需连续失败 RATE_MAX_FAILS（=3）次：
    // 前两次 401，第 3 次触发 429 锁定；锁定期间口令正确也 429。
    const login = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: 'x' }) };
    const r1 = await call('/api/auth/login', login);
    assert.equal(r1.status, 401);
    const r2 = await call('/api/auth/login', login);
    assert.equal(r2.status, 401);
    const r3 = await call('/api/auth/login', login);
    assert.equal(r3.status, 429, `第 3 次失败应触发 429，实际 ${r3.status}`);
    assert.ok(r3.body.message.includes('锁定'));
    const r4 = await call('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: config.authPassword }),
    });
    assert.equal(r4.status, 429, '锁定期间即使口令正确也应 429');
  });

  await check('静态托管 + SPA 回退', async () => {
    const home = await fetch(base + '/');
    assert.equal(home.status, 200);
    assert.ok((await home.text()).includes('smoke'));
    const spa = await fetch(base + '/capability');
    assert.equal(spa.status, 200);
    assert.ok((await spa.text()).includes('smoke'));
  });

  console.log(`\n冒烟通过：${passed} 项。`);
} finally {
  server.close();
}
