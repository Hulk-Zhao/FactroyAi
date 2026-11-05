# M3 验收前整改批次（前端性能）

**4 项 / 完成 / 0 受阻**

> 范围：仅 `aps-frontend`。未执行任何 `mvn`，未改后端接口契约、未改 `src/mocks/handlers.ts` 业务语义、未 commit / push。
> 测试日期：2026-11-05｜机器：AMD Ryzen 7 9800X3D（8C）/ 64GB｜Chrome（Playwright 驱动，headless）｜生产构建 + 本地静态服务（非 dev server）。

---

## 1. 结论速览

| # | 项 | 结果 | 关键数字 |
|---|---|---|---|
| 1 | 压缩与拆包 | 完成 | 入口 `index.js` **1,101.9KB → 95.0KB（-91.4%）**；首屏传输 **1,729.6KB → 301.9KB（br，-82.5%）** |
| 2 | 骨架屏 | 完成 | 首屏 Dashboard、所有 `DataTable` 列表页、资源/订单甘特舞台 |
| 3 | 甘特虚拟滚动 + 帧采样 | 完成（复核 + 指标增强） | 5000 任务 / 1667 行中**仅渲染 20 行**；滚动 **平均帧间隔 21.12ms / 47.3 FPS / 掉帧率 25.0%** |
| 4 | 真实浏览器重测 FCP | 完成 | 10Mbps/40ms 模拟网络：**1,704ms → 684ms（-59.9%）**；LCP 1,704ms → 772ms |

> 说明：`GanttTimeline` 的行/条窗口化在 HEAD 中已存在（M3-FE-04 产物），本批没有重复造轮子；本批做的是**复核虚拟化确实生效**、**补平均帧间隔/掉帧率输出**，并修复“5000 任务档实际只剩 1,165 条”的窗口过滤问题。

---

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `vite.config.ts` | 新增 `rolldownOptions.output.codeSplitting.groups` 按大依赖拆包；新增构建后 `node:zlib` 预压缩插件（`.gz`/`.br`） |
| `src/main.ts` | 全量 `ElementPlus` 改为按实际使用的 49 个组件显式注册（`ElLoading` 插件单独注册，保留 `v-loading`） |
| `src/router/modules/{gantt,gantt-order,master-data}.ts` | 视图由静态 `import` 改为 `() => import()`，消除首屏路由代码 |
| `src/components/common/PageSkeleton.vue`（新增） | 通用骨架屏 |
| `src/components/common/DataTable.vue` | 首次加载且无数据时显示骨架屏 |
| `src/views/DashboardView.vue` | 首屏整体骨架屏 |
| `src/views/gantt/{Order,Resource}GanttView.vue` | 甘特舞台首载骨架屏；订单甘特帧采样补 `avgFrameMs` / `dropRate`；5000 容量档 Mock 时间窗放宽到实际跨度 |
| `nginx/default.conf.template` | 开启 `gzip_static on`，直接托管构建期 `.gz` |

未新增 npm 依赖。

---

## 3. 拆包与压缩

### 3.1 拆包前后主要 chunk（解压后体积，KB）

