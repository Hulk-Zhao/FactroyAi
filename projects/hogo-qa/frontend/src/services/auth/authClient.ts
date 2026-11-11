/**
 * authClient —— 演示登录门禁的客户端（与 backend/lib/auth.mjs 对应）。
 *
 * 语义口径：
 * - 门禁的目的是"部署在云端做作品展示时防恶意刷访问"，不是用户体系：
 *   单口令（默认 123）+ 一次性图形验证码 + 无状态 HMAC token。
 * - token 存 localStorage（含过期时间），本模块先做本地过期预检，再由
 *   /api/auth/check 做服务端确认（签名 / 提前吊销场景）。
 * - `file://` 离线双击包没有后端，门禁自动关闭——离线核心功能不受影响。
 *   部署形态可用 `VITE_AUTH_DISABLED=1` 显式关闭门禁（纯静态托管时）。
 */

/** localStorage 会话键。 */
export const AUTH_SESSION_KEY = 'hogo-auth-session';

/**
 * 认证接口前缀。
 *
 * - 默认跟随构建基路径：独立部署为 `/api/`；挂在子路径（`/demo/hogo-qa/`）时为
 *   `/demo/hogo-qa/api/`——不能写死 `/api/`，否则会命中同域下的其它后端。
 * - 静态站托管在 COS/CDN、后端在 api 子域时，用 `VITE_API_BASE` 指向
 *   `https://api.hulkai.cn/hogo/api/`（构建时注入，未配置则保持原行为）。
 */
const CONFIGURED_API_BASE = (import.meta.env.VITE_API_BASE || '').trim();
const API_BASE = CONFIGURED_API_BASE
  ? CONFIGURED_API_BASE.replace(/\/?$/, '/')
  : `${import.meta.env.BASE_URL}api/`;

/** 已保存会话（与后端 login 响应对齐）。 */
export interface AuthSession {
  token: string;
  /** epoch 毫秒 */
  expiresAt: number;
}

/** 当前环境是否需要门禁。 */
export function isAuthRequired(): boolean {
  if (typeof window === 'undefined') return false;
  // 离线双击包（file://）没有后端，门禁关闭，保留离线核心功能。
  if (window.location.protocol === 'file:') return false;
  // 部署开关：纯静态托管（无后端）时可显式关闭门禁。
  if (import.meta.env.VITE_AUTH_DISABLED === '1') return false;
  // 单测直接渲染 App，不经过真实后端。
  if (import.meta.env.MODE === 'test') return false;
  return true;
}

/** 读取本地会话；缺失 / 结构非法 / 已过期 → null（并清理）。 */
export function getValidSession(): AuthSession | null {
  try {
    const raw = window.localStorage.getItem(AUTH_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<AuthSession>;
    if (typeof parsed?.token !== 'string' || typeof parsed?.expiresAt !== 'number') {
      window.localStorage.removeItem(AUTH_SESSION_KEY);
      return null;
    }
    if (Date.now() >= parsed.expiresAt) {
      window.localStorage.removeItem(AUTH_SESSION_KEY);
      return null;
    }
    return { token: parsed.token, expiresAt: parsed.expiresAt };
  } catch {
    return null;
  }
}

function saveSession(session: AuthSession): void {
  window.localStorage.setItem(AUTH_SESSION_KEY, JSON.stringify(session));
}

/** 丢弃本地会话（登出 = 客户端丢 token；后端无状态，无需服务端调用）。 */
export function logout(): void {
  try {
    window.localStorage.removeItem(AUTH_SESSION_KEY);
  } catch {
    /* 隐私模式等场景忽略 */
  }
}

/** 登录结果。 */
export type LoginOutcome =
  | { ok: true; session: AuthSession }
  | { ok: false; message: string; retryAfterMs?: number };

/** 一次图形验证码（SVG 由后端签发，一次性、约 10 分钟有效）。 */
export interface CaptchaChallenge {
  captchaId: string;
  /** 内联 SVG 字符串（已服务端生成，可直接注入 DOM / data URI）。 */
  svg: string;
  /** 有效期（毫秒）。 */
  ttlMs: number;
}

/** 获取验证码结果。 */
export type CaptchaOutcome =
  | { ok: true; challenge: CaptchaChallenge }
  | { ok: false; message: string };

/**
 * 拉取一张新验证码（点击图片刷新时复用同一函数）。
 *
 * 失败（后端不可达／被限速）返回可读原因——登录页据此展示"点击重试"，
 * 不静默退回"无验证码登录"（门禁是 fail-closed 的）。
 */
export async function fetchCaptcha(): Promise<CaptchaOutcome> {
  try {
    const res = await fetch(`${API_BASE}auth/captcha`, { cache: 'no-store' });
    const body = (await res.json().catch(() => null)) as
      | { ok?: boolean; captchaId?: string; svg?: string; ttlS?: number; message?: string }
      | null;
    if (res.ok && body?.ok && typeof body.captchaId === 'string' && typeof body.svg === 'string') {
      return {
        ok: true,
        challenge: {
          captchaId: body.captchaId,
          svg: body.svg,
          ttlMs: typeof body.ttlS === 'number' ? body.ttlS * 1000 : 600_000,
        },
      };
    }
    return {
      ok: false,
      message: body?.message || `验证码获取失败（HTTP ${res.status}）`,
    };
  } catch {
    return {
      ok: false,
      message: '无法连接认证服务：请确认后端已启动',
    };
  }
}

/**
 * 用「口令 + 验证码」登录。
 *
 * 验证码是一次性的：登录失败后前端必须重新拉取（服务端已作废该 id）。
 * 网络失败（后端不可达）归一化为 ok:false —— 门禁是 fail-closed 的：
 * 后端不可用时宁可显示"服务不可用"，也不放行。
 */
export async function login(
  password: string,
  captcha: { id: string; code: string },
): Promise<LoginOutcome> {
  try {
    const res = await fetch(`${API_BASE}auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        password,
        captchaId: captcha.id,
        captchaCode: captcha.code,
      }),
    });
    const body = (await res.json().catch(() => null)) as
      | { ok?: boolean; token?: string; expiresAt?: number; message?: string }
      | null;
    if (res.ok && body?.ok && typeof body.token === 'string' && typeof body.expiresAt === 'number') {
      const session: AuthSession = { token: body.token, expiresAt: body.expiresAt };
      saveSession(session);
      return { ok: true, session };
    }
    return {
      ok: false,
      message: body?.message || `登录失败（HTTP ${res.status}）`,
      retryAfterMs: typeof res.headers.get('retry-after') === 'string'
        ? Number(res.headers.get('retry-after')) * 1000
        : undefined,
    };
  } catch {
    // 最常见根因：只托管了静态产物、没有启动后端（/api 无人接）。
    return {
      ok: false,
      message: '无法连接认证服务：请确认后端已启动（node backend/server.mjs 或 docker compose up）',
    };
  }
}

/** 服务端会话确认：token 签名 / 有效期 / 未被吊销。任何失败一律 false。 */
export async function checkSession(): Promise<boolean> {
  const session = getValidSession();
  if (!session) return false;
  try {
    const res = await fetch(`${API_BASE}auth/check`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    if (res.ok) return true;
    if (res.status === 401) logout();
    return false;
  } catch {
    // 网络抖动时保守放行本地未过期会话，避免后端瞬断把用户踢回登录页；
    // token 本身有过期时间兜底。
    return true;
  }
}
