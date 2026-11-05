# APS 前端 UI 只读盘点（ui-audit）

- 仓库：`d:\tools\smart-aps-agent\aps-frontend`，栈：Vue3 + Element Plus + Pinia + Vite + TypeScript。
- 范围：`src/views/**/*.vue`（28 个文件）、`src/router/modules/*.ts`、`src/layouts`、`src/styles/main.css`、`src/components/common`。
- 方法：逐文件读取 + 全量正则统计；所有行号均为实际文件行号。行号中的文件路径相对 `src/`。
- 口径：只判断「内部不一致」与「功能缺失」；Element Plus 默认样式本身不计为问题。本文档为只读盘点，未改动任何源码。

## 1. 页面清单

统计：`src/views` 共 **28 个 .vue**，其中 **15 个路由页面**（14 个业务页 + 1 个兜底 `NotFoundView`）、**12 个非路由组件**（7 个 Dialog/Drawer + 5 个内嵌面板/时间轴）、**1 个孤儿页面**（PlaceholderView 未注册路由）。路由总表在 `router/modules/index.ts:18-29`，根路由在 `router/index.ts:12-29`。

| # | 文件（`src/views/`） | 路由 path（来源文件:行） | 引入方式（行） | 主要区块/卡片（模板行） | 用到的 Element Plus 标签 | DataTable/PageState/StatusTag（import 行 / 使用行） |
|---|---|---|---|---|---|---|
| 1 | `DashboardView.vue` | `/`（`router/modules/dashboard.ts:11-13`） | 懒加载 `() => import`（`dashboard.ts:13`） | 标题 25；卡片 26/39 | el-card, el-skeleton, el-result, el-alert, el-descriptions, el-descriptions-item | 无 |
| 2 | `NotFoundView.vue` | `:pathMatch(.*)*`，name=not-found（`router/index.ts:20-25`） | 静态 `import`（`router/index.ts:4`） | el-result 8 | el-result, el-button | 无 |
| 3 | `PlaceholderView.vue` | **无路由**：`router/modules` 未注册（`modules/index.ts:18-29` 无引用） | 未被引用（孤儿） | 标题 14；卡片 15 | el-card, el-empty | 无 |
| 4 | `OrdersView.vue` | `/orders`（`router/modules/orders.ts:11-13`） | 懒加载（`orders.ts:13`） | 标题 205；卡片 207；DataTable 263；弹层 338/340 | el-card, el-form, el-form-item, el-input, el-select, el-option, el-date-picker, el-button, el-table-column | DataTable+StatusTag 均 `:7`；用于 263 / 284,289,294 |
| 5 | `orders/OrderFormDialog.vue` | 无路由，被 `OrdersView.vue:338` 使用 | 静态 `import`（`OrdersView.vue:12`） | el-dialog 154；表单 160；保存 204 | el-dialog, el-form, el-form-item, el-input, el-input-number, el-select, el-option, el-date-picker, el-button | 无 |
| 6 | `orders/OrderDetailDrawer.vue` | 无路由，被 `OrdersView.vue:340` 使用 | 静态 `import`（`OrdersView.vue:11`） | drawer 36；StatusTag 49/53/65/68；section 62/81；空态 96 | el-drawer, el-descriptions, el-descriptions-item, el-alert, el-button, el-empty | StatusTag `:4`；用于 49/53/65/68 |
| 7 | `SolveTaskView.vue` | `/solve-tasks`（`router/modules/solve.ts:11-13`） | 懒加载（`solve.ts:13`） | 标题 191；卡片 192；错误 213；进度卡片 215；取消 256 | el-card, el-form, el-form-item, el-input, el-select, el-option, el-button, el-alert, el-progress, el-descriptions, el-descriptions-item, el-tag, el-tooltip | StatusTag `:8`；用于 220 |
| 8 | `master-data/materials/MaterialListView.vue` | `/master-data/materials`（`router/modules/master-data.ts:30-32`） | 静态 `import`（`master-data.ts:4`） | 标题 115；卡片 117；DataTable 154；弹层 191/193 | el-card, el-form, el-form-item, el-input, el-select, el-option, el-button, el-table-column | DataTable+StatusTag `:6`；用于 154 / 174 |
| 9 | `master-data/materials/MaterialFormDialog.vue` | 无路由，被 `MaterialListView.vue:191` 使用 | 静态 `import`（`MaterialListView.vue:11`） | dialog 186；表单 193；扩展属性 249；保存 270 | el-dialog, el-form, el-form-item, el-input, el-input-number, el-select, el-option, el-row, el-col, el-alert, el-button | 无 |
| 10 | `master-data/materials/MaterialDetailDrawer.vue` | 无路由，被 `MaterialListView.vue:193` 使用 | 静态 `import`（`MaterialListView.vue:10`） | drawer 41；StatusTag 52；section 76/79；空态 95 | el-drawer, el-descriptions, el-descriptions-item, el-alert, el-button, el-empty | StatusTag `:4`；用于 52 |
| 11 | `master-data/boms/BomListView.vue` | `/master-data/boms`（`master-data.ts:36-38`） | 静态 `import`（`master-data.ts:2`） | 标题 169；卡片 171；DataTable 197；结构树 243；弹层 257/264 | el-card, el-form, el-form-item, el-input, el-select, el-option, el-button, el-row, el-col, el-table-column | DataTable `:8`、StatusTag `:7`；用于 197 / 216 |
| 12 | `master-data/boms/BomFormDialog.vue` | 无路由，被 `BomListView.vue:257` 使用 | 静态 `import`（`BomListView.vue:15`） | dialog 215（width 218）；表单 222；组件区 277；表格 278；保存 343 | el-dialog, el-form, el-form-item, el-input, el-input-number, el-date-picker, el-switch, el-row, el-col, el-table, el-table-column, el-alert, el-button | 无 |
| 13 | `master-data/boms/BomDetailDrawer.vue` | 无路由，被 `BomListView.vue:264` 使用 | 静态 `import`（`BomListView.vue:14`） | drawer 42；StatusTag 56；section 66；表格 67；空态 89 | el-drawer, el-descriptions, el-descriptions-item, el-table, el-table-column, el-button, el-empty | StatusTag `:4`；用于 56 |
| 14 | `master-data/boms/BomTreePanel.vue` | 无路由，内嵌于 `BomListView.vue:243` | 静态 `import`（`BomListView.vue:16`） | 卡片 73；header 74；PageState 86；空态 87/88；v-loading 92 | el-card, el-empty, el-tree, el-tag, el-button | PageState `:5`；用于 86 |
| 15 | `master-data/resources/ResourceListView.vue` | `/master-data/resources`（`master-data.ts:42-44`） | 静态 `import`（`master-data.ts:5`） | 标题 163；卡片 173；DataTable 219；弹层 276 | el-card, el-form, el-form-item, el-input, el-select, el-option, el-button, el-alert, el-tag, el-table-column | DataTable+StatusTag `:7`；用于 219 / 239,244 |
| 16 | `master-data/resources/ResourceFormDialog.vue` | 无路由，被 `ResourceListView.vue:276` 使用 | 静态 `import`（`ResourceListView.vue:12`） | dialog 285（width 288）；表单 292；换算区 416-450；保存 485 | el-dialog, el-form, el-form-item, el-input, el-input-number, el-select, el-option, el-row, el-col, el-alert, el-descriptions, el-descriptions-item, el-button | 无 |
| 17 | `master-data/calendars/CalendarListView.vue` | `/master-data/calendars`（`master-data.ts:48-50`） | 静态 `import`（`master-data.ts:3`） | 标题 129；卡片 139；DataTable 167；停用确认 78；弹层 222 | el-card, el-form, el-form-item, el-input, el-select, el-option, el-button, el-alert, el-tag, el-table-column | DataTable `:6`、StatusTag `:6`；用于 167 / 187 |
| 18 | `master-data/calendars/CalendarFormDialog.vue` | 无路由，被 `CalendarListView.vue:222` 使用 | 静态 `import`（`CalendarListView.vue:10`） | dialog 383（width 386）；表单 415；班次区 484；时段预览 532；例外日 535；表格 485/506/536；时间轴 533；保存 612 | el-dialog, el-form, el-form-item, el-input, el-input-number, el-select, el-option, el-date-picker, el-time-select, el-tabs, el-tab-pane, el-table, el-table-column, el-tag, el-alert, el-button, el-row, el-col | 无（内嵌 ShiftTimeline，`:19`） |
| 19 | `master-data/calendars/ShiftTimeline.vue` | 无路由，内嵌于 `CalendarFormDialog.vue:533` | 静态 `import`（`CalendarFormDialog.vue:19`） | 图例 117；纯 div/CSS 时间轴 | 无 el-* 组件 | 无 |
| 20 | `mrp/MrpAnalysisView.vue` | `/mrp/analysis`（`router/modules/mrp.ts:14-16`） | 懒加载（`mrp.ts:16`） | 标题 107；卡片 109/154/341；表格 207/218/275 | el-card, el-form, el-form-item, el-input, el-checkbox, el-switch, el-button, el-tabs, el-tab-pane, el-table, el-table-column, el-tag, el-alert | PageState `:6`；用于 342（未用 DataTable） |
| 21 | `plans/PlanPublishView.vue` | `/plans/publish`（`router/modules/plans.ts:14-16`） | 懒加载（`plans.ts:16`） | 标题 268；卡片 279/339/366/370/417/451/478/507 | el-card, el-input, el-input-number, el-button, el-alert, el-descriptions, el-descriptions-item, el-tag, el-table-column | DataTable `:14`（297/426/509）、StatusTag `:14`（315/374/384/437） |
| 22 | `strategies/StrategyListView.vue` | `/strategies`（`router/modules/strategies.ts:11-13`） | 懒加载（`strategies.ts:13`） | 标题 277；DataTable 279；导出弹窗 351；导入弹窗 381；预检表格 437 | el-input, el-button, el-alert, el-descriptions, el-descriptions-item, el-dialog, el-table, el-table-column, el-tag | DataTable+StatusTag `:8`；用于 279 / 307 / 437 |
| 23 | `strategies/StrategyEditorDrawer.vue` | 无路由，被 `StrategyListView.vue:344` 使用 | 静态 `import`（`StrategyListView.vue:18`） | drawer 350；校验错误 361；表单 366；维度表 439；规则表 483；保存 531 | el-drawer, el-form, el-form-item, el-input, el-input-number, el-select, el-option, el-date-picker, el-switch, el-row, el-col, el-divider, el-table, el-table-column, el-tag, el-alert, el-button | 无 |
| 24 | `process/routings/RoutingListView.vue` | `/process/routings`（`router/modules/process.ts:20-22`） | 懒加载（`process.ts:22`） | 标题 90；卡片 92；DataTable 127；弹层 165 | el-card, el-form, el-form-item, el-input, el-select, el-option, el-button, el-table-column | DataTable+StatusTag `:11`；用于 127 / 144 |
| 25 | `process/routings/RoutingEditorView.vue` | `/process/routings/:id`（`process.ts:26-28`） | 懒加载（`process.ts:28`） | page-header 482；4 个 alert 502/510/518/526；v-loading 535；画布 537；元信息 555；节点表单 567；边表单 737；空态 775；校验 779；版本弹窗 825 | el-page-header, el-card, el-alert, el-form, el-form-item, el-input, el-input-number, el-select, el-option, el-switch, el-radio-group, el-radio-button, el-empty, el-tag, el-button | StatusTag `:22`；用于 487 |
| 26 | `process/routings/RoutingVersionDialog.vue` | 无路由，被 `RoutingListView.vue:165`、`RoutingEditorView.vue:825` 使用 | 静态 `import`（`RoutingListView.vue:16` / `RoutingEditorView.vue:45`） | dialog 116（width 119）；表单 132；提交 162 | el-dialog, el-form, el-form-item, el-input, el-date-picker, el-alert, el-button | 无 |
| 27 | `gantt/ResourceGanttView.vue` | `/gantt`（`router/modules/gantt.ts:17-19`） | 静态 `import`（`gantt.ts:1`） | 标题 256；工具栏 258；指标卡 339；日历表卡 402；停机窗卡 449；冲突卡 482；甘特卡 532 | el-card, el-form, el-form-item, el-select, el-option, el-checkbox, el-button, el-alert, el-descriptions, el-descriptions-item, el-table, el-table-column, el-tag, el-empty | 无（未用 DataTable/PageState/StatusTag） |
| 28 | `gantt/OrderGanttView.vue` | `/gantt-orders`（`router/modules/gantt-order.ts:17-19`） | 静态 `import`（`gantt-order.ts:1`） | 标题 786；工具栏 788；指标卡 870；订单表卡 941；人工调整卡 972；C14 表卡 1133；日历表卡 1158；停机窗卡 1197；冲突卡 1230；甘特卡 1254 | el-card, el-form, el-form-item, el-select, el-option, el-checkbox, el-button, el-alert, el-descriptions, el-descriptions-item, el-table, el-table-column, el-tag, el-empty, el-input, el-date-picker, el-switch, el-radio-group, el-radio-button | 无（未用 DataTable/PageState/StatusTag） |