| 拆包前（HEAD 构建，40 文件） | KB | 拆包后（66 文件） | KB |
|---|---|---|---|
| `index-C8EXzNa6.js`（入口） | **1,101.9** | `index-CtL36iAr.js`（入口） | **95.0** |
| `index-Bdl1AhYp.css` | 375.9 | `vendor-element-plus-*.css` | 351.7 |
| `http-7TQ5EWJ0.js`（axios 等） | 214.8 | `vendor-element-plus-*.js` | 627.3 |
| `RoutingEditorView-Cu-cEaBx.js` | 171.9 | `vendor-flow-*.js`（路由懒加载，首屏不加载） | 146.9 |
| `StrategyListView-Y6kF_iJU.js` | 21.9 | `vendor-axios-*.js` | 56.2 |
| `api-BtXut79U.js` | 20.6 | `GanttTimeline-*.js`（路由懒加载） | 45.8 |
| `ImportCenterView` | 16.9 | `OrderGanttView-*.js`（路由懒加载） | 41.8 |
| `OrdersView` | 13.8 | `vendor-vue-*.js` | 28.6 |
| `PlanPublishView` | 12.7 | `RoutingEditorView-*.js`（路由懒加载） | 25.4 |
| `SystemUsersView` | 12.5 | `StrategyListView-*.js`（路由懒加载） | 22.0 |
| `SandboxView` | 11.5 | `CalendarListView-*.js`（路由懒加载） | 20.1 |
| `MrpAnalysisView` | 9.9 | `vendor-stomp-*.js` | 19.2 |
| `RoutingEditorView css` | 9.8 | `ResourceListView-*.js`（路由懒加载） | 17.8 |
| `AuditLogView` | 7.2 | `BomListView-*.js`（路由懒加载） | 17.7 |
| `SolveTaskView` | 6.7 | `ImportCenterView-*.js`（路由懒加载） | 16.9 |
| `PlanCompareView` | 5.4 | `ResourceGanttView-*.js`（路由懒加载） | 16.0 |
| `RoutingVersionDialog` | 4.6 | `OrdersView-*.js`（路由懒加载） | 14.0 |
| `import-*.js` | 4.2 | `MaterialListView-*.js`（路由懒加载） | 12.8 |
| `RoutingListView` | 3.9 | `PlanPublishView-*.js`（路由懒加载） | 12.7 |
| `DashboardView` | 1.7 | `SystemUsersView-*.js`（路由懒加载） | 12.6 |
| 其余小碎片（<5KB） | — | `SandboxView-*.js`（路由懒加载） | 11.5 |

**总量**：`dist` 基础产物 **2,044.4KB / 40 文件 → 1,717.8KB / 66 文件（-16.0%）**。
其中首屏实际加载（Dashboard）：`index.js 95.0 + element-plus.js 627.3 + element-plus.css 351.7 + axios 56.2 + vue 28.6 + stomp 19.2 + 小碎片 ≈ 1,188KB` 解压体积；拆包前为 `1,101.9 + 214.8 + 375.9 ≈ 1,693KB`。

> 入口 chunk 下降 91.4% 是硬指标；总产物体积只降 16%，因为 Element Plus 已从“全量”降到“49 个组件”，但仍是首屏最大依赖。

### 3.2 预压缩产物

| 项 | 值 |
|---|---|
| `.gz` | 46 个，合计 **492.4KB** |
| `.br` | 46 个，合计 **446.6KB** |
| `dist` 总文件数 | **40 → 158**（66 个基础产物 + 46 gz + 46 br） |
| 首屏实测传输 | **1,729.6KB（未压缩）→ 301.9KB（br）**，gzip 口径约 326KB |
| `index.html` | 入口引用仍为 `/assets/index-*.js`，SPA 回退正常 |

压缩实现：`node:zlib` 在 `closeBundle` 阶段为 `.js/.css/.html/.svg/.json` 生成同名 `.gz/.br` 兄弟文件，`<1KB` 跳过。**没有新增依赖**，原因是 Windows 下多会话共用 `node_modules`，安装新依赖有文件锁风险；内置 zlib 已足够。

Nginx：已开启 `gzip_static on`，优先命中 `.gz`。官方 `nginxinc/nginx-unprivileged` 镜像**未编译 `ngx_brotli`**，因此 `.br` 目前只是预生成备用，接入带 brotli 模块的网关/CDN 后可开启 `brotli_static`（本文档未验证该路径）。

---

## 4. 骨架屏

| 页面 | 行为 |
|---|---|
| 首屏 `/`（Dashboard） | 健康检查返回前展示整页结构骨架（标题/工具栏/多行），不再白屏 |
| 所有使用 `DataTable` 的列表页 | 首次加载且无数据时展示 8 行骨架；有数据后回落到原表格与 `v-loading` |
| 资源甘特 / 订单甘特 | `dataset` 为空且 `loading` 时展示 10 行舞台骨架 |

