/**
 * auth.mjs —— 演示登录鉴权 + 防刷限流（零依赖）。
 *
 * 设计口径（对应"部署在云端做作品展示"的场景）：
 * - 无用户体系：单口令（默认 123）放行，目的是挡住爬虫与恶意刷量，不是保密。
 * - 无状态会话：HMAC-SHA256 签名的 token（payload 只有签发/过期时间），
 *   服务端不存 session 表；密钥可由环境变量注入，缺省自动生成并持久化，
 *   重启不掉线、多 worker 可共用（读同一文件）。
 * - 防刷：登录前必须通过一次性图形验证码（见 captcha.mjs），再按 IP 记连续
 *   失败次数，超限锁定；失败响应固定延迟拖慢爆破。
 *   纯内存实现（重启清零），演示场景足够且不引入存储依赖。
 */

import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { verifyCaptcha } from './captcha.mjs';
import { config } from './config.mjs';

/* ---------------- HMAC 会话 token ---------------- */

function b64url(buf) {
  return Buffer.from(buf).toString('base64url');
}

/** 取签名密钥：环境变量优先，否则生成并持久化（幂等）。 */
export function getSecret() {
  if (config.authSecret) return config.authSecret;
  const file = join(config.runtimeDir, 'auth-secret.key');
  try {
    const existing = readFileSync(file, 'utf8').trim();
    if (existing.length >= 32) return existing;
  } catch {
    /* 文件不存在 → 生成 */
  }
  mkdirSync(config.runtimeDir, { recursive: true });
  const secret = randomBytes(32).toString('base64url');
  writeFileSync(file, secret, { mode: 0o600 });
  return secret;
}

/** 签发 token：`b64url(payload).b64url(hmac)`，payload 仅含 iat/exp。 */
export function issueToken(now = Date.now()) {
  const payload = { v: 1, iat: now, exp: now + config.tokenTtlMs };
  const body = b64url(JSON.stringify(payload));
  const sig = b64url(createHmac('sha256', getSecret()).update(body).digest());
  return { token: `${body}.${sig}`, expiresAt: payload.exp };
}

/** 校验 token：签名（恒定时间比较）+ 未过期。合法返回 payload，否则 null。 */
export function verifyToken(token) {
  if (typeof token !== 'string') return null;
  const dot = token.indexOf('.');
  if (dot <= 0) return null;
  const body = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  const expected = createHmac('sha256', getSecret()).update(body).digest();
  let given;
  try {
    given = Buffer.from(sig, 'base64url');
  } catch {
    return null;
  }
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) {
    return null;
  }
  try {
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    if (payload?.v !== 1 || typeof payload.exp !== 'number') return null;
    if (Date.now() >= payload.exp) return null;
    return payload;
  } catch {
    return null;
  }
}

/* ---------------- 按 IP 防刷限流（内存态） ---------------- */

/** ip → { fails, lockedUntil }。重启清零，演示场景可接受。 */
const rateState = new Map();

/** 定期清理过期条目，防 Map 无限增长。 */
function sweep(now) {
  if (rateState.size < 10_000) return;
  for (const [ip, s] of rateState) {
    if (s.lockedUntil <= now && now - (s.lastFail || 0) > config.rateLockMs * 2) {
      rateState.delete(ip);
    }
  }
}

/**
 * 纯函数：判定当前请求是否被锁定，并返回锁定剩余毫秒。
 * 独立导出便于单测（不依赖 Map 状态）。
 */
export function lockRemainingMs(state, now) {
  if (!state || state.lockedUntil <= now) return 0;
  return state.lockedUntil - now;
}

function stateOf(ip, now) {
  sweep(now);
  let s = rateState.get(ip);
  if (!s) {
    s = { fails: 0, lockedUntil: 0, lastFail: 0 };
    rateState.set(ip, s);
  }
  return s;
}

/** 登录成功后清零失败计数。 */
function resetFails(ip) {
  rateState.delete(ip);
}

/**
 * 处理登录：成功返回 { ok, token, expiresAt }；失败返回 { ok:false, status, message, retryAfterMs? }。
 *
 * 校验顺序：IP 锁定 → 一次性图形验证码 → 口令。验证码**无论对错都作废**，
 * 且失败同样计入连续失败次数（否则可绕开限流爆破验证码）。
 *
 * @param {string} ip 客户端 IP（取最外层，反代场景由 server.mjs 解析 x-forwarded-for）
 * @param {unknown} body 请求体（期望 { password, captchaId, captchaCode }）
 */
export async function handleLogin(ip, body) {
  const now = Date.now();
  const state = stateOf(ip, now);
  const locked = lockRemainingMs(state, now);
  if (locked > 0) {
    return {
      ok: false,
      status: 429,
      message: `尝试次数过多，已临时锁定，请 ${Math.ceil(locked / 60000)} 分钟后再试`,
      retryAfterMs: locked,
    };
  }

  const { captchaId, captchaCode, password } =
    body && typeof body === 'object' ? body : {};
  const captcha = verifyCaptcha(captchaId, captchaCode);
  if (!captcha.ok) {
    return recordFailure(state, now, captcha.message);
  }

  if (typeof password === 'string' && password === config.authPassword) {
    resetFails(ip);
    const { token, expiresAt } = issueToken(now);
    return { ok: true, token, expiresAt };
  }

  return recordFailure(state, now, '口令不正确');
}

/** 记一次失败：计数 + 锁定判定 + 固定延迟（统一 401；锁定中才是 429）。 */
async function recordFailure(state, now, reason) {
  state.fails += 1;
  state.lastFail = now;
  if (state.fails >= config.rateMaxFails) {
    state.lockedUntil = now + config.rateLockMs;
    return {
      ok: false,
      status: 429,
      message: `连续失败 ${state.fails} 次，已锁定 ${Math.round(config.rateLockMs / 60000)} 分钟`,
      retryAfterMs: state.lockedUntil - now,
    };
  }
  if (config.loginDelayMs > 0) {
    await new Promise((r) => setTimeout(r, config.loginDelayMs));
  }
  return {
    ok: false,
    status: 401,
    message: `${reason}（剩余尝试次数 ${config.rateMaxFails - state.fails}）`,
  };
}

/** 测试辅助：清空限流状态。 */
export function resetRateState() {
  rateState.clear();
}
