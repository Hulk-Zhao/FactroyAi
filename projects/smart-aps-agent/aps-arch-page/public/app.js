'use strict';

/* 视图切换 */
function switchView(v) {
  document.getElementById('view-arch').classList.toggle('hidden', v !== 'arch');
  document.getElementById('view-task').classList.toggle('hidden', v !== 'task');
  document.getElementById('nav-arch').classList.toggle('active', v === 'arch');
  document.getElementById('nav-task').classList.toggle('active', v === 'task');
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (v === 'task' && !window.__tasksLoaded) loadTasks();
}

const CATEGORY_ORDER = ['phase0', 'M1', 'M2', 'M3', 'integration', 'v05'];
let ALL_TASKS = [];
let META = {};

const STATUS_OPTIONS = ['待开始', '进行中', '已完成', '阻塞'];

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function loadTasks() {
  const board = document.getElementById('board');
  board.innerHTML = '<p class="lead">任务看板加载中…</p>';
  try {
    const res = await fetch('/api/tasks');
    const json = await res.json();
    ALL_TASKS = (json.data || []).slice();
    META = json.meta || {};
    const note = document.getElementById('dbnote');
    if (json.source === 'db') {
      note.classList.add('hidden');
    } else {
      note.classList.remove('hidden');
      note.textContent = '提示：当前数据库未就绪，看板展示为内置种子数据，状态修改不会持久化。';
    }
    buildFilters();
    render();
    window.__tasksLoaded = true;
  } catch (e) {
    board.innerHTML = '<p class="lead">加载失败：' + esc(e.message) + '</p>';
  }
}

function buildFilters() {
  const cats = (META.categories && META.categories.length)
    ? META.categories
    : CATEGORY_ORDER.map((k) => ({ key: k, label: k }));
  const fc = document.getElementById('f-category');
  fc.innerHTML = '<option value="">全部里程碑/阶段</option>' +
    cats.map((c) => `<option value="${esc(c.key)}">${esc(c.label)}</option>`).join('');

  const roles = (META.roleGroups && META.roleGroups.length)
    ? META.roleGroups
    : Array.from(new Set(ALL_TASKS.map((t) => t.role_group)));
  const fr = document.getElementById('f-role');
  fr.innerHTML = '<option value="">全部角色组</option>' +
    roles.map((r) => `<option value="${esc(r)}">${esc(r)}</option>`).join('');

  ['f-category', 'f-role', 'f-status'].forEach((id) =>
    document.getElementById(id).addEventListener('change', render));
  document.getElementById('f-search').addEventListener('input', render);
  document.getElementById('f-critical').addEventListener('change', render);
}

function currentFilters() {
  return {
    category: document.getElementById('f-category').value,
    role: document.getElementById('f-role').value,
    status: document.getElementById('f-status').value,
    search: document.getElementById('f-search').value.trim().toLowerCase(),
    criticalOnly: document.getElementById('f-critical').checked,
  };
}

function match(t, f) {
  if (f.category && t.category !== f.category) return false;
  if (f.role && t.role_group !== f.role) return false;
  if (f.status && t.status !== f.status) return false;
  if (f.criticalOnly && !t.is_critical) return false;
  if (f.search) {
    const hay = (t.task_no + ' ' + t.title + ' ' + t.content + ' ' + t.deliverable + ' ' + t.acceptance).toLowerCase();
    if (!hay.includes(f.search)) return false;
  }
  return true;
}

function catLabel(key) {
  const c = (META.categories || []).find((x) => x.key === key);
  return c ? c.label : key;
}

function render() {
  const f = currentFilters();
  const list = ALL_TASKS.filter((t) => match(t, f));
  renderStats(list);

  const board = document.getElementById('board');
  if (!list.length) { board.innerHTML = '<p class="lead">无匹配任务。</p>'; return; }

  const groups = {};
  list.forEach((t) => { (groups[t.category] = groups[t.category] || []).push(t); });
  const orderedKeys = CATEGORY_ORDER.filter((k) => groups[k]);
  Object.keys(groups).forEach((k) => { if (!orderedKeys.includes(k)) orderedKeys.push(k); });

  board.innerHTML = orderedKeys.map((k) => {
    const items = groups[k].sort((a, b) => (a.order_index - b.order_index) || a.task_no.localeCompare(b.task_no));
    return `<div class="milestone-block">
      <div class="milestone-head">
        <h3>${esc(catLabel(k))}</h3>
        <span class="cnt">${items.length} 个任务 · 关键路径 ${items.filter((x) => x.is_critical).length} 个</span>
      </div>
      <div class="task-grid">${items.map(cardHtml).join('')}</div>
    </div>`;
  }).join('');

  board.querySelectorAll('select[data-role="status"]').forEach((el) =>
    el.addEventListener('change', (e) => onPatch(e.target.dataset.no, { status: e.target.value }, e.target)));
  board.querySelectorAll('input[data-role="owner"]').forEach((el) =>
    el.addEventListener('change', (e) => onPatch(e.target.dataset.no, { owner: e.target.value }, e.target)));
}

