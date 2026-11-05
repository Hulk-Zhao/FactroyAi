'use strict';

/**
 * mfg-aps-platform 架构设计 + 任务拆解 动态页面服务
 * Node 22+，仅使用内置模块（node:http / node:fs / node:path）+ 全局 fetch。
 * 持久化数据（任务看板）存放于 QW Pages 注入的 Supabase。
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const PORT = Number(process.env.PORT || 8081);
const HOST = process.env.HOST || '0.0.0.0';

const SUPABASE_URL = process.env.SUPABASE_URL || '';
const SUPABASE_KEY = process.env.SUPABASE_ANON_KEY || '';
const DB_READY = Boolean(SUPABASE_URL && SUPABASE_KEY);

const PUBLIC_DIR = path.join(__dirname, 'public');
const SEED_FILE = path.join(__dirname, 'data', 'tasks.seed.json');
const TABLE = 'tasks';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function sbHeaders(extra) {
  return Object.assign(
    {
      apikey: SUPABASE_KEY,
      Authorization: 'Bearer ' + SUPABASE_KEY,
      'Content-Type': 'application/json',
    },
    extra || {}
  );
}

function restUrl(query) {
  return SUPABASE_URL.replace(/\/$/, '') + '/rest/v1/' + TABLE + (query || '');
}

// ---- 内存兜底缓存：即使数据库不可用，页面仍可展示种子任务 ----
let fallbackTasks = [];
try {
  const seed = JSON.parse(fs.readFileSync(SEED_FILE, 'utf8'));
  fallbackTasks = (seed.tasks || []).map(normalizeTask);
} catch (e) {
  console.error('[seed] load failed:', e.message);
}

function normalizeTask(t) {
  return {
    task_no: t.task_no,
    title: t.title || '',
    category: t.category || 'M1',
    role_group: t.role_group || '',
    content: t.content || '',
    deps: t.deps || '',
    effort: t.effort || '',
    deliverable: t.deliverable || '',
    acceptance: t.acceptance || '',
    is_critical: Boolean(t.is_critical),
    is_parallel: t.is_parallel !== false,
    status: t.status || '待开始',
    owner: t.owner || '',
    priority: t.priority || '',
    order_index: Number(t.order_index || 0),
  };
}

async function ensureSeeded() {
  if (!DB_READY) return;
  try {
    const res = await fetch(restUrl('?select=task_no&limit=1'), { headers: sbHeaders() });
    if (!res.ok) throw new Error('select ' + res.status);
    const rows = await res.json();
    if (Array.isArray(rows) && rows.length > 0) return; // already seeded
    const body = fallbackTasks.map((t) => ({
      task_no: t.task_no,
      title: t.title,
      category: t.category,
      role_group: t.role_group,
      content: t.content,
      deps: t.deps,
      effort: t.effort,
      deliverable: t.deliverable,
      acceptance: t.acceptance,
      is_critical: t.is_critical,
      is_parallel: t.is_parallel,
      status: t.status,
      owner: t.owner,
      priority: t.priority,
      order_index: t.order_index,
    }));
    const ins = await fetch(restUrl(), {
      method: 'POST',
      headers: sbHeaders({ Prefer: 'resolution=ignore-duplicates' }),
      body: JSON.stringify(body),
    });
    console.log('[seed] insert status', ins.status);
  } catch (e) {
    console.error('[seed] ensureSeeded failed:', e.message);
  }
}

async function fetchTasks() {
  if (!DB_READY) return { source: 'seed', tasks: fallbackTasks };
  try {
    const res = await fetch(restUrl('?select=*&order=order_index.asc'), { headers: sbHeaders() });
    if (!res.ok) throw new Error('select ' + res.status);
    const rows = await res.json();
    if (!Array.isArray(rows) || rows.length === 0) return { source: 'seed', tasks: fallbackTasks };
    return { source: 'db', tasks: rows.map(normalizeTask) };
  } catch (e) {
    console.error('[api] fetchTasks failed:', e.message);
    return { source: 'seed', tasks: fallbackTasks };
  }
}

async function updateTask(taskNo, patch) {
  const allowed = {};
  if (typeof patch.status === 'string') allowed.status = patch.status;
  if (typeof patch.owner === 'string') allowed.owner = patch.owner.slice(0, 64);
  if (Object.keys(allowed).length === 0) return { ok: false, code: 400, error: 'no valid fields' };
  if (!DB_READY) {
    // 无数据库时仅在内存兜底数组中修改（不持久化）
    const t = fallbackTasks.find((x) => x.task_no === taskNo);
    if (!t) return { ok: false, code: 404, error: 'task not found' };
    Object.assign(t, allowed);
    return { ok: true, persisted: false, task: t };
  }
  try {
    const res = await fetch(restUrl('?task_no=eq.' + encodeURIComponent(taskNo)), {
      method: 'PATCH',
      headers: sbHeaders({ Prefer: 'return=representation' }),
      body: JSON.stringify(allowed),
    });
    if (!res.ok) throw new Error('patch ' + res.status);
    const rows = await res.json();
    const task = Array.isArray(rows) && rows[0] ? normalizeTask(rows[0]) : null;
    // 同步内存兜底
    const local = fallbackTasks.find((x) => x.task_no === taskNo);
    if (local) Object.assign(local, allowed);
    return { ok: true, persisted: true, task };
  } catch (e) {
    console.error('[api] updateTask failed:', e.message);
    return { ok: false, code: 502, error: e.message };
  }
}

function sendJSON(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 1e6) reject(new Error('body too large'));
    });
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function serveStatic(req, res, urlPath) {
  let rel = urlPath === '/' ? '/index.html' : urlPath;
  const filePath = path.join(PUBLIC_DIR, path.normalize(rel));
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    return res.end('Forbidden');
  }
  fs.readFile(filePath, (err, buf) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('Not Found');
    }
    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(buf);
  });
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const p = u.pathname;

  try {
    if (p === '/api/tasks' && req.method === 'GET') {
      const data = await fetchTasks();
      let meta = {};
      try {
        meta = JSON.parse(fs.readFileSync(SEED_FILE, 'utf8')).meta || {};
      } catch (e) {}
      return sendJSON(res, 200, { code: 0, message: 'success', source: data.source, dbReady: DB_READY, meta, data: data.tasks });
    }

    const m = p.match(/^\/api\/tasks\/([^/]+)$/);
    if (m && req.method === 'PATCH') {
      const patch = await readBody(req);
      const r = await updateTask(decodeURIComponent(m[1]), patch);
      if (!r.ok) return sendJSON(res, r.code || 500, { code: r.code || 500, message: r.error });
      return sendJSON(res, 200, { code: 0, message: 'success', persisted: r.persisted, data: r.task });
    }

    if (p.startsWith('/api/')) {
      return sendJSON(res, 404, { code: 404, message: 'no such endpoint' });
    }

    return serveStatic(req, res, p);
  } catch (e) {
    console.error('[server] error', e);
    return sendJSON(res, 500, { code: 500, message: e.message });
  }
});

ensureSeeded().then(() => {
  server.listen(PORT, HOST, () => {
    console.log('APS architecture page listening on http://' + HOST + ':' + PORT);
    console.log('DB_READY=' + DB_READY);
  });
});