## 2. 信息架构

### 2.1 导航层级与入口分布

| 项 | 现状（文件:行） |
|---|---|
| 布局 | `src/layouts/AppLayout.vue:34-63`：左侧 `el-aside` + 顶部 `el-header` + `el-main` 内 `RouterView`（60） |
| 侧栏宽度 | 固定 `width="220px"`（`AppLayout.vue:35`），无折叠按钮 |
| 菜单数据 | `menuModules` 生成逻辑：`router/modules/index.ts:31-33`（过滤 `menu !== false` 后按 `order` 升序） |
| 菜单渲染 | `AppLayout.vue:37-46`：有 `menuItems` 的模块渲染 `el-sub-menu`（39-44），否则渲染单个 `el-menu-item`（45） |
| 默认展开 | 仅 `['master-data']`（`AppLayout.vue:37`） |
| 面包屑 | `layouts/components/AppBreadcrumb.vue:7-13` 由 `route.matched` 的 `meta.title` 生成；`hidden` 路由被过滤（9） |

菜单顺序与实际入口（`order` 字段位置）：

| 顺序 | 模块 | menuPath | order 位置 | 菜单项数 |
|---|---|---|---|---|
| 1 | 总览 | `/` | `dashboard.ts:7` | 1 |
| 2 | 基础数据 | `/master-data/materials` | `master-data.ts:13` | 4 个子项（`master-data.ts:15-20`） |
| 3 | 工艺路线 | `/process/routings` | `process.ts:9` | 1 |
| 4 | 订单管理 | `/orders` | `orders.ts:7` | 1 |
| 5 | 排产策略 | `/strategies` | `strategies.ts:7` | 1 |
| 6 | MRP 齐套 | `/mrp/analysis` | `mrp.ts:10` | 1 |
| 7 | 计划版本 | `/plans/publish` | `plans.ts:10` | 1 |
| 8 | 排产求解 | `/solve-tasks` | `solve.ts:7` | 1 |
| 9 | 甘特图 | `/gantt` | `gantt.ts:13` | 1 |
| 10 | 订单甘特 | `/gantt-orders` | `gantt-order.ts:13` | 1 |