未全站重写，只覆盖首屏与主列表/主甘特路径。

---

## 5. 甘特虚拟滚动 + 帧采样

**数据**：`/gantt-orders?dataset=5000x50`，数据源 Mock（固定 seed `20261108`），**5000 任务 / 1667 个订单行 / 50 资源**。

| 指标 | 实测 |
|---|---|
| 渲染行数 / 数据行数 | **20 / 1667（1.2%）** |
| 舞台 DOM 节点 | **332**（整页 43,537，主要来自订单明细表） |
| 滚动采样 | 120 帧，`.gantt-scroll` 从 0 等步长推进到 `scrollHeight` |
| 平均帧间隔 | **21.12ms** |
| FPS | **47.3** |
| 掉帧（>25ms）/ 掉帧率 | **30 / 120 = 25.0%** |
| 5 轮全量加载+分析+渲染 | avg **2,954.76ms** / P95 **3,226.7ms** / min 2,791ms / max 3,226.7ms |

测量口径：`requestAnimationFrame` 相邻回调间隔；`>25ms` 记为掉帧（约 40FPS 以下）。探针页面截图：`D:\tools\_aps-localdata\m3-fe-perf\after-gantt-5000.png`。

**修复的测量陷阱**：原实现用 11-01→11-12 的固定窗口过滤 Mock 数据，5000 档实际只剩 **1,165 任务 / 424 行**。本批把 Mock 下 5000 档的时间窗放宽到整档实际跨度（11-01→12-31），页面显示恢复为 **5,000 任务 / 1,667 行**，上述数字才是真实 5000 规模。

---

## 6. FCP / LCP 真实浏览器复测

**方法**：`npm run build` 产物 → Python 静态服务器（回环 `127.0.0.1`，按 `Accept-Encoding` 直接返回预压缩 `.br`，`Cache-Control: no-store`）→ Google Chrome（Playwright 驱动，headless，1440×900，每次新建上下文=冷缓存）→ `performance.getEntriesByType('paint')` + LCP `PerformanceObserver`。**未使用 dev server / vite preview**。

对照组：把 **HEAD** 用 `git archive` 导出到临时目录，链接同一份 `node_modules`，用同一构建命令重建，再跑同一脚本（同一端口类型、同一浏览器、同一脚本）。`/api/*` 统一快速返回 503，避免请求悬挂影响首屏；页面因此显示“请求失败（HTTP 503）”，FCP 不受影响。

### 6.1 回环网络（无网络限速），5 次取中位数

| 指标 | 拆包前（HEAD） | 拆包后（本次） | 变化 |
|---|---|---|---|
| FCP | **216ms**（208–268） | **200ms**（172–248） | **-7.4%** |
| LCP | **216ms**（208–268） | **220ms**（204–248） | ≈ 持平 |
| DOMContentLoaded | 108.7ms | 94.9ms | -12.7% |
| 首屏传输 | 1,729.6KB | 301.9KB | **-82.5%** |
| 资源条目 | 16 | 22 | +6（拆包后并行加载） |

回环下没有带宽/延迟瓶颈，FCP 主要由解析执行决定，因此压缩带来的收益在 FCP 上仅 16ms。

### 6.2 模拟 10Mbps / 40ms RTT，3 次取中位数

| 指标 | 拆包前（HEAD） | 拆包后（本次） | 变化 |
|---|---|---|---|
| FCP | **1,704ms**（1,684–1,764） | **684ms**（672–748） | **-59.9%** |
| LCP | **1,704ms** | **772ms** | **-54.7%** |
| DOMContentLoaded | 1,537ms | 426ms | -72.3% |
| 首屏传输 | 1,729.6KB | 301.9KB | **-82.5%** |

两档均满足“FCP < 2s”。

### 6.3 与验收报告 5,140–6,948ms 的关系

验收报告 `<docs/perf/m3-qa-02-nonfunctional.md>` 明确记录了矛盾：**FCP 5.1–6.9s，但 DOMContentLoaded 仅 68–261ms**，并自述“可能来自自动化浏览器的绘制调度（首个截图/前台化才记录 paint）”。本次用同一脚本、同一机器复现 HEAD 时，**FCP 为 208–268ms，无法复现 5–6s**。因此：

