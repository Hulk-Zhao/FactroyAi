/**
 * config.mjs —— 环境配置解析（零依赖）。
 *
 * 读取顺序：真实环境变量 > backend/.env（简易 KEY=VALUE 解析）> 内置默认值。
 * .env 只用于"服务器上不想配 systemd/compose 环境变量"的场景；两处都写了时
 * 真实环境变量优先（12-factor 惯例）。
 */

import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** backend/ 目录绝对路径。 */
export const BACKEND_ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));

/** 仓库根目录（backend 的上一级）。 */
export const REPO_ROOT = resolve(BACKEND_ROOT, '..');

/** 简易 .env 加载：不覆盖已存在的真实环境变量。 */
function loadDotEnv() {
  const envFile = join(BACKEND_ROOT, '.env');
  if (!existsSync(envFile)) return;
  for (const line of readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed.length === 0 || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const val = trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
    if (!(key in process.env)) process.env[key] = val;
  }
}
loadDotEnv();

function num(name, fallback) {
  const v = Number(process.env[name]);
  return Number.isFinite(v) && v > 0 ? v : fallback;
}

export const config = {
  port: num('PORT', 8788),
  host: process.env.HOST || '0.0.0.0',
  /** 演示口令（防刷）；上线前务必修改。 */
  authPassword: process.env.AUTH_PASSWORD || '123',
  /** HMAC 密钥；空串表示"自动生成并持久化"。 */
  authSecret: process.env.AUTH_SECRET || '',
  /** 会话有效期（毫秒）。 */
  tokenTtlMs: num('TOKEN_TTL_HOURS', 12) * 3600_000,
  /** 同一 IP 最大连续失败次数。 */
  rateMaxFails: num('RATE_MAX_FAILS', 5),
  /** 锁定时长（毫秒）。 */
  rateLockMs: num('RATE_LOCK_MINUTES', 10) * 60_000,
  /** 登录失败固定延迟（毫秒）。 */
  loginDelayMs: num('LOGIN_DELAY_MS', 600),
  /** 图形验证码有效期（毫秒）。 */
  captchaTtlMs: num('CAPTCHA_TTL_SECONDS', 600) * 1000,
  /** 同一 IP 每分钟验证码签发上限。 */
  captchaRateLimit: num('CAPTCHA_RATE_LIMIT', 30),
  /** 前端构建产物目录（相对 backend/ 或绝对路径）。 */
  get staticDir() {
    const raw = process.env.STATIC_DIR || '../frontend/dist-server';
    return resolve(BACKEND_ROOT, raw);
  },
  /** 运行时数据目录（自动生成的密钥等），相对仓库根固定。 */
  runtimeDir: join(BACKEND_ROOT, 'data', 'runtime'),
  /** 样例数据目录。 */
  samplesDir: join(BACKEND_ROOT, 'data', 'samples'),
};