合计可点击菜单入口 **13 个**（9 个一级 + 4 个「基础数据」子项）。编辑器 `/process/routings/:id` 不出现在菜单（`process.ts:26-28` 无 `menuItems`），只能从列表进入。

### 2.2 页面间跳转关系

| 跳转 | 文件:行 | 说明 |
|---|---|---|
| 工艺路线列表 → 编辑器 | `RoutingListView.vue:77`、`RoutingListView.vue:82` | 行点击 / 新建后进入编辑器 |
| 编辑器 → 列表 | `RoutingEditorView.vue:459` | `router.push({ name: 'process-routings' })`（page-header 返回 482-500） |
| 订单甘特 → 资源甘特 | `OrderGanttView.vue:776` | `router.push({ path: '/gantt', query: { source, zoom } })` |
| 404 → 总览 | `NotFoundView.vue:10` | `router.push('/')` |
| 订单甘特读取 query | `OrderGanttView.vue:754-762` | dataset / zoom / source / heat |
| 资源甘特读取 query | `ResourceGanttView.vue:232-240` | 与上同键，回跳只传 source+zoom（`OrderGanttView.vue:776`） |
| 计划版本读取 query | `PlanPublishView.vue:257` | `route.query.planId` |

全仓 `router.push` 仅 6 处（`NotFoundView:10`、`OrderGantt:776`、`RoutingEditor:459/463`、`RoutingList:77/82`），`RouterLink/router-link` 为 0 处。页面之间没有其他直接跳转。

