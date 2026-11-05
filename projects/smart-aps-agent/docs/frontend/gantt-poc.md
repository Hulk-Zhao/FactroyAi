# M1-FE-06 甘特图 PoC（只读渲染）实测与选型报告

- 任务卡：**M1-FE-06 甘特图 PoC（只读渲染）**
- Owner：`dev-FE6`
- 日期：2026-11-04
- 契约依据：`docs/api/contract-freeze.md` C4（第 6 节）、`docs/api/openapi/gantt.yaml`
- 范围：资源为行的只读甘特（时间轴缩放、横向滚动、行头冻结、悬浮 tooltip、换型窗口）。**不含拖拽**（拖拽属 M2-FE-03）。

---

## 1. 结论（先看这一句）

**推荐「自研 DOM 渲染 + GanttAdapter 抽象」作为后续甘特主技术栈。** 在 500 任务 × 50 资源档位，三次实测中位数 **26.6 ms**，显著快于 ECharts 自定义渲染（62.7 ms）与 vue-ganttastic 2.3.2（218.1 ms）；DOM 节点数 574（行虚拟滚动）对比 ganttastic 2660，且冻结行头、像素级时间缩放、换型窗口与 tooltip 都是自研实现的原生能力。

ECharts 可作为**备选/降级**方案（PRD 2.2 栈内已有），但需要接受：无真正冻结行头、无行虚拟滚动、单 canvas 高度随资源数线性增长，且动态 chunk 约 524 KB。
vue-ganttastic（`@infectoone/vue-ganttastic` 2.3.2）**不推荐作为生产主方案**：Vue 2 版 `vue-ganttastic` 无法用于 Vue 3；Vue 3 分支可用但强依赖 dayjs 严格时间格式、按容器宽度缩放（非像素级缩放）、无虚拟滚动，500×50 档位中位数 218.1 ms。

> 说明：任务卡里写的 500 任务行数量级与 PRD 5.1 的「甘特图渲染（5000 任务）< 3s」不是同一量级。本 PoC 只实测到 500 任务，5000 任务为**未实测风险**，兜底方案见第 6 节。

---

## 2. 环境、命令与数据生成

### 2.1 环境