function renderStats(list) {
  const done = list.filter((t) => t.status === '已完成').length;
  const doing = list.filter((t) => t.status === '进行中').length;
  const crit = list.filter((t) => t.is_critical).length;
  const effort = list.reduce((s, t) => s + (parseFloat(t.effort) || 0), 0);
  const stats = [
    { n: list.length, l: '任务总数' },
    { n: crit, l: '关键路径' },
    { n: doing, l: '进行中' },
    { n: done, l: '已完成' },
    { n: effort.toFixed(0) + ' 人天', l: '预估工作量' },
  ];
  document.getElementById('stats').innerHTML =
    stats.map((s) => `<div class="stat"><div class="n">${esc(s.n)}</div><div class="l">${esc(s.l)}</div></div>`).join('');
}

function cardHtml(t) {
  const crit = t.is_critical;
  return `<div class="task-card ${crit ? 'critical' : 'normal'}">
    <div class="tc-top">
      <span class="tc-no">${esc(t.task_no)}</span>
      ${crit ? '<span class="pill crit">关键路径</span>' : ''}
      ${t.is_parallel ? '<span class="pill par">可并行</span>' : '<span class="pill ser">串行</span>'}
    </div>
    <div class="tc-title">${esc(t.title)}</div>
    <div class="tc-meta">
      <span class="tag role">${esc(t.role_group)}</span>
      <span class="tag">工时 ${esc(t.effort || '-')}</span>
      ${t.priority ? `<span class="tag">${esc(t.priority)}</span>` : ''}
      <span class="tag">依赖 ${esc(t.deps || '无')}</span>
    </div>
    <div class="tc-body">
      <div class="row"><span class="k">核心内容：</span>${esc(t.content)}</div>
      <div class="row"><span class="k">交付物：</span>${esc(t.deliverable)}</div>
      <div class="row"><span class="k">验收标准：</span>${esc(t.acceptance)}</div>
    </div>
    <div class="tc-foot">
      <select data-role="status" data-no="${esc(t.task_no)}">
        ${STATUS_OPTIONS.map((s) => `<option ${s === t.status ? 'selected' : ''}>${s}</option>`).join('')}
      </select>
      <input data-role="owner" data-no="${esc(t.task_no)}" placeholder="负责人" value="${esc(t.owner || '')}" />
      <span class="saved-flash" id="flash-${esc(t.task_no)}">已保存</span>
    </div>
  </div>`;
}

async function onPatch(taskNo, patch, el) {
  const flash = document.getElementById('flash-' + taskNo);
  el.disabled = true;
  try {
    const res = await fetch('/api/tasks/' + encodeURIComponent(taskNo), {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    });
    const json = await res.json();
    if (json.code !== 0) throw new Error(json.message || 'save failed');
    const t = ALL_TASKS.find((x) => x.task_no === taskNo);
    if (t) Object.assign(t, patch);
    if (flash) { flash.textContent = json.persisted ? '已保存' : '已保存(未持久化)'; flash.classList.add('show'); setTimeout(() => flash.classList.remove('show'), 1500); }
    renderStats(ALL_TASKS.filter((x) => match(x, currentFilters())));
  } catch (e) {
    if (flash) { flash.textContent = '保存失败'; flash.style.color = '#d9480f'; flash.classList.add('show'); setTimeout(() => { flash.classList.remove('show'); flash.style.color = ''; }, 1800); }
  } finally {
    el.disabled = false;
  }
}

/* 首屏默认展示架构视图 */
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.toc a').forEach((a) => {
    a.addEventListener('click', (e) => {
      const id = a.getAttribute('href').slice(1);
      const el = document.getElementById(id);
      if (el) { e.preventDefault(); el.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
    });
  });
});