### 2.3 「需要来回跳」与「缺直达入口」

| 类型 | 具体位置（文件:行） | 现象 |
|---|---|---|
| 需要来回跳 | `RoutingListView.vue:77,82` ↔ `RoutingEditorView.vue:459` | 编辑路线必须离开列表再从头返回，编辑器无「保存并返回列表」 |
| 需要来回跳 | `OrderGanttView.vue:776` | 订单甘特 → 资源甘特是单向；资源甘特工具栏（`ResourceGanttView.vue:258`）无返回订单甘特入口 |
| 缺直达入口 | `DashboardView.vue:26` | 总览两张卡片均无业务页面链接，只能靠侧栏 |
| 缺直达入口 | `OrdersView.vue:205` | 订单行无「查看甘特」入口，需手动切菜单到 `/gantt-orders` |
| 缺直达入口 | `SolveTaskView.vue:191` | 求解完成后无「查看计划版本 / 查看甘特」入口（`PlanPublishView.vue:268` 也未反向链接求解） |
| 缺直达入口 | `PlanPublishView.vue:257` | 读取 `?planId=`，但全仓无任何 push 带 `planId`，只能手工拼 URL |
| 缺直达入口 | `MrpAnalysisView.vue:107` | MRP 结果中的订单号不可点击到订单详情（`MrpAnalysisView.vue:251` 仅文本列） |
| 死页面 | `PlaceholderView.vue:1-19` | 未在任何路由/模块注册，属孤儿文件 |

## 3. 一致性

### 3.1 全局样式与变量

| 项 | 结论（文件:行） |
|---|---|
| 全局样式文件 | `styles/main.css` 共 27 行；仅 4 段规则：`*` 盒模型 1-3、`body` 13-17、`.page-title` 19-23、`.page-card` 25-27 |
| 自定义 CSS 变量 | **0 个**：`main.css` 无 `:root`、无 `--*` 定义 |
| Element 变量使用 | 仅 24 处零散引用，如 `RoutingDagCanvas.vue:233,253,274`、`RoutingEditorView.vue:877,913`、`PlanPublishView.vue:553,594`、`MrpAnalysisView.vue:387` |
| 结论 | 页面间距/色值主要靠行内 `style` 与各页 `<style scoped>`，没有主题层 |