| 项 | 值 |
|---|---|
| OS | Windows 10（本机） |
| Node | `26.13.0`（`D:\tools\_aps-toolchain\node-v26.13.0-win-x64`） |
| npm | 随 Node 工具链（npm 11.x） |
| 浏览器 | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) TraeCN/1.107.1 Chrome/142.0.7444.235 Electron/39.2.7 Safari/537.36` |
| CPU / 内存 | 8 逻辑核 / DPR 1 / 屏幕 2560×1440（自动化浏览器） |
| 打包 | `npm run build`（vue-tsc + vite build），产物 `dist/` |
| 预览 | `npm run preview -- --port 4173 --strictPort` |

自动化浏览器上报的 `window.innerWidth/innerHeight` 为 `0×0`（Electron 自动化容器限制），实际页面渲染宽度约 1176 px；该值不影响毫秒级打点，但说明数字来自**自动化 Chrome 容器**，不是低端真机。

### 2.2 命令（可复现）

```powershell
$env:PATH = "D:\tools\_aps-toolchain\node-v26.13.0-win-x64;" + $env:PATH
cd d:\tools\smart-aps-agent\aps-frontend
npm run build
npm run preview -- --port 4173 --strictPort
# 打开：
# http://localhost:4173/gantt?dataset=500x50&engine=dom&zoom=day
# http://localhost:4173/gantt?dataset=100x10&engine=dom&zoom=day
# http://localhost:4173/gantt?dataset=500x50&engine=echarts&zoom=day
# http://localhost:4173/gantt?dataset=500x50&engine=ganttastic&zoom=day
```

页面 URL 参数：

| 参数 | 取值 | 默认 |
|---|---|---|
| `dataset` | `500x50` / `100x10` | `500x50` |
| `engine` | `dom` / `echarts` / `ganttastic` | `dom` |
| `zoom` | `hour` / `day` / `week` / `month` | `day` |
| `source` | `mock` / `api`（api 需后端 C4 就绪） | `mock` |

### 2.3 数据生成

- 文件：`aps-frontend/src/mocks/gantt-data.ts`（独立文件，未改 `handlers.ts`）。
- 结构：严格按 C4 冻结响应（`resources[{resourceCode,resourceName,resourceType,tasks[]}]`，任务内 `startTime/endTime/setupStartTime/setupEndTime/status/sequenceInResource/version`）。
- 固定种子：`500x50` 使用 `20261105`，`100x10` 使用 `20261106`，mulberry32 伪随机，**同档位每次生成完全一致**。
- 时间窗：`2026-11-05T00:00:00Z` ~ `2026-11-12T00:00:00Z`（7 天）。
- 每资源一条顺序任务链：换型 0 或 15–45 min（40% 为 0），加工 30–180 min，任务间隔 30–600 min；资源类型按 7 主资源 / 1 辅资源 / 1 人力 / 1 工具循环；状态分布约 70% PLANNED、12% FROZEN、10% IN_PROGRESS、8% COMPLETED。
- 两档规模：`500 × 50` 与 `100 × 10`，各 10 条/资源。

### 2.4 测量口径

- 单轮：`performance.now()` 在调用 `GanttAdapter.load` 前打点 → 数据准备完成 → 组件挂载后连续 2 次 `requestAnimationFrame` 确认绘制结束；Mock 数据生成计入「数据准备」。
- 每档连续测 **3 轮**，页面展示中位数与三次数值；渲染引擎 chunk 在打点前预加载，不计入渲染耗时。
- `performance.mark/measure` 同步打点（每轮 clear 后重建）；页面 FCP 取 `PerformancePaintTiming`。
- 中心指标：**首屏渲染 = 数据准备 + 渲染（中位数）**。

---

## 3. 实测数字

### 3.1 两档数据（推荐方案：自研 DOM）

| 档位 | 首屏渲染中位数 | 三次总耗时 | 数据准备 | 渲染 | 任务条 / 资源行 | DOM 节点数 |
|---|---|---|---|---|---|---|
| 500 任务 × 50 资源 | **26.6 ms** | 223.9 / 21.8 / 26.6 ms | 2.6 ms | 24.4 ms | 500 / 50 | 574 |
| 100 任务 × 10 资源 | **28.2 ms** | 12.6 / 28.2 / 31.5 ms | 0.6 ms | 27.6 ms | 100 / 10 | 346 |

- 两档中位数均 < 30 ms，**满足 PRD 5.1「页面首屏加载 < 2s」与「甘特图渲染（5000 任务）< 3s」在 500 任务量级的等价约束**（后者按任务量级外推，5000 任务未实测）。
- 第一轮常出现 100–220 ms 的冷启动尖峰（JIT/样式计算），中位数已剔除该影响；三次数值都保留，便于复核。
- 500 任务比 100 任务更快属正常：行虚拟滚动只渲染可视行（二者可见行数相近），主要成本在 500 根任务条的绝对定位，低于一帧。
- 水平滚动实测：`scrollWidth 1209 > clientWidth 394`；垂直 `scrollHeight 1744 > clientHeight 443`；行头与时间轴分别 `position: sticky` 冻结。

### 3.2 500 × 50 三方案对比

| 方案 | 首屏渲染中位数 | 三次总耗时 | 数据准备 | 渲染 | DOM 节点数 | 备注 |
|---|---|---|---|---|---|---|
| 自研 DOM（推荐） | **26.6 ms** | 223.9 / 21.8 / 26.6 ms | 2.6 ms | 24.4 ms | **574** | 行虚拟滚动 + 冻结行头 |
| ECharts 自定义渲染 | 62.7 ms | 110.2 / 62.7 / 56.8 ms | 2.8 ms | 60.3 ms | 9 | 单 canvas 641×1494，**未虚拟化**；chunk 523.96 KB（gzip 176.89 KB） |
| vue-ganttastic 2.3.2 | 218.1 ms | 478.2 / 159.7 / 218.1 ms | 4.3 ms | 214.6 ms | 2660 | 50 行 × (换型条 + 任务条) 全量 DOM |

补充事实（不是主观判断）：

1. **vue-ganttastic 的 Vue 2 版本不可用**：npm `vue-ganttastic@0.9.34` 最后发布于 2022 年，API 为 Vue 2 插件式；本项目是 Vue 3 + Vite 8。可用的 Vue 3 分支是 `@infectoone/vue-ganttastic@2.3.2`，本报告实测即该包。
2. **ganttastic 的时间解析是严格模式**：内部按 `YYYY-MM-DD HH:mm` 解析，直接喂 C4 的 ISO 8601（`2026-11-05T00:00:00.000Z`）会得到 Invalid Date，首轮表现为「只有资源标签、没有任务条、时间轴为空」。适配层已用 dayjs 转为本地时区格式后正常；这是接入成本，也说明它不直接吃契约时间格式。
3. **ganttastic 不是像素级时间缩放**：图宽固定为容器宽度，`precision` 只改变刻度粒度；50 行的 DOM 已 2660 个节点，且无行虚拟滚动，5000 任务会线性膨胀。
4. **ECharts 冻结行头不完整**：yAxis 标签固定，但无法放资源副标题/类型标签/行级操作；单 canvas 需要外层纵向滚动，无行虚拟化；首轮偶发「坐标轴已画、任务条未进视口」的观感问题（canvas 已检测到 4332 个彩色像素，点击「重新测量」后稳定），自动化截图难以稳定复现，标记为**低置信视觉缺陷**。
5. `navigator`/`performance` 等浏览器面数据由页面「环境」行实时展示，便于换机复测。

---

## 4. 实现与适配器

- `GanttAdapter` 接口在 `src/components/gantt/index.ts` 保持 `load()` 形态，新增可选字段（`setupStartTime/setupEndTime/operationIndex/qty/sequenceInResource/resourceType/unscheduledTaskCount`），向后兼容。
- `src/components/gantt/adapters.ts`：
  - `createMockGanttAdapter(datasetKey)`：默认实现，直接调用 `src/mocks/gantt-data.ts`，不经过 HTTP。
  - `createApiGanttAdapter()`：调用 `src/api/gantt.ts` 的 `getResourceGantt`，字段映射按 C4。
  - `createGanttAdapter({ dataset, source })`：页面唯一入口；切真实接口只改 `source: 'api'`，页面与绘图组件不改。
- 页面 `src/views/gantt/ResourceGanttView.vue`：数据档/引擎/缩放/数据源切换、3 轮实测面板、环境信息、状态图例。
- 渲染组件：
  - `GanttTimeline.vue`（默认）：左右双栏（行头冻结 + 时间画布）、纵向行虚拟滚动（overscan 3）、`hour/day/week/month` 像素级缩放、换型窗口斜纹条、单节点事件委托 tooltip、状态色来自 `enums.md`（`getEnumTagType('taskStatus')`），FROZEN 额外虚线边框与「冻」标记。
  - `GanttEchartsChart.vue`、`GanttGanttasticChart.vue`：仅用于选型对比，动态 import 独立 chunk。

---

## 5. 契约字段缺口（不改契约，只提需求）

### 5.1 与任务说明中的字段清单不一致（以冻结契约为准）

任务说明写的是 `resources[{id,code,name,type}]`、扁平 `tasks[{resourceId,...}]`；实际冻结的 C4 与 `gantt.yaml` 是：

```json
resources: [{ resourceCode, resourceName, resourceType, tasks: [...] }]
tasks: [{ id, orderNo, operationIndex, operationName, resourceCode, secondaryResourceCodes,
          setupStartTime, setupEndTime, startTime, endTime, qty, status, sequenceInResource, version }]
