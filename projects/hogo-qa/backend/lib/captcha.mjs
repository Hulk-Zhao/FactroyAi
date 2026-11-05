/**
 * captcha.mjs —— 登录图形验证码（零依赖，Node 18+）。
 *
 * 与 mfg-agent 的登录守卫同口径：
 * - `GET /api/auth/captcha` 签发一张 SVG 验证码（内联文本，前端 data URI 渲染）；
 * - 登录必须携带正确的 `captchaId + captchaCode`，校验后立即作废（一次性），
 *   默认 10 分钟过期（`CAPTCHA_TTL_SECONDS` 可调）；
 * - 签发端点按 IP 滑动窗口限速（默认每分钟 30 张），防被刷爆内存/带宽；
 * - 比对大小写不敏感（人工输入友好；自动化防刷语义不受影响）。
 *
 * 诚实边界：验证码挡的是「自动化批量提交」，不防定点人工攻击；演示门禁
 * 本来就是单口令 + 限流，不是用户体系。
 */

import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { config } from './config.mjs';

/** 字符集：去掉 0/O、1/I 等易混淆字符，降低人工输入挫败感。 */
const CHARS = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
/** 验证码长度。 */
const CAPTCHA_LEN = 4;
/** 验证码 / 限速表的容量上限（内存防线；正常流量远达不到）。 */
const MAX_ITEMS = 20_000;
const MAX_IPS = 10_000;
/** 签发限速窗口（毫秒）。 */
const RATE_WINDOW_MS = 60_000;
/** 验证码配色（与 mfg-agent 一致）。 */
const COLORS = ['#1B66CC', '#2E7D32', '#B26A00', '#7B3FA0', '#B3413C', '#0E7490'];

/** captchaId → { answer, expiresAt }。 */
const store = new Map();
/** ip → 窗口内签发时间戳列表。 */
const rate = new Map();

/** 清掉过期验证码，防 Map 无限增长。 */
function sweep(now) {
  for (const [id, rec] of store) {
    if (rec.expiresAt <= now) {
      store.delete(id);
    }
  }
}

/** 从数组里随机取一个元素。 */
function pick(list) {
  return list[randomInt(list.length)];
}

/** 渲染验证码 SVG（120x44，浅色底保证深浅主题下都可读）。 */
function renderSvg(text) {
  const width = 120;
  const height = 44;
  const parts = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">`,
    `<rect width="${width}" height="${height}" fill="#F4F7FC"/>`,
  ];

  // 干扰曲线 ×3：横穿的贝塞尔曲线，颜色/起伏随机
  for (let i = 0; i < 3; i += 1) {
    const y1 = randomInt(height);
    const y2 = randomInt(height);
    const y3 = randomInt(height);
    parts.push(
      `<path d="M0,${y1} C ${Math.floor(width / 3)},${y2} ${Math.floor((width * 2) / 3)},${y3} ${width},${y1}" ` +
        `stroke="${pick(COLORS)}" stroke-width="1.2" fill="none" opacity="0.45"/>`,
    );
  }

  // 字符：随机字号 / 基线偏移 / 旋转 / 颜色，等距铺开
  const slot = Math.floor(width / (CAPTCHA_LEN + 1));
  [...text].forEach((ch, i) => {
    const size = 26 + randomInt(7);
    const x = slot * (i + 1) + randomInt(5) - 2;
    const y = Math.floor(height / 2) + 9 + randomInt(7) - 3;
    const angle = randomInt(51) - 25;
    parts.push(
      `<text x="${x}" y="${y}" font-size="${size}" fill="${pick(COLORS)}" ` +
        `font-family="Georgia, 'Times New Roman', serif" font-weight="700" ` +
        `transform="rotate(${angle} ${x} ${y})" text-anchor="middle">${ch}</text>`,
    );
  });

  // 噪点 ×10
  for (let i = 0; i < 10; i += 1) {
    parts.push(
      `<circle cx="${randomInt(width)}" cy="${randomInt(height)}" r="${1 + randomInt(2)}" ` +
        `fill="${pick(COLORS)}" opacity="0.35"/>`,
    );
  }

  parts.push('</svg>');
  return parts.join('');
}