### 3.2 硬编码颜色

全 `src` 硬编码色值（含 `#hex`、`rgb/rgba`，正则 `#(?:[0-9a-fA-F]{3,8})\b|rgba?\(`）共 **201 处**：`views` 90、`components` 100、`layouts` 9、`styles` 2。列出 ≥3 处的文件（其余约 30 处分散在弹窗/抽屉/画布样式中）：

| 文件 | 处数 | 代表位置:色值 |
|---|---|---|
| `components/gantt/GanttTimeline.vue` | 67 | 模板 SVG 与 `<style>` 段均有硬编码，属单文件最高 |
| `views/gantt/ResourceGanttView.vue` | 26 | `:593 #606266`、`:614 #409eff`、`:655 #f56c6c`、`:667 rgba(144,147,153,0.3)` |
| `views/gantt/OrderGanttView.vue` | 25 | `:1316 #606266`、`:1337 #409eff`、`:1392 #f56c6c`、`:1422 #909399` |
| `components/graph/RoutingDagCanvas.vue` | 18 | 节点/边状态色均写死在样式段，无主题变量 |
| `views/master-data/calendars/ShiftTimeline.vue` | 15 | `:73 #409eff`、`:126 #ebeef5`、`:139 #909399`、`:185 #f2f6fc` |
| `layouts/AppLayout.vue` | 9 | `:72 #1f2d3d`、`:78 #ffffff`、`:92 #c0c4cc`、`:97 #324157`、`:105 #e4e7ed` |
| `views/master-data/boms/BomTreePanel.vue` | 3 | `:143 #303133`、`:147 #606266`、`:152 #909399` |
| `views/mrp/MrpAnalysisView.vue` | 3 | `:352 #909399`、`:369 #606266`、`:382 #67c23a` |
| `views/orders/OrderDetailDrawer.vue` | 3 | `:105 #303133`、`:118 #f5f7fa`、`:119 #606266` |
| `styles/main.css` | 2 | `:15 #303133`、`:16 #f5f7fa` |

Element Plus 主色 `#409eff/#67c23a/#e6a23c/#f56c6c/#909399` 在两份甘特页与 `ShiftTimeline.vue:73` 被重复硬编码，等同把主题色复制进业务文件。

### 3.3 px 魔法数字

全 `src` 共 **452 处 `Npx`**。高频值：

| 值 | 次数 | 代表位置 |
|---|---|---|
| 12px | 86 | `OrderGanttView.vue:1315`（font-size）、`:1306`（margin-right）；`SolveTaskView.vue:279` |
| 8px | 43 | `OrderGanttView.vue:1296,1301`；`SolveTaskView.vue:274,285`；`DataTable.vue:88` |
| 16px | 23 | `main.css:20`；`OrderGanttView.vue:1290,1302`；`DataTable.vue:94` |
| 20px | 7 | `main.css:21`（标题字号）；`MaterialDetailDrawer.vue:101`、`OrderDetailDrawer.vue:102`、`BomDetailDrawer.vue:95`（`margin: 20px 0 8px` 三处完全一致） |
| 220px | 7 | `AppLayout.vue:35`（侧栏固定宽）；`CalendarListView.vue:146`；`OrderGanttView.vue:1057` |
| 其他高频 | 1px=34、2px=33、4px=31、6px=23、3px=23、18px=20 | 多为甘特图例/边框尺寸 |

### 3.4 表格/表单/空态/加载/错误态封装矩阵