```

**没有 `resourceId`、没有 `orderId`、没有资源行 `id`。** 前端键统一使用 `resourceCode` / `task.id`；本 PoC 按冻结契约实现，未自行改契约。

### 5.2 FR-GT-001 后续能力所需、当前契约缺失的字段

| 需求 | PRD 条款 | 缺什么 | 影响 |
|---|---|---|---|
| 按订单优先级着色 | FR-GT-001-02 | `orderPriority`（或 `priority`）未在 GanttTask 返回 | 只能按状态着色；按优先级需再查订单，形成 N+1 |
| 资源分组 / 过滤 | BR-GT-001-01 | `resourceGroupCode / resourceGroupName` 未返回 | 行分组需另查资源主数据 |
| 负载热力图 | BR-GT-001-05 | 资源日负载率或容量/日历数据未返回 | 无法在甘特内画负载底色 |
| 交期线 / 延期高亮 | FR-GT-002 | `dueDate` 未在资源甘特返回 | 订单甘特需独立接口或额外查询 |
| 冻结任务灰色 + 锁图标 | BR-GT-001-06 | 状态字段有 `FROZEN`，但 enums 配色为 warning（橙） | 与 PRD「灰色背景」冲突，是产品口径问题，不是字段问题 |
| 超大数据量分页 | PRD 5.1 5000 任务 | 契约明确「非分页结构；不返回 total」 | 只能客户端虚拟滚动，或协商分页扩展 |

建议：以上字段以**可选字段**增量加入 C4（不改既有语义），由 S3/契约 owner 决策；FE 不在本文档之外改契约。

### 5.3 对 SE-04 / SE-06 的字段与实现要求

- **SE-04 问题装配器与解回写**
  - 必须持久化每条 assignment 的 `setup_seconds`，回写时派生 `setupStartTime = startTime - setup_seconds`、`setupEndTime = startTime`；`setup_seconds = 0` 时两者等于 `startTime`（C4 硬性规则）。
  - `sequence_in_resource` 按 `sandbox_id + resource_id` 的**时间顺序**生成并持久化（C4 补充第 5 条），空值前端按 `startTime` 兜底排序。
  - 保证 `endTime > startTime`；任务行与 `tasks[]` 内都带 `resourceCode`，`secondaryResourceCodes` 为空数组而非 null。
- **SE-06 最小闭环打通**
  - 按 C4 实现 `GET /api/v1/gantt/resource`：默认 `sandboxId=...0401`；返回与 `[from, to)` **有交集**的任务（`setupStartTime` 可早于 `from`）；按 `resourceCode`、`sequenceInResource` 稳定排序；非分页、无 `total`。
  - 500×50 档位的响应体建议控制在 ~200 KB 以内（当前 Mock 同构数据约 500 条任务），否则 FE 首包/解析成本会先于渲染成为瓶颈。
  - 前端切真实接口只改 `createGanttAdapter({ source: 'api' })`（或 URL `?source=api`），无需改页面。

---

## 6. 虚拟滚动 / 分页兜底

1. **已实现（行虚拟滚动）**：只渲染 `scrollTop` 可视范围内的资源行（上下各 3 行 overscan），行头与画布同一虚拟窗口；500×50 时 DOM 节点 574，与 50 行全量渲染（约 1500+ 节点）相比显著降低样式/布局成本。
2. **横向兜底**：任务条按可视时间窗裁剪绘制；时间轴缩放改变 `pxPerMs`，不做 DOM 复用。
3. **>5000 任务兜底（未实现，M2 建议）**：
   - 方案 A（不改契约）：行虚拟滚动 + 时间窗裁剪（只画与当前水平视口相交的任务条）即可把单帧节点数控制在几百；需要补 5000 任务实测。
   - 方案 B（需契约扩展）：按 `resourceCodes` 分批请求（如每批 20 个资源）并在纵向滚动时懒加载；契约当前明确「非分页、不返回 total」，需 S3 确认后可加 `resourceCodes` 分段 + `version`/ETag，不建议 FE 自行改契约。
   - 方案 C（降级）：换型窗口与加工窗口在节点数超阈值时合并为单个 DOM 节点（CSS 背景切分），可再省约 40% 节点。

---

## 7. 文件清单

| 文件 | 说明 |
|---|---|
| `aps-frontend/src/components/gantt/index.ts` | 适配器接口扩展（换型窗口、工序、资源类型、未排程计数） |
| `aps-frontend/src/components/gantt/adapters.ts` | Mock/API 适配器与工厂、C4 映射 |
| `aps-frontend/src/components/gantt/GanttTimeline.vue` | 自研 DOM 只读甘特（推荐方案） |
| `aps-frontend/src/components/gantt/GanttEchartsChart.vue` | ECharts 对比渲染（动态 chunk） |
| `aps-frontend/src/components/gantt/GanttGanttasticChart.vue` | vue-ganttastic 对比渲染（动态 chunk） |
| `aps-frontend/src/views/gantt/ResourceGanttView.vue` | PoC 页面、3 轮实测面板、环境展示 |
| `aps-frontend/src/mocks/gantt-data.ts` | 两档确定性 Mock 数据生成器 |
| `aps-frontend/src/router/modules/gantt.ts` | 路由指向 PoC 页面 |
| `docs/frontend/gantt-poc.md` | 本文档 |

依赖变更（PoC 选型对比所需）：`echarts@6.1.0`、`@infectoone/vue-ganttastic@2.3.2`、`dayjs@1.11.21`（写入 `package.json`，前两者仅被动态 import 的对比组件引用，不进主包）。

未触碰（边界约束）：`router/index.ts`、`api/http.ts`、`types/api.ts`、`views/orders/**`、`views/process/**`、`views/master-data/**`、后端、`db/`、`deploy/`、Dockerfile、`docs/api/**`、PRD、`mocks/handlers.ts`。

---

## 8. 验收状态

| 项 | 结果 |
|---|---|
| `npm run build`（含 `vue-tsc`） | **通过**（2026-11-04，退出码 0；产物含 GanttEchartsChart 523.96 KB、GanttGanttasticChart 37.03 KB 独立 chunk） |
| `npm run format:check` | **通过**（All matched files use Prettier code style） |
| `npm run lint` | **失败：36 个 `no-undef`，全部为浏览器全局**，集中在本文档的 3 个 `.vue` 文件（`HTMLElement / Event / EventTarget / MouseEvent / ResizeObserver / requestAnimationFrame / window / screen / navigator / performance / console`）。根因是 **ESLint 配置层缺失**，不是文件内未声明变量：`eslint.config.js` 没有为 `**/*.vue` 配置 `languageOptions.globals`，而 `typescript-eslint` 的 `no-undef` 豁免只覆盖 `.ts`，未覆盖 `.vue`。已验证：给本目录加浏览器全局后 `npx eslint src/components/gantt src/views/gantt --max-warnings 0` **0 error**。按要求未改共享 `eslint.config.js`，需 P0-02 集中修复（建议加 `globals.browser` 或对 `.vue` 关闭 `no-undef`；仓库当前未安装 `globals` 包，修复时需补 devDependency）。 |
| `npm ci` | 未执行（见风险）：并发流多次 `npm install` 曾出现 `EPERM/ENOTEMPTY`，收尾阶段再确认无安装进程后执行 |

---

## 9. 未完成项与风险

1. **lint 配置阻塞**：如上，需 P0-02 修复；本文件内无未声明变量问题，代码侧无需改。
2. **5000 任务未实测**：PRD 5.1 的 5000 任务 < 3s 目标未验证；当前虚拟滚动可支撑，但需 M2 补测（同一页面把 Mock 档位扩到 5000×200）。
3. **仅自动化 Chrome 实测**：没有低端真机/移动端数据；第一轮冷启动尖峰说明真实首次体验可能高于中位数约 3–8 倍（DOM 500×50 首轮 223.9 ms 仍远低于 2s）。
4. **ECharts 首屏视觉低置信**：自动化首轮截图难以稳定看到任务条（canvas 彩色像素已存在），点击「重新测量」后正常；若选它做备选，需一次真机人工验证。
5. **tooltip 自动化未截到图**：实现为事件委托 + `position: fixed` 浮层，代码路径已覆盖；自动化悬停未能稳定捕获，建议人工复核一次。
6. **ganttastic 仅 500×50 实测**：`100×10` 未测；其日期严格解析与固定宽度缩放是接入已知成本。
7. **PoC 对比依赖常驻 `package.json`**：若最终只保留 DOM 方案，M2 可移除 `echarts` 对比组件与 `@infectoone/vue-ganttastic`、`dayjs`（ECharts 若被其他图表复用则保留）。