/** 定长时间字符串比较（长度不同直接判否）。 */
function sameCode(a, b) {
  const left = Buffer.from(String(a), 'utf8');
  const right = Buffer.from(String(b), 'utf8');
  return left.length === right.length && timingSafeEqual(left, right);
}

/**
 * 输入归一化：全角转半角（NFKC）、去掉所有空白（含全角空格）、转大写。
 *
 * 中文输入法在中英文混排时常把 `ＡＢＣ１２３` 打成全角，肉眼与半角几乎无差别，
 * 只做 `trim().toUpperCase()` 会让这类输入「看起来完全正确却永远报错」。
 * 归一化后与字符集口径一致，不影响防刷语义。
 */
function normalizeCode(code) {
  return String(code)
    .normalize('NFKC')
    .replace(/\s+/gu, '')
    .toUpperCase();
}

/**
 * 签发一张验证码。返回 `{ id, svg }`——答案只留在服务端，HTTP 应答不回明文。
 */
export function issueCaptcha() {
  const now = Date.now();
  sweep(now);

  let answer = '';
  for (let i = 0; i < CAPTCHA_LEN; i += 1) {
    answer += CHARS[randomInt(CHARS.length)];
  }

  let id = '';
  do {
    id = randomBytes(12).toString('base64url');
  } while (store.has(id));

  while (store.size >= MAX_ITEMS) {
    store.delete(store.keys().next().value);
  }
  store.set(id, { answer, expiresAt: now + config.captchaTtlMs });

  return { id, svg: renderSvg(answer) };
}

/**
 * 校验验证码（**一次性**：无论对错，该 id 立即作废——防同号重试爆破）。
 *
 * @param {unknown} captchaId 签发时返回的 id
 * @param {unknown} code 用户输入
 * @returns {{ ok: boolean, message: string }} `message` 可直接展示给用户
 */
export function verifyCaptcha(captchaId, code) {
  const id = typeof captchaId === 'string' ? captchaId.trim() : '';
  const input = typeof code === 'string' ? normalizeCode(code) : '';
  if (!id || !input) {
    return { ok: false, message: '请输入验证码' };
  }

  const record = store.get(id);
  store.delete(id);
  if (!record || record.expiresAt <= Date.now()) {
    return { ok: false, message: '验证码已失效，请点击图片刷新后重试' };
  }
  if (!sameCode(record.answer, input)) {
    return { ok: false, message: '验证码不正确，请重新输入' };
  }
  return { ok: true, message: 'ok' };
}

/**
 * 签发限速：同一 IP 每分钟最多 `CAPTCHA_RATE_LIMIT` 张。
 *
 * @param {unknown} ip 客户端 IP
 * @returns {boolean} false = 超限（调用方应返回 429）
 */
export function checkCaptchaRate(ip) {
  const key = typeof ip === 'string' && ip.length > 0 ? ip : 'unknown';
  const now = Date.now();
  const hits = (rate.get(key) || []).filter((t) => now - t < RATE_WINDOW_MS);
  if (hits.length >= config.captchaRateLimit) {
    rate.set(key, hits); // 超限也回写：继续累计，不重置窗口
    return false;
  }
  hits.push(now);
  rate.set(key, hits);
  if (rate.size > MAX_IPS) {
    rate.delete(rate.keys().next().value);
  }
  return true;
}

/** 测试辅助：清空验证码与限速状态。 */
export function resetCaptcha() {
  store.clear();
  rate.clear();
}

/** 测试辅助：读取某张验证码的答案（HTTP 应答永不回明文）。 */
export function peekCaptchaAnswer(captchaId) {
  const record = store.get(typeof captchaId === 'string' ? captchaId : '');
  return record ? record.answer : null;
}