| 封装 | 覆盖页面（文件:行） | 未用而手写的页面 |
|---|---|---|
| DataTable（表格+分页+空态+错误态） | Orders 263；Material 154；Bom 197；Resource 219；Calendar 167；RoutingList 127；StrategyList 279；PlanPublish 297/426/509（10 处） | OrderGantt 945/1163/1201/1232；ResourceGantt 408/453/510；Mrp 207/218/275；BomForm 278；BomDetail 67；CalendarForm 485/536；StrategyEditor 439/483；StrategyList 437 |
| PageState（loading/empty/error 三态） | Mrp 342；BomTreePanel 86 | 其余页面各自用 `el-empty`/`el-alert`/button loading |
| StatusTag | 13 个文件（Orders、OrderDetail、PlanPublish、Solve、StrategyList、RoutingList、RoutingEditor、BomDetail、CalendarList、ResourceList、BomList、MaterialDetail、MaterialList） | 甘特两页手工 `<el-tag :type=...>`：`OrderGanttView.vue:1167,1205,1235`、`ResourceGanttView.vue:417,457,513` |
| 表单 | 6 个 Dialog 均重复 `el-form ref + :rules`：`MaterialFormDialog.vue:193`、`ResourceFormDialog.vue:292`、`CalendarFormDialog.vue:415`、`BomFormDialog.vue:222`、`OrderFormDialog.vue:160`、`RoutingVersionDialog.vue:132` | 无统一 Form 容器 |
| 加载 | DataTable 内置 `v-loading`（`DataTable.vue:60`）；PageState skeleton（28）；页面级仅 6 处 `v-loading`（ResourceGantt 537、OrderGantt 1259、Mrp 154、StrategyList 385、RoutingEditor 535、BomTreePanel 92） | SolveTask 详情 215、PlanPublish 详情 370/417/451/478、三个 Detail Drawer（Material 41/Order 36/Bom 42）无 v-loading |
| 错误 | DataTable 内置 error（`DataTable.vue:58`）；页面级错误 `el-alert` 5 处（ResourceGantt 325、OrderGantt 856、SolveTask 213、Dashboard 35、PlanPublish 367） | SolveTask 取消失败仅注释依赖拦截器（`SolveTaskView.vue:177-178`） |

## 4. 重复与耦合

| # | 重复点 | 文件:行 |
|---|---|---|
| 1 | 甘特图例 CSS 几乎整段复制 | `ResourceGanttView.vue:606-625` vs `OrderGanttView.vue:1329-1348`（`.legend__dot.tag-*`，色值相同） |
| 2 | 日历/状态/冲突辅助函数逐行重复 | `ResourceGanttView.vue:138` vs `OrderGanttView.vue:645`（calendarStateTag）；`150` vs `657`（calendarWindowKindLabel）；`160` vs `667`（formatWindowTime）；`182` vs `685`（conflictTagType） |
| 3 | 「只读指标」el-descriptions 卡片重复 | `ResourceGanttView.vue:339` vs `OrderGanttView.vue:870`（两页均为指标卡，含 8 个 `el-descriptions-item`） |
| 4 | 资源日历表 / 停机窗口表结构重复 | `ResourceGanttView.vue:402,449` vs `OrderGanttView.vue:1158,1197`（两组卡片内表格均含日历表与停机窗表） |
| 5 | 列表页「标题 + 筛选卡片 + DataTable」骨架重复 | `MaterialListView.vue:115,117,154`、`ResourceListView.vue:163,173,219`、`CalendarListView.vue:129,139,167`、`RoutingListView.vue:90,92,127`、`OrdersView.vue:205,207,263` |
| 6 | Dialog 底部「取消/保存 + submitting」重复 | `MaterialFormDialog.vue:270`、`ResourceFormDialog.vue:485`、`CalendarFormDialog.vue:612`、`BomFormDialog.vue:343`、`OrderFormDialog.vue:204`、`RoutingVersionDialog.vue:162`（均为 `:loading="submitting"` 保存按钮） |
| 7 | Detail Drawer 的 section 间距重复 | `MaterialDetailDrawer.vue:101`、`OrderDetailDrawer.vue:102`、`BomDetailDrawer.vue:95`（`margin: 20px 0 8px` 完全一致） |
| 8 | 页面级错误 el-alert 结构重复 | `ResourceGanttView.vue:325`、`OrderGanttView.vue:856`、`SolveTaskView.vue:213`、`DashboardView.vue:35`、`PlanPublishView.vue:367` |
| 9 | 手工状态标签与枚举映射重复 | `OrderGanttView.vue:1167,1205,1235`、`ResourceGanttView.vue:417,457,513`，而其他 13 页已用 `StatusTag` |
| 10 | 甘特「数据档/缩放/数据源/图层」工具栏重复 | `ResourceGanttView.vue:259` vs `OrderGanttView.vue:789`（两页工具栏起始，均含数据档/缩放/数据源/图层选择器） |

可抽取公共组件候选：`GanttLegend`、`GanttMetricsCard`、`GanttCalendarTables`、`gantt/formatters.ts`、`PageHeader`（标题+卡片）、`FilterCard`、`FormDialogFooter`、`DetailDrawerSection`、`DataTable` 扩展（甘特/MRP 直接用 raw table 的页面可复用同一空态/错误态）。

## 5. 交互缺口

