/**
 * server.mjs —— Hogo-QA-tool 后端入口（零 npm 依赖，Node 18+）。
 *
 * 职责：
 *   1. 托管前端构建产物（frontend/dist-server，SPA history 回退）；
 *   2. 演示登录鉴权 API：POST /api/auth/login（口令 + 按 IP 限流防刷）、
 *      GET /api/auth/check、POST /api/auth/logout；
 *   3. 样例数据 API：GET /api/samples、GET /api/samples/:file/download；
 *   4. GET /api/health 健康检查（公网探测 / 反代探活用）。
 *
 * 用法：node server.mjs   （配置见 .env.example）
 */

import { createServer } from 'node:http';
import { config } from './lib/config.mjs';
import { getSecret, handleLogin, verifyToken } from './lib/auth.mjs';
import { securityHeaders, serveStatic } from './lib/static.mjs';
import { listSamples, readSampleFile } from './lib/samples.mjs';

const JSON_HDR = { 'Content-Type': 'application/json; charset=utf-8' };

/** 客户端 IP：优先取 x-forwarded-for 首段（Nginx 反代场景），否则 socket 地址。 */
function clientIp(req) {
  const xff = req.headers['x-forwarded-for'];
  if (typeof xff === 'string' && xff.length > 0) {
    return xff.split(',')[0].trim();
  }
  return req.socket.remoteAddress || 'unknown';
}

/** 读请求体（JSON，限制 10KB 防滥用）。 */
function readJsonBody(req) {
  return new Promise((resolvePromise) => {
    let size = 0;
    const chunks = [];
    let aborted = false;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 10_240) {
        aborted = true;
        resolvePromise(null);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (aborted) return;
      try {
        resolvePromise(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        resolvePromise(null);
      }
    });
    req.on('error', () => resolvePromise(null));
  });
}

function sendJson(res, status, payload) {
  res.writeHead(status, JSON_HDR);
  res.end(JSON.stringify(payload));
}

/** Bearer token 解析。 */
function bearerToken(req) {
  const h = req.headers.authorization;
  if (typeof h === 'string' && h.startsWith('Bearer ')) {
    return h.slice(7).trim();
  }
  return null;
}

/**
 * API 路由分发。返回 true 表示已处理；false 表示非 API 请求（转静态托管）。
 */
async function handleApi(req, res, pathname) {
  const method = req.method || 'GET';

  if (pathname === '/api/health' && method === 'GET') {
    sendJson(res, 200, { ok: true, name: 'hogo-qa-backend', time: new Date().toISOString() });
    return true;
  }

  if (pathname === '/api/auth/login' && method === 'POST') {
    const body = await readJsonBody(req);
    const result = await handleLogin(clientIp(req), body);
    if (result.ok) {
      sendJson(res, 200, { ok: true, token: result.token, expiresAt: result.expiresAt });
    } else {
      if (result.retryAfterMs) {
        res.setHeader('Retry-After', Math.ceil(result.retryAfterMs / 1000));
      }
      sendJson(res, result.status, { ok: false, message: result.message });
    }
    return true;
  }

  if (pathname === '/api/auth/check' && method === 'GET') {
    const payload = verifyToken(bearerToken(req));
    if (payload) {
      sendJson(res, 200, { ok: true, expiresAt: payload.exp });
    } else {
      sendJson(res, 401, { ok: false, message: '会话无效或已过期' });
    }
    return true;
  }

  if (pathname === '/api/auth/logout' && method === 'POST') {
    // 无状态 token：登出由客户端丢弃 token 实现；此端点仅为语义完备。
    sendJson(res, 204, {});
    return true;
  }

  if (pathname === '/api/samples' && method === 'GET') {
    // 样例清单需要登录：与整站门禁口径一致。
    if (!verifyToken(bearerToken(req))) {
      sendJson(res, 401, { ok: false, message: '未登录' });
      return true;
    }
    sendJson(res, 200, { ok: true, samples: listSamples() });
    return true;
  }

  const dl = pathname.match(/^\/api\/samples\/([^/]+)\/download$/);
  if (dl && method === 'GET') {
    if (!verifyToken(bearerToken(req))) {
      sendJson(res, 401, { ok: false, message: '未登录' });
      return true;
    }
    const buf = readSampleFile(decodeURIComponent(dl[1]));
    if (!buf) {
      sendJson(res, 404, { ok: false, message: '样例文件不存在' });
      return true;
    }
    res.writeHead(200, {
      'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'Content-Disposition': `attachment; filename="${encodeURIComponent(dl[1])}"`,
    });
    res.end(buf);
    return true;
  }

  return false;
}

/** 启动 HTTP 服务（导出以便冒烟测试复用）。 */
export function createAppServer() {
  return createServer(async (req, res) => {
    securityHeaders(res);
    const pathname = (req.url || '/').split('?')[0];

    try {
      if (await handleApi(req, res, pathname)) return;

      if ((req.method === 'GET' || req.method === 'HEAD') && (await serveStatic(req, res, config.staticDir, pathname))) {
        return;
      }

      sendJson(res, 404, { ok: false, message: '未找到资源' });
    } catch (err) {
      // 路径越界等 → 400；其他未知错误 → 500（不泄露内部细节）。
      const msg = err instanceof Error && err.message === '路径越界' ? '非法路径' : '服务器内部错误';
      if (!(err instanceof Error && err.message === '路径越界')) {
        console.error('[server] 未处理异常：', err);
      }
      sendJson(res, err instanceof Error && err.message === '路径越界' ? 400 : 500, { ok: false, message: msg });
    }
  });
}

/* CLI 入口（被 import 时不执行）。 */
const isMain = process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('backend/server.mjs');
if (isMain) {
  getSecret(); // 启动即确保密钥就绪（自动生成场景提前落盘）
  const server = createAppServer();
  server.on('error', (err) => {
    if (err && err.code === 'EADDRINUSE') {
      console.error(`\n端口 ${config.port} 已被占用。请换端口（PORT=8899 node server.mjs）或释放占用。\n`);
    } else {
      console.error('\n服务器启动失败：', err);
    }
    process.exit(1);
  });
  server.listen(config.port, config.host, () => {
    console.log('');
    console.log('  Hogo-QA-tool 后端已启动');
    console.log(`  http://localhost:${config.port}/`);
    console.log(`  静态目录：${config.staticDir}`);
    console.log(`  登录口令：${'*'.repeat(config.authPassword.length)}（AUTH_PASSWORD 配置）`);
    console.log('');
    console.log('  按 Ctrl+C 停止服务');
    console.log('');
  });
}