- 5,140–6,948ms 不能作为可复现的改进基线；
- 本报告采用 HEAD 同口径 A/B（216ms → 200ms 回环；1,704ms → 684ms 限速）；
- 传输体积 1.73MB → 0.30MB 是可复现的确定性收益，限速网络下直接转化为 FCP/LCP 下降。

证据截图：`D:\tools\_aps-localdata\m3-fe-perf\before-fcp-run*.png`、`after-fcp-run*.png`。

---

## 7. 校验与纪律

| 项 | 结果 |
|---|---|
| `npm run build`（含 `vue-tsc --noEmit`） | **通过**，构建 933ms |
| `npx eslint . --max-warnings 0` | **0 error / 0 warning** |
| 路由冒烟（生产构建，17 条路由） | **17/17 PASS**，无 “Failed to resolve component” / 运行时异常 |
| 首屏与甘特视觉 | 截图确认 Element Plus 样式、布局、探针结果正常 |
| 后端 / Maven | 未执行 `mvn`，未改后端契约 |
| `src/mocks/handlers.ts` | 未修改 |
| commit / push | 未执行 |
| 静态服务器 | 4173/4174 均**已停止**，端口已释放；未留后台进程 |
| 端口说明 | 18099 被本机策略拒绝（WinError 10013），改用 4173/4174 |

> `dist` 的 hash 文件名因重新构建而变化，这是正常产物变化；入口 `index.html` 引用完整、SPA 回退正常，未改目录结构。

---

## 8. 新增依赖

**无**。`package.json` / `package-lock.json` 未变动。预压缩使用 Node 内置 `node:zlib`；拆包使用 Vite 8/Rolldown 原生 `codeSplitting.groups`。

---

## 9. 这次改动没有验证到的部分

1. **真实后端 5000 任务数据**：本次甘特 5000 规模全部来自 Mock（`source=mock`，固定 seed），未对 `/api/v1/gantt/order` 的 2.12MB 真数据做前端滚动采样；`source=api` 路径仅保证接口契约不变。
2. **真实移动端 / 低端 CPU / 其他浏览器**：仅 Chrome（headless）+ 本机 9800X3D；未测 Firefox/Safari、未做 4×–6× CPU 降频、未测手机视口。
3. **Brotli 在部署链路生效**：本地静态服务验证了 `.br` 命中；但官方 Nginx 镜像无 `ngx_brotli`，仅启用 `gzip_static`，CDN/网关的 brotli 配置未验证；Docker 镜像构建未执行。
4. **全量交互路径**：17 条路由冒烟只覆盖页面挂载与首屏组件；上传、树拖拽、日期选择、弹窗确认等 49 个组件的交互态未逐一回归（菜单关闭/折叠等边缘场景）。
5. **慢接口下的骨架观感**：`/api` 在复测中固定 503，未能观察真实慢查询（如 MRP 300ms+）下骨架切换的视觉连续性。
6. **长时滚动稳定性**：帧采样为 120 帧的短程程序化滚动，未覆盖连续 60s 用户滚动、滚动中缩放、拖动调整等复合操作。
7. **服务端压缩收益上限**：限速为 10Mbps/40ms 的单点模拟，未做 4G/弱网丢包与 HTTP/2 多路复用对比。

---

## 10. 原始证据（本机）

- 构建产物：`d:\tools\smart-aps-agent\aps-frontend\dist`
- 首屏/甘特截图：`D:\tools\_aps-localdata\m3-fe-perf\`（`before-fcp-run*.png`、`after-fcp-run*.png`、`after-gantt-5000.png`）
- 复测脚本：同目录 `server.py`、`measure.py`、`throttle.py`、`smoke.py`（临时脚本，未入库）
- 基线 HEAD 构建：`D:\tools\_aps-localdata\m3-fe-perf\before\aps-frontend\dist`