| 缺口 | 位置（文件:行） | 说明 |
|---|---|---|
| 缺 loading | `SolveTaskView.vue:215` | 详情卡片无 `v-loading`/骨架，仅提交按钮有 `:loading`（208） |
| 缺 loading | `PlanPublishView.vue:370,417,451,478` | 详情/发布/回滚卡片无 `v-loading`，仅「加载详情」按钮 `:loading`（349） |
| 缺 loading | `MaterialDetailDrawer.vue:41`、`OrderDetailDrawer.vue:36`、`BomDetailDrawer.vue:42` | Drawer 无 loading 态 |
| 缺 loading | `StrategyEditorDrawer.vue:350` | 打开/保存期间无遮罩，仅保存按钮 `:loading`（531） |
| 缺空态 | `SolveTaskView.vue:215` | `v-if="detail"` 之外没有空态提示；未提交时页面只剩表单 |
| 缺空态（以隐藏代替） | `PlanPublishView.vue:370,417,451,478` | 未选版本时整块卡片 `v-if` 消失，无「请先加载版本」占位 |
| 缺错误提示 | `SolveTaskView.vue:177-178` | 取消失败 `catch {}`，仅注释「拦截器已统一提示」，无页面内联错误 |
| 缺错误提示 | `StrategyEditorDrawer.vue:306` | 保存失败仅 `ElMessage.error`，无就地错误区 |
| 缺二次确认 | `SolveTaskView.vue:166,256` | 「取消求解」直接执行，无 `ElMessageBox.confirm`（同文件未 import） |
| 缺二次确认 | `PlanPublishView.vue:172,467`、`214,496` | 发布/回滚直接执行；全文件无 `ElMessageBox` 引用 |
| 缺二次确认 | `OrderGanttView.vue:1098` | 提交 MOVE/LOCK/FREEZE 直接执行（有 dryRun，但无确认） |
| 缺二次确认 | `StrategyListView.vue:96-97` | 克隆策略后直接落库，无确认 |
| 缺二次确认 | `RoutingEditorView.vue:494`、`RoutingVersionDialog.vue:162` | 保存、创建草稿版本直接执行 |
| 缺键盘可达 | `components/graph/RoutingDagCanvas.vue:186-214,179` | 自定义 `div.routing-node` 无 `tabindex/role/aria`，只能鼠标 `@node-click`；可键盘元素仅「适应画布」按钮（224） |
| 缺键盘可达 | `components/graph/RoutingDagCanvas.vue:151-155` | 拖拽改坐标仅 `@node-drag-stop`，无键盘替代 |
| 缺分页 | `OrderGanttView.vue:643,1140,1232` | 结果被 `slice(0,20)`/`slice(0,50)` 截断，无分页或「查看全部」 |
| 缺分页 | `ResourceGanttView.vue:136,510` | 同上 |
| 缺分页 | `MrpAnalysisView.vue:207,218,275` | 三张 raw table 无分页（结果可能全量渲染） |
| 缺分页 | `PlanPublishView.vue:428,511` | 快照/留痕表显式 `:show-pagination="false"` |
| 缺分页 | `StrategyListView.vue:437`、`StrategyEditorDrawer.vue:439,483`、`CalendarFormDialog.vue:485,536`、`BomFormDialog.vue:278`、`BomDetailDrawer.vue:67` | 弹窗/抽屉内表格无分页（数据量小时可接受，但与 DataTable 默认分页不一致） |

## 6. 可访问性与响应式

| 项 | 现状（文件:行） |
|---|---|
| 焦点样式 | 全仓仅 `components/gantt/GanttTimeline.vue:1244-1246` 定义 `.gantt-bar:focus-visible`；`main.css` 无全局 focus 规则；DAG 节点无 focus 样式 |
| aria/role | 仅 `GanttTimeline.vue:783-786`：`tabindex="0"`(783)、`role="button"`(784)、`aria-label`(785)、`aria-pressed`(786)；`views` 目录 0 处 aria/role |
| alt | `src` 内无 `<img>`，alt 无适用对象；不存在图标图片 |
| label | 6 个 Dialog 均用 `el-form-item` 自动关联（如 `MaterialFormDialog.vue:193`）；仅 `PlanPublishView.vue:455,459,482,492` 使用原生 `<label>` 且无 `for`/input `id` |
| 对比度-低 | `#909399` 文本在白底对比度约 **3.06:1**，低于 WCAG AA 4.5:1：`OrderGanttView.vue:1422,1430`、`ResourceGanttView.vue:699,707`、`MrpAnalysisView.vue:352`、`ShiftTimeline.vue:139,198` |
| 对比度-低 | `#909399` 在 `#f5f7fa`（`main.css:16`）上约 **2.77:1**：`ShiftTimeline.vue:139` 等 |
| 对比度-通过 | `#606266` 在白底约 **6.1:1**：`OrderGanttView.vue:1316`、`ResourceGanttView.vue:593`；`#c0c4cc` 在 `#1f2d3d` 约 **7.98:1**（`AppLayout.vue:92` vs 72） |
| 窄屏-无断点 | 全 `src` `@media` 数量 **0** |
| 窄屏-固定侧栏 | `AppLayout.vue:35` 固定 `width="220px"`，无折叠/抽屉模式，窄屏主区被挤压 |
| 窄屏-编辑页固定右栏 | `RoutingEditorView.vue:844-861`：`.editor-body` 水平 flex，`.editor-side` 固定 `400px` + `flex-shrink:0`，`.editor-main` `min-width:0`（852）；窄屏无换行断点 |
| 窄屏-弹窗固定宽 | `BomFormDialog.vue:218` 960px、`CalendarFormDialog.vue:386` 960px、`ResourceFormDialog.vue:288` 860px、`StrategyListView.vue:384` 760px、`MaterialFormDialog.vue:189` 720px、`StrategyListView.vue:351` 600px、`OrderFormDialog.vue:157` 560px、`RoutingVersionDialog.vue:119` 520px；均无 `max-width` 自适应 |
| 窄屏-画布固定节点宽 | `RoutingDagCanvas.vue:265-266` 节点 `min-width:210px; max-width:240px`，`RoutingEditorView.vue:856` 右栏 400px |
| 已有横向滚动 | `GanttTimeline.vue:1007-1014`（`.gantt-scroll{overflow:auto}` + `.gantt-canvas{min-width:100%}`），甘特本体不撑破页面 |

