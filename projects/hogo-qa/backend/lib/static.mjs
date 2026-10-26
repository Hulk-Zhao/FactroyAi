/**
 * static.mjs —— 前端产物静态托管 + SPA 回退（零依赖）。
 *
 * 安全要点：
 * - 路径规范化 + 越界检查（防目录穿越）；
 * - index.html no-cache（发布新版立即生效），/assets/ 带内容哈希可长缓存；
 * - 基础安全响应头。
 */

import { createReadStream, stat } from 'node:fs';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { promisify } from 'node:util';

const statAsync = promisify(stat);

/** 扩展名 → Content-Type。 */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
};

/** 解析 URL 路径为静态目录内的安全绝对路径；越界抛错。 */
export function safeResolve(rootDir, urlPath) {
  const decoded = decodeURIComponent(urlPath.split('?')[0]);
  const rel = normalize(decoded).replace(/^([/\\])+/, '');
  const abs = resolve(join(rootDir, rel));
  if (abs !== resolve(rootDir) && !abs.startsWith(resolve(rootDir) + sep)) {
    throw new Error('路径越界');
  }
  return abs;
}

/** 基础安全响应头。 */
export function securityHeaders(res) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
}

/**
 * 托管静态文件；未命中（或目录）时回退 index.html（SPA history 路由）。
 * @returns {Promise<boolean>} 是否已写入响应
 */
export async function serveStatic(req, res, rootDir, urlPath) {
  let target = safeResolve(rootDir, urlPath);
  let info = null;
  try {
    info = await statAsync(target);
  } catch {
    info = null;
  }
  if (!info || info.isDirectory()) {
    target = join(resolve(rootDir), 'index.html');
    try {
      info = await statAsync(target);
    } catch {
      return false; // 产物不存在（尚未构建）→ 交由 server 返回 503 提示
    }
  }

  const ext = extname(target).toLowerCase();
  const isHashedAsset = urlPath.startsWith('/assets/');
  const headers = {
    'Content-Type': MIME[ext] || 'application/octet-stream',
    'Cache-Control': isHashedAsset ? 'public, max-age=31536000, immutable' : 'no-cache',
  };
  if (req.method === 'HEAD') {
    res.writeHead(200, { ...headers, 'Content-Length': info.size });
    res.end();
    return true;
  }
  res.writeHead(200, headers);
  createReadStream(target).pipe(res);
  return true;
}
