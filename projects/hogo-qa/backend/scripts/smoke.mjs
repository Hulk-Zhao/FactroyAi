/**
 * smoke.mjs —— 后端冒烟测试（CI / 部署前自检，零依赖）。
 *
 * 覆盖：
 *   1. /api/health 健康检查；
 *   2. 图形验证码：签发 200 + SVG、登录缺验证码 401、验证码一次性作废、大小写不敏感；
 *   3. 登录：错误口令 401、正确口令 + 正确验证码发 token；
 *   4. /api/auth/check：合法 token 200、伪造 token 401；
 *   5. 防刷限流：连续失败 RATE_MAX_FAILS 次后 429 锁定；
 *   6. 样例清单 API 鉴权：未登录 401、登录后 200；
 *   7. 静态托管 + SPA 回退。
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
const { peekCaptchaAnswer } = await import('../lib/captcha.mjs');

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

let ipSeq = 0;
/** 每个用例独立 IP，避免用例之间互相消耗失败次数 / 触发锁定。 */
function nextIp() {
  ipSeq += 1;
  return `10.0.0.${ipSeq}`;
}

/** 取一张验证码；答案只存在于服务端，测试进程内直接读（HTTP 应答不回明文）。 */
async function getCaptcha(ip = nextIp()) {
  const r = await call('/api/auth/captcha', { headers: { 'X-Forwarded-For': ip } });
  assert.equal(r.status, 200, `验证码签发应 200，实际 ${r.status}`);
  assert.ok(r.body.captchaId, '应返回 captchaId');
  assert.ok(typeof r.body.svg === 'string' && r.body.svg.includes('<svg'), '应返回 SVG');
  assert.equal(r.body.ttlS, Math.round(config.captchaTtlMs / 1000));
  const answer = peekCaptchaAnswer(r.body.captchaId);
  assert.match(String(answer), /^[2-9A-HJ-NP-Z]{4}$/, `验证码答案格式异常：${answer}`);
  return { ip, id: r.body.captchaId, answer, raw: r.body };
}

/** 带 IP 的登录调用。 */
async function loginWith(ip, payload) {
  return call('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': ip },
    body: JSON.stringify(payload),
  });
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

  await check('GET /api/auth/captcha → 200 + SVG（一次性答案不回显）', async () => {
    const cap = await getCaptcha();
    assert.ok(!JSON.stringify(cap.raw).includes(cap.answer), '答案不应出现在签发响应里');
  });

  await check('POST login 缺少验证码 → 401（验证码必填）', async () => {
    const ip = nextIp();
    const r = await loginWith(ip, { password: config.authPassword });
    assert.equal(r.status, 401);
    assert.ok(r.body.message.includes('验证码'), `应提示验证码，实际：${r.body.message}`);
  });

  await check('验证码一次性：登录成功后复用同一 id → 401', async () => {
    const cap = await getCaptcha();
    const first = await loginWith(cap.ip, {
      password: config.authPassword,
      captchaId: cap.id,
      captchaCode: cap.answer,
    });
    assert.equal(first.status, 200);
    const replay = await loginWith(cap.ip, {
      password: config.authPassword,
      captchaId: cap.id,
      captchaCode: cap.answer,
    });
    assert.equal(replay.status, 401);
    assert.ok(replay.body.message.includes('验证码'), `应提示验证码失效，实际：${replay.body.message}`);
  });

  await check('验证码大小写不敏感 + 错误口令 → 401', async () => {
    const cap = await getCaptcha();
    const r = await loginWith(cap.ip, {
      password: 'wrong',
      captchaId: cap.id,
      captchaCode: cap.answer.toLowerCase(),
    });
    assert.equal(r.status, 401);
    assert.ok(r.body.message.includes('口令'), `应提示口令错误，实际：${r.body.message}`);
  });

  await check('错误验证码 → 401 且同一 id 立即作废', async () => {
    const cap = await getCaptcha();
    const bad = await loginWith(cap.ip, {
      password: config.authPassword,
      captchaId: cap.id,
      captchaCode: '!!!!',
    });
    assert.equal(bad.status, 401);
    assert.ok(bad.body.message.includes('验证码不正确'), `应提示验证码不正确，实际：${bad.body.message}`);
    // 一次性：输错也作废，同一个 id 再用正确答案必须失败（防同号重试爆破）。
    const retry = await loginWith(cap.ip, {
      password: config.authPassword,
      captchaId: cap.id,
      captchaCode: cap.answer,
    });
    assert.equal(retry.status, 401);
    assert.ok(retry.body.message.includes('失效'), `应提示验证码已失效，实际：${retry.body.message}`);
  });

  await check('验证码全角输入等价（中文输入法）→ 200', async () => {
    const cap = await getCaptcha();
    // 把半角字母数字逐个映射到全角（Ａ → Ａ），模拟中文输入法下的实际输入。
    const fullWidth = cap.answer.replace(/[A-Z0-9]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 0xfee0));
    const r = await loginWith(cap.ip, {
      password: config.authPassword,
      captchaId: cap.id,
      captchaCode: fullWidth,
    });
    assert.equal(r.status, 200, `全角输入应等价通过，实际 ${r.status}：${r.body.message || ''}`);
  });

  let token = '';
  await check('POST login 正确口令 + 正确验证码 → 200 + token', async () => {
    const cap = await getCaptcha();
    const r = await loginWith(cap.ip, {
      password: config.authPassword,
      captchaId: cap.id,
      captchaCode: cap.answer,
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
    // 独立 IP：缺验证码的失败同样计数。RATE_MAX_FAILS=3 → 前两次 401，第 3 次 429；
    // 锁定期间即使口令+验证码齐全也 429。
    const ip = nextIp();
    const bad = { password: 'x' };
    const r1 = await loginWith(ip, bad);
    assert.equal(r1.status, 401);
    const r2 = await loginWith(ip, bad);
    assert.equal(r2.status, 401);
    const r3 = await loginWith(ip, bad);
    assert.equal(r3.status, 429, `第 3 次失败应触发 429，实际 ${r3.status}`);
    assert.ok(r3.body.message.includes('锁定'));
    const cap = await getCaptcha();
    const r4 = await loginWith(ip, {
      password: config.authPassword,
      captchaId: cap.id,
      captchaCode: cap.answer,
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