## 7. 优先级建议（建议，不是结论）

排序口径：影响演示效果 × 改动成本；P0 为「演示可见 + 低成本」，P2 为「结构治理 + 高成本」。

| 优先级 | 建议项 | 涉及文件（关键行） | 理由（基于上文事实） |
|---|---|---|---|
| P0 | 给取消/发布/回滚加二次确认 | `SolveTaskView.vue:166,256`；`PlanPublishView.vue:172,467,214,496` | 当前直接执行，误触即变更状态 |
| P0 | 给详情/详情抽屉补 loading 与空态 | `SolveTaskView.vue:215`；`PlanPublishView.vue:370,417,451,478,507`；`MaterialDetailDrawer.vue:41`；`OrderDetailDrawer.vue:36`；`BomDetailDrawer.vue:42` | 目前只有按钮 loading，详情异步期间无反馈 |
| P0 | 补跨页直达链接 | `OrdersView.vue:205`；`SolveTaskView.vue:191`；`DashboardView.vue:26`；`OrderGanttView.vue:776`/`ResourceGanttView.vue:258` | 全仓仅 6 处 `router.push`，业务链路无法直达 |
| P0 | 处理孤儿页 `PlaceholderView.vue:1-19` | 删除或注册到路由（`router/modules/index.ts:18-29`） | 无路由且无引用，属死代码 |
| P1 | 抽取甘特公共组件（图例/指标/日历表/格式化函数） | `ResourceGanttView.vue:138-182,339,402,449,606-625`；`OrderGanttView.vue:645-685,870,1158,1197,1329-1348` | 5 组逐行重复，改一处需改两处 |
| P1 | 给甘特/MRP 长表补分页或「查看全部」 | `OrderGanttView.vue:643,1140,1232`；`ResourceGanttView.vue:136,510`；`MrpAnalysisView.vue:207,218,275` | 目前静默截断 20/50 条 |
| P1 | 建立 CSS 变量/间距 token 并替换硬编码 | `styles/main.css:1-27`（当前 0 变量）；`OrderGanttView.vue:1316-1430`；`ResourceGanttView.vue:606-625`；`ShiftTimeline.vue:126-198` | 全 src 201 处色值、452 处 px |
| P1 | 窄屏适配：侧栏折叠、弹窗宽度自适应、编辑器堆叠 | `AppLayout.vue:35`；`RoutingEditorView.vue:844-861`；8 个固定 width 弹窗（7 个文件，见 6 节） | `@media` 为 0，窄屏会溢出 |
| P2 | 统一用 `StatusTag`/`DataTable` 替代甘特/MRP 手写表与手写标签 | `OrderGanttView.vue:945,1163,1201,1232`；`ResourceGanttView.vue:408,453,510`；`MrpAnalysisView.vue:207,218,275` | 空态/错误态/分页口径不一致 |
| P2 | 补 DAG 节点键盘可达与 `aria` | `RoutingDagCanvas.vue:186-214,151-155`；对照 `GanttTimeline.vue:783-786` | 自定义节点仅鼠标可操作 |
| P2 | 修复低对比度次要文本与未绑定 label | `OrderGanttView.vue:1422,1430`；`ResourceGanttView.vue:699,707`；`ShiftTimeline.vue:139,198`；`PlanPublishView.vue:455,459,482,492` | `#909399` 约 3.06:1；4 个 label 无 `for` |
