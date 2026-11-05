# APS 前端 UI 对标与改造计划（只读对标）

> 对标对象：`D:\tools\mfg-agent`。本文只写文档，不执行构建、不改参考项目、不改 `aps-frontend/src/**`。
> 路径简写：`REF` = `D:\tools\mfg-agent\backend\src\mfg_agents\v2\demo\ui`；`APS` = `aps-frontend\src`。
> 所有行号均来自本次 Read/Grep；未读取到文件的位置不写结论。

## 1. 设计语言摘要

### 1.1 技术栈与骨架事实

| 项目 | 事实 | 证据 |
|---|---|---|
| 参考项目根 | 无 `package.json`、无 `node_modules`，不是 npm 工程 | Shell：`Test-Path D:\tools\mfg-agent\package.json` = False |
| 参考前端形态 | 原生 HTML + CSS + 原生 JS，零依赖、零构建；`index.html` 直接以 `<script defer>` 加载 | `REF/index.html:96-126` |
| 参考 UI 位置 | `backend/src/mfg_agents/v2/demo/ui/` 与 `frontend/public/` 两份镜像 | 本次 SHA256 校验：`index.html`、`app.css` 两份一致；`app.js` 两份不一致 |
| 参考入口结构 | `aside.sidebar` + `header.topbar` + `main.content#screen-root` | `REF/index.html:35-88` |
| 参考样式分层 | `app.css` 基础层 → `aging.css` 覆盖层（适龄化/对比度/字号/密度） | `REF/index.html:27-29`；`REF/aging.css:1-11` |
| 我们的技术栈 | Vue 3.5 + TypeScript + Vite 8 + Element Plus 2.14 + Pinia 4 | `aps-frontend/package.json:16-27` |
| 我们的脚本 | `type-check`、`lint`、`build`（`vue-tsc --noEmit && vite build`） | `aps-frontend/package.json:7-11` |
| 我们的骨架 | `el-container` → `el-aside 220px` → `el-header` → `el-main` → `RouterView` | `APS/layouts/AppLayout.vue:34-63` |

### 1.2 设计令牌表（参考项目 `:root`，含 `aging.css` 覆盖）

| 变量名 | 值 | 用途 | 参考文件:行号 |
|---|---|---|---|
| `--primary` | `#3B8AE8` | 品牌主色；`aging.css` 运行态覆盖为 `#6BAEF7` | `REF/app.css:8`；覆盖 `REF/aging.css:64` |
| `--primary-bright` | `#2B7AE0` | 按钮/渐变亮端；覆盖为 `#7FB9F9` | `REF/app.css:9`；`REF/aging.css:65` |
| `--bg` | `#0A1322` | 页面深色底 | `REF/app.css:19` |
| `--card` | `#16273F` | 卡片/弹窗表面 | `REF/app.css:20` |
| `--border` | `#263E62` | 常规描边/分隔；覆盖为 `#3A587F` | `REF/app.css:21`；`REF/aging.css:70` |
| `--border-strong` | `#35507A` | 强调描边 | `REF/app.css:22` |
| `--text` | `#EEF4FE` | 主文字 | `REF/app.css:23` |
| `--text-2` | `#BCCBDF` | 次级文字 | `REF/app.css:24` |
| `--text-3` | `#93A8C6` | 说明/占位 | `REF/app.css:25` |
| `--ok` / `--ok-bg` | `#3FCB79` / `rgba(63,203,121,.13)` | 成功、正常 | `REF/app.css:28-29` |
| `--warn` / `--warn-bg` | `#E8B93E` / `rgba(232,185,62,.13)` | 预警 | `REF/app.css:30-31` |
| `--danger` / `--danger-bg` | `#F4696B` / `rgba(244,105,107,.13)` | 错误、危险操作；覆盖为 `#FD9496` | `REF/app.css:32-33`；`REF/aging.css:66` |
| `--radius` / `--radius-sm` | `12px` / `8px` | 卡片与控件圆角 | `REF/app.css:55-56` |
| `--shadow-sm` / `--shadow-md` / `--shadow-pop` | 三档阴影 | 卡片、悬浮、弹层 | `REF/app.css:57-59` |
| `--sidebar-w` | `220px` | 侧栏宽 | `REF/app.css:62` |
| `--topbar-h` | `58px` | 顶栏高 | `REF/app.css:63` |
| `--font-scale` | `1` | 字号系数，所有字号按 `calc(Npx * var(--font-scale))` 书写 | `REF/app.css:65-69` |
| `--focus-ring` | `#6BAEF7`（深色）/`#1557AD`（亮色） | 键盘焦点环 | `REF/aging.css:20`、`REF/aging.css:49` |
| `--d-card-pad` 等密度变量 | `20/18/13/10/8/14px` 为标准档 | 卡片、行、单元格间距 | `REF/aging.css:40-45` |
| 密度档位覆写 | compact / loose 两档 | 通过 `html[data-density]` 改写密度变量 | `REF/aging.css:315-322` |

### 1.3 布局范式

| 维度 | 参考项目 | 我们现状 | 可核对差距 |
|---|---|---|---|
| 外层布局 | `.app { display:flex; height:100vh; overflow:hidden }` | `el-container` 仅 `min-height:100vh` | 参考锁定视口高度；我们允许页面继续撑高（`REF/app.css:106`；`APS/layouts/AppLayout.vue:67-69`） |
| 侧栏宽度 | `var(--sidebar-w)` = 220px | `el-aside width="220px"` | 数值相同；我们为硬编码，不是令牌（`REF/app.css:62,130`；`APS/layouts/AppLayout.vue:35`） |
| 顶栏高度 | `var(--topbar-h)` = 58px | `el-header` 默认 `--el-header-height:60px` | 58 vs 60（`REF/app.css:63,250`；`element-plus/theme-chalk/el-header.css:1`） |
| 内容区内边距 | `padding: 22px 28px 40px` | `el-main` 默认 `--el-main-padding:20px` | 四边 20 vs 上 22/左右 28/下 40（`REF/app.css:113`；`element-plus/theme-chalk/el-main.css:1`） |
| 内容宽度 | `.content-inner { width:100% }`，自适应铺满 | `RouterView` 直接挂在 `el-main`，无宽度容器 | 我们无显式内容容器（`REF/app.css:122`；`APS/layouts/AppLayout.vue:59-60`） |
| 卡片 | 12px 圆角、1px 边框、顶部 3px 品牌渐变线 | `el-card shadow="never"` + `page-card` 仅下边距 16px | 参考有顶部渐变线（`REF/app.css:379-407`）；我们无（`APS/styles/main.css:25-27`） |
| 页头 | `.page-head` 下边距 18px；标题 20px 粗体 + 左侧 5×21 渐变竖条；`page-desc` 12.5px | `.page-title` 20px/600、下边距 16px，无竖条、无副标题 | 0 个竖条、0 个 page-desc（`REF/app.css:356-377`；`APS/styles/main.css:19-23`） |
| 表格 | `.std-table` 12.5px，表头默认右对齐（首列左对齐），单元格 `8px 10px`，数字等宽 | Element Plus 表格 `border stripe` | 字号/内边距由组件库决定，未做令牌化（`REF/app.css:3002-3014`；`APS/components/common/DataTable.vue:60`） |
| 卡片内边距 | `.card-pad` 16px 18px；`.card-head` 14px 20px | 未设置 `el-card` body padding | 我们沿用 EP 默认，无 16/18 档（`REF/app.css:407-417`） |

### 1.4 交互范式

| 范式 | 参考实现 | 我们现状 | 结论 |
|---|---|---|---|
| 列表 → 详情 | 行内“详情”触发居中弹窗；弹窗骨架动态插入 body，`role=dialog`、`aria-modal`、Esc 关闭、点击遮罩关闭 | 订单/物料/BOM 使用 `el-drawer`：订单 560px、物料 520px、BOM 640px | 同为“不跳页”详情；容器不同（参考 `REF/screens.js:1088-1121`；我们用例 `APS/views/orders/OrderDetailDrawer.vue:36-40`、`.../materials/MaterialDetailDrawer.vue:41-44`、`.../boms/BomDetailDrawer.vue:42-45`） |
| 编辑入口 | 参考以弹窗/面板内编辑为主，不为单个表单新开整页 | 物料/BOM/资源/日历/订单用 `el-dialog`；工艺路线使用独立路由编辑页 | 工艺路线属于领域编辑器，不强行改；表单弹窗可统一页头与按钮区（参考 `REF/screens.js:1088-1121`；路由 `APS/router/modules/process.ts:20-26`） |
| 危险操作确认 | 原生 `window.confirm("确认把该催交单升级…")`，确认后 toast 反馈 | 订单、物料、BOM、资源、日历均用 `ElMessageBox.confirm` | 语义已对齐；不搬 `window.confirm` 代码（`REF/screens.js:1295`；我们 `APS/views/OrdersView.vue:141,159,181`） |
| 异步进度 | 进度条宽度按耗时更新；弹窗先显示“正在加载详情…”占位 | 求解页 `el-progress`；列表 `v-loading`；弹窗无统一 loading 骨架 | 参考的“先骨架后填充”可移植（`REF/screens.js:61-72,116,1101`；`APS/views/SolveTaskView.vue:229`） |
| 空态/错误态 | `.empty` 42px 上下留白 + 26px 图标；行内 `empty-inline`；toast 三类 `ok/err/info` | `PageState` 三态：`el-skeleton` / `el-empty` / `el-result`；`DataTable` 出错时整表替换 | 三态已具备；缺少“行内空态”和统一 toast 文案层级（`REF/app.css:614-621,2318`；`REF/screens.js:222,1043`；`APS/components/common/PageState.vue:28-34`、`DataTable.vue:58-64`） |
| 导航 | 按钮列表按 `group` 输出分组标题，选中项左侧 3px 渐变条 | `el-menu` + `el-sub-menu`，仅“基础数据”有二级，选中背景 `#324157` | 参考分组标签在 4 组以上；我们只有 1 个二级组（`REF/app.js:47-76,1523-1538`；`APS/layouts/AppLayout.vue:37-46,95-98`） |
| 顶栏 | 标题 + 副标题 + 全局搜索 + 后端状态 + 刷新 + 用户 chip | 面包屑 + traceId + 复制按钮 | 0 个全局搜索、0 个后端状态、0 个用户入口（`REF/index.html:61-85`；`APS/layouts/AppLayout.vue:51-57`） |

### 1.5 主题 / 字号 / 密度切换实现（参考项目）

| 能力 | 实现 | 参考文件:行号 |
|---|---|---|
| 明暗主题 | `localStorage` 键 `mfg-theme`；默认 `light`，显式存过 `dark` 才深色；写 `html[data-theme]` | `REF/theme-switcher.js:15,25,32-38` |
| 主题色 | 键 `mfg-accent`；`blue/cyan/violet/orange` 四选一；非 blue 写 `html[data-accent]` | `REF/theme-switcher.js:16-18,26-29,35-36` |
| 切换入口 | 顶栏 `.topbar-right` 注入两个图标按钮；登录页另有浮动控件 | `REF/theme-switcher.js:101-130` |
| 字号 | `--font-scale:1`；`html[data-font="medium"]=1.2`、`large=1.35`；全部 `font-size` 用 `calc(Npx * var(--font-scale))` | `REF/app.css:65-69`；`REF/aging.css:198-199` |
| 字号落盘 | `mfg-font-scale`；统一写入 `html[data-font]` | `REF/aging-mode.js:15,74` |
| 密度 | `compact/standard/loose` 三档；`html[data-density]` 改写 `--d-*` 变量并落到卡片/表格/列表 | `REF/aging.css:315-330`；`REF/aging-mode.js:16,75` |
| 首绘防闪 | `index.html` 内联脚本在 `<head>` 预读四个 localStorage 键并写 `<html>` 属性 | `REF/index.html:11-25` |
| 我们现状 | 全仓仅 1 处 `theme` 关键字（Vue Flow 的 `theme-default.css` 导入）；无 `data-theme/data-font/data-density`、无偏好存储 | `APS/components/graph/RoutingDagCanvas.vue:19`；`APS/layouts/AppLayout.vue:34-63` 无相关代码 |

### 1.6 组件尺寸与反馈基线

| 组件 | 关键值 | 参考文件:行号 | 我们现状 |
|---|---|---|---|
| 普通按钮 | 高 32px、左右 15px、圆角 8px、字号 13px | `REF/app.css:485-499` | EP 按钮默认高 32px（`element-plus/theme-chalk/el-button.css:1`），无令牌映射 |
| 小按钮 | 高 27px、左右 11px、字号 12px；适龄化覆盖为 32px | `REF/app.css:524`；`REF/aging.css:355` | 使用 EP 默认，无 `btn-sm` 对应类 |
| 图标按钮 | 32×32、圆角 9px | `REF/app.css:324-332` | 顶栏只有文字链“复制”（`APS/layouts/AppLayout.vue:56`） |
| 导航项 | 左右 11px、上下 8.5px、字号 13.5px、下边距 3px | `REF/app.css:173-186` | `el-menu-item` 默认高度 56px（`element-plus/theme-chalk/el-menu.css:1`） |
| 卡片头 | 高 14px 20px、标题 14px、右侧附加 12px | `REF/app.css:409-421` | `el-card` 默认 header padding，未定制 |
| 空态 | 上下 42px、左右 20px，图标 26px | `REF/app.css:614-621` | `PageState` 外层仅 16px（`APS/components/common/PageState.vue:38-41`） |
| 加载旋转 | 14×14，大号 22×22 | `REF/app.css:624-633` | `el-skeleton` 动画替代，无尺寸定制 |
| Toast | 顶部居中，成功/错误/信息三边框色 | `REF/app.css:1837-1866` | `ElMessage` 默认顶部居中（`element-plus/theme-chalk/el-message.css:1`），无统一间距 |
| 弹窗 | 宽 880px、最大 86vh、头 13px 16px | `REF/app.css:3141-3150` | 详情抽屉 3 种宽度：560/520/640px |
| 进度条 | 高 6px、圆角 999px | `REF/app.css:602-606` | 求解页 16px（`APS/views/SolveTaskView.vue:229`） |
| 表格 | 字号 12.5px、表头 11.5px、单元格 8px 10px | `REF/app.css:3002-3010` | EP 表格走默认字号（`element-plus/theme-chalk/el-table.css:1`），无令牌映射（`APS/components/common/DataTable.vue:60`） |
| 徽标 | 字号 11.5px、圆角 6px、6 种语义色 | `REF/app.css:460-480` | `StatusTag` 已有语义枚举（`APS/components/common/StatusTag.vue:7`），未统一字号 |

### 1.7 字体与排版基线

| 层级 | 参考值 | 参考文件:行号 | 我们现状 |
|---|---|---|---|
| 正文 | `calc(14px * var(--font-scale))` | `REF/app.css:79` | `main.css` 未设 body 字号（`APS/styles/main.css:13-17`） |
| 页面标题 | `calc(20px * var(--font-scale))`，字重 800 | `REF/app.css:357-366` | 20px、字重 600（`APS/styles/main.css:19-23`） |
| 卡片标题 | `calc(14px * var(--font-scale))`，字重 700 | `REF/app.css:419` | EP 卡片头默认，未改 |
| 导航项 | `calc(13.5px * var(--font-scale))` | `REF/app.css:181` | EP 菜单走默认字号（`element-plus/theme-chalk/el-menu.css:1`） |
| 表格 | `calc(12.5px * var(--font-scale))`，表头 11.5px | `REF/app.css:3002-3005` | EP 表格走默认字号（`element-plus/theme-chalk/el-table.css:1`） |
| 说明文字 | `calc(12.5px * var(--font-scale))`，色 `--text-2` | `REF/app.css:377` | 无 `page-desc` 层级 |

### 1.8 响应式与无障碍基线

| 项 | 参考做法 | 参考文件:行号 | 是否移植 |
|---|---|---|---|
| 焦点环 | `inset 0 0 0 2px var(--focus-ring)`，避免被 `overflow:hidden` 裁切 | `REF/aging.css:132-148` | 是；Element Plus 默认焦点样式需补 |
| 触控目标 | 粗指针下最小 44×44 | `REF/aging.css:338-342` | 是；桌面后台可只做 min-height |
| 窄屏侧栏 | ≤960px 收为 64px 图标轨，标签由 JS 浮出 | `REF/aging.css:359-375` | 否；我们的后台保留 220px 固定侧栏 |
| 减少动态 | `prefers-reduced-motion: reduce` 关闭提示动画 | `REF/aging.css:570-571` | 是；可加在显示设置/吐司层 |

## 2. 页面级对照表

| # | 参考项目模式（文件:行号） | 我们对应页面（文件:行号） | 现状差距（可核对） | 改造动作 | 改动文件 |
|---|---|---|---|---|---|
| 1 | 工作台：KPI 卡 + 今日简报 + 待办两个异步区块 | `APS/views/DashboardView.vue:25-54` | 我们 1 个标题 + 2 个 `el-card` + 1 个 `el-descriptions`（4 行），0 个 KPI、0 个异步区块；参考有 2 个异步加载入口（`REF/screens.js:548-549,553-610`） | 增加 4 个 KPI 摘要卡和“待办/简报”两栏；保留现有技术栈说明 | `DashboardView.vue` |
| 2 | 页头：20px 标题 + 5×21 竖条 + 12.5px 副标题（`REF/app.css:356-377`） | `APS/views/master-data/materials/MaterialListView.vue:115` | 我们仅 `h2.page-title` 一行；0 个副标题、0 个竖条；标题下边距 16px vs 参考 18px（`APS/styles/main.css:19-23`） | 抽 `PageHeader`，标题 + 副标题 + 竖条；所有列表页换用 | 见 B2/B3 |
| 3 | 物料类列表：紧凑筛选行 + 数据表 + 行内详情；表格字号 12.5px、单元格 8×10（`REF/app.css:3002-3011`） | `APS/views/master-data/materials/MaterialListView.vue:115-180` | 我们 3 个筛选项 + 2 个按钮（118-150）、1 个 `DataTable`、7 列（170-180）、详情抽屉 520px；筛选卡与表格卡为 2 张 | 筛选与表格合并到 1 个卡片；统一 `PageHeader`；抽屉宽 520→640 | `MaterialListView.vue`、`MaterialDetailDrawer.vue` |
| 4 | 列表 + 详情弹窗：880px、最大高 86vh、标题/标签/副标题三段（`REF/app.css:3135-3167`） | `APS/views/master-data/boms/BomListView.vue:169-224` | 我们 2 个筛选项（173-191）、左 11/右 13 双栏（195-208）、5 列 + 250px 操作列（213-224）、明细抽屉 640px；0 个居中弹窗 | 保留双栏（BOM 领域需要），统一页头/筛选卡，详情抽屉 640 保持一致 | `BomListView.vue`、`BomTreePanel.vue`、`BomDetailDrawer.vue` |
| 5 | 资源/产能类：指标卡（24px 数字 + 12.5px 标签）+ 分组卡片（`REF/app.css:757-813`） | `APS/views/master-data/resources/ResourceListView.vue:163-217` | 我们 1 条 info `el-alert` + 4 个筛选项（175-215）+ 1 个 `DataTable`；0 个指标卡 | 顶部加 3 个资源计数卡（总数/启用/停用），筛选折为 1 行 | `ResourceListView.vue` |
| 6 | 状态徽标：6 色 `badge`，11.5px，圆角 6px（`REF/app.css:460-480`） | `APS/views/master-data/calendars/CalendarListView.vue:131-181` | 我们 1 条 info `el-alert` + 2 个筛选项 + `DataTable`；状态由 `StatusTag` 呈现；无“版本/只读”顶部分类卡 | 增加“当前生效/历史版本”2 个统计块；保持 `StatusTag` | `CalendarListView.vue` |
| 7 | 订单履约：管线阶段条 + 进度条 + 风险计数（`REF/screens.js:859`；`REF/app.css:602-606`） | `APS/views/OrdersView.vue:205-272`；`APS/views/orders/OrderDetailDrawer.vue:36-96` | 我们 5 个筛选项（209-255）+ `DataTable` + 560px 抽屉；0 个管线、0 个 KPI、0 个进度条 | 顶部加 4 段状态管线；抽屉 560→640，统一详情分组 | `OrdersView.vue`、`OrderDetailDrawer.vue`、`OrderFormDialog.vue` |
| 8 | 详情/编辑不分页：弹窗内完成查看与提交（`REF/screens.js:1088-1121`） | `APS/views/process/routings/RoutingListView.vue:90-138`；编辑页 `RoutingEditorView.vue` | 我们列表 3 个筛选项（94-123）+ 表；编辑走独立路由（`APS/router/modules/process.ts:20-26`），1 次跳页；参考 0 次跳页 | 列表加 `PageHeader`；编辑页保留独立路由（DAG 画布不适合弹窗） | `RoutingListView.vue`、`RoutingEditorView.vue` |
| 9 | 审批/发布：状态徽标 + 进度条 + 会签进度（`REF/screens.js:3472-3587`；`REF/app.css:602-606`） | `APS/views/plans/PlanPublishView.vue:266-335` | 我们 1 条 info alert（270-277）、2 个按钮（294-295）、8 列表格（309-330）、1 个“查看详情”链接（331-335）；0 个流程条 | 增加发布状态流程条（草稿→审批→发布→回滚）；表格保留 | `PlanPublishView.vue` |
| 10 | 长任务：弹窗 loading 占位 + 进度条 + 阶段文案（`REF/screens.js:1101-1102`；`REF/app.css:602-606`） | `APS/views/SolveTaskView.vue:191-248` | 我们 4 个表单项（194-206）、1 个 16px 进度条（229）、9 个描述项（238-248）；参考进度条 6px；参考有 loading 骨架 | 进度条 16→6px；增加“提交中/求解中/完成”三段阶段条；错误保留 `el-alert` | `SolveTaskView.vue` |
| 11 | 页头与内容铺满：标题/副标题 + `content-inner 100%`（`REF/index.html:61-64`；`REF/app.css:122`） | `APS/views/gantt/ResourceGanttView.vue:256-258`；`OrderGanttView.vue:786` | 我们的页面标题含内部编号“（M2-FE-01 · 只读增强）”“（M2-FE-02 · 只读增强）”；参考标题不含内部编号；资源页 4 张 `el-card`、3 个 `el-empty` | 标题去掉内部编号，编号移入副标题；统一页头 | `ResourceGanttView.vue`、`OrderGanttView.vue` |
| 12 | 缺料/预警：3 类行内预警条（SPC/缺料/履约），文案含数量与实体（`REF/screens.js:600-605`） | `APS/views/mrp/MrpAnalysisView.vue:105-142,205-338` | 我们 5 个控制项（场景 ID、订单号、复选框、开关、按钮，111-136）、1 条错误 `el-alert`、2 个 tab、2 张表（按订单 6 个数据列 + 展开列；按物料 7 个固定列 + 最多 3 个建议列，207-336）；0 个顶部预警条 | 顶部加“总缺料/齐套/阻塞”3 个计数条；保留双 tab | `MrpAnalysisView.vue` |
| 13 | 设置面板：320px 浮层、分组小标题、分段按钮（`REF/aging.css:440-515`） | `APS/views/strategies/StrategyListView.vue:275-320` | 我们 3 个工具按钮 + 1 个隐藏 file input（291-302）、5+ 列表格；0 个设置面板；编辑用抽屉 | 工具区改为“新建/导入/刷新”3 个固定按钮，导入说明迁移到副标题 | `StrategyListView.vue`、`StrategyEditorDrawer.vue` |
| 14 | 详情容器：880px 居中、最大 86vh、头部 13px 16px（`REF/app.css:3142-3150`） | `APS/views/orders/OrderDetailDrawer.vue:36-40`；`MaterialDetailDrawer.vue:41-44`；`BomDetailDrawer.vue:42-45` | 我们 3 种宽度：560/520/640px；0 个统一值；参考为单一弹窗宽 880px | 统一详情抽屉宽 640px，头部 padding 对齐，底部按钮固定 | 3 个 Drawer |
| 15 | 空/错态：`.empty` 42px 留白、26px 图标；行内 `empty-inline`（`REF/app.css:614-621,2318`） | `APS/components/common/PageState.vue:28-34`；`DataTable.vue:58-64` | 我们 3 分支（skeleton/empty/result），错误时整表消失；0 个行内空态 | `PageState` 增加 `inline` 变体；`DataTable` 保留表头并显示行内空态 | `PageState.vue`、`DataTable.vue` |
| 16 | 导航：13 个基础项、4 个显式分组、组标题由可见项生成（`REF/app.js:47-76,1523-1538`） | `APS/router/modules/index.ts:18-29`；`APS/layouts/AppLayout.vue:37-46` | 我们 10 个模块、1 个二级组（基础数据）；0 个侧栏分组标签；选中背景 `#324157`（95-98） | 侧栏加 4 个分组标签（总览/主数据/排产/分析）；菜单项 10 个不变 | `AppLayout.vue`、`router/modules/index.ts` |
| 17 | 顶栏：标题+副标题+全局搜索+后端状态+刷新+用户（`REF/index.html:61-85`） | `APS/layouts/AppLayout.vue:51-57` | 我们 1 个面包屑 + 1 个 traceId + 1 个复制按钮；0 个全局搜索、0 个后端状态、0 个用户信息 | 顶栏左侧加当前页标题/副标题；右侧加搜索框与用户占位；保留 breadcrumb/trace | `AppLayout.vue`、新增 `TopbarTitle.vue` |
| 18 | 显示偏好：2 主题 × 4 主题色 × 3 字号 × 3 密度（`REF/theme-switcher.js:17-18,25`；`REF/aging.css:198-199,315-322`） | `APS/layouts/AppLayout.vue:34-63`；全仓无偏好代码 | 我们 0 个主题按钮、0 个字号按钮、0 个密度按钮、0 个 localStorage 键 | 新增显示设置抽屉：明暗 2 档、字号 3 档、密度 3 档；写 `html[data-*]` | `AppLayout.vue`、新增 `DisplaySettingsDrawer.vue`、`useDisplayPreferences.ts` |

## 3. 可移植性判定

| 对象 | 判定 | 依据与说明 |
|---|---|---|
| 设计令牌 | 可移植 | 参考把颜色/圆角/阴影/布局尺寸集中在 `:root`（`REF/app.css:6-70`），并以 `aging.css` 覆盖层维护对比度与档位（`REF/aging.css:16-71,198-199,315-322`）。我们可映射为 CSS 变量 + Element Plus `--el-*` 变量，不改组件库 |
| 布局骨架 | 可移植 | 固定视口 + 220px 侧栏 + 58px 顶栏 + 内容区 22/28/40（`REF/app.css:106,130,250,113`）。我们对应 `el-aside 220px`、`el-header 60px`、`el-main 20px`（`APS/layouts/AppLayout.vue:35,51,59`；`el-header.css:1`；`el-main.css:1`） |
| 导航分组 | 可移植 | 数据驱动 `NAV_ITEMS` + 组标题渲染（`REF/app.js:47-76,1523-1538`）。我们已有 `menuModules` 数据源（`APS/router/modules/index.ts:18-31`），只需在 `AppLayout` 加组标题映射 |
| 页头范式 | 可移植 | 标题竖条 + 副标题（`REF/app.css:356-377`），纯 CSS 可复刻；不依赖参考脚本 |
| 空态/错误态 | 可移植 | `.empty`、`empty-inline`、toast 三态（`REF/app.css:614-621,2318`；`REF/screens.js:222,1043`）。我们有 `PageState` 三态（`APS/components/common/PageState.vue:28-34`），可加 `inline` 变体 |
| 异步进度 | 可移植 | 进度条 6px、按耗时更新、loading 占位（`REF/app.css:602-606`；`REF/screens.js:61-72,1101`）。我们的 `el-progress` 已存在（`APS/views/SolveTaskView.vue:229`），改高度与文案即可 |
| 详情容器范式 | 可移植（容器不照搬） | 参考居中弹窗 880px（`REF/app.css:3142-3143`）；我们使用 `el-drawer`（`APS/views/orders/OrderDetailDrawer.vue:36-40`）。只移植“单容器、头部三段、固定底部动作”，不照搬 DOM 结构 |
| 危险操作确认 | 已具备，不搬代码 | 参考 `window.confirm`（`REF/screens.js:1295`）；我们已用 `ElMessageBox.confirm`（`APS/views/OrdersView.vue:141,159,181`）。保留 Element Plus 交互 |
| 主题/字号/密度偏好 | 可移植（交互层） | 参考四键 localStorage + `<html data-*>`（`REF/theme-switcher.js:15-16,25-38`；`REF/aging-mode.js:15-16,74-76`）。我们可写入同样属性并只服务 CSS，不引入参考脚本 |
| 参考 JS 实现 | 不可移植 | 参考是 `document.createElement` + `innerHTML` 模板 + 全局 `window.Screens`（`REF/screens.js:1090-1104`）；与 Vue 响应式/类型体系冲突，禁止复制 |
| 参考 CSS 选择器 | 不可整段复制 | `.std-modal/.std-table/.sk-*` 等绑定参考 DOM（`REF/app.css:3002-3017,3135-3167`）。只能提炼值，不能直接粘贴选择器 |
| 框架约束 | 禁止变更 | 继续使用 Vue 3 + Element Plus + Pinia + Vite（`aps-frontend/package.json:16-40`）；不引入 React/原生模板引擎，不替换 Element Plus |

## 4. 分批实施计划（B1–B5）

> 本节只是计划，不执行。每批文件范围互不重叠；验收统一要求 `npm run type-check`、`npm run lint`、`npm run build` 全绿，并附 DOM/交互证据。

### 4.0 验收命令与证据口径

| 项 | 命令/口径 | 证据落点 |
|---|---|---|
| 类型检查 | `npm run type-check`（`vue-tsc --noEmit`） | 退出码 0；`aps-frontend/package.json:10` |
| 代码规范 | `npm run lint`（`eslint . --max-warnings 0`） | 退出码 0；`aps-frontend/package.json:11` |
| 构建 | `npm run build`（`vue-tsc --noEmit && vite build`） | 退出码 0；`aps-frontend/package.json:8` |
| DOM 证据 | 选择器计数/可见文案，例如 `.nav-group-label` 数量、`.page-header__desc` 文案、`el-drawer` 计算宽度 | 文档或截图附在批次说明中 |
| 交互证据 | 点击步骤 + 预期结果，例如“点击新建按钮 → 出现 `el-dialog` 标题‘新建物料’” | 同上 |

### 4.1 批次依赖与文件互斥检查

| 批次 | 前置 | 文件范围互斥对象 | 冲突检查 |
|---|---|---|---|
| B1 | 无 | B2/B3/B4/B5 均不改 `tokens.css`、`AppLayout.vue`、`TopbarTitle.vue`、显示偏好文件 | 通过：B1 独占布局与令牌 |
| B2 | B1（`--d-cell-pad` 等变量） | B3/B4/B5 不改 `DataTable.vue`、`PageState.vue`、`PageHeader.vue` | 通过：共享组件只在 B2 改 |
| B3 | B1/B2 | B4/B5 不改主数据四页及其弹窗/抽屉 | 通过：主数据文件只在 B3 改 |
| B4 | B1/B2 | B3/B5 不改订单、工艺路线、求解页 | 通过：订单/路线/求解文件只在 B4 改 |
| B5 | B1/B2 | B3/B4 不改计划、甘特、MRP、策略页 | 通过：分析/发布文件只在 B5 改 |

### B1：令牌层 + 布局骨架 + 顶栏 + 侧栏分组 + 显示偏好

| 项 | 内容 |
|---|---|
| 目标 | 建立 `tokens.css`；把 220px 侧栏、58px 顶栏、22/28/40 内容区、12px 圆角、三档阴影、字号系数落到变量；顶栏加当前页标题/副标题；侧栏加 4 个组标题；显示设置抽屉支持 2 主题/3 字号/3 密度 |
| 文件范围 | `APS/styles/tokens.css`（新增）、`APS/styles/main.css`、`APS/main.ts`、`APS/layouts/AppLayout.vue`、`APS/layouts/components/TopbarTitle.vue`（新增）、`APS/components/settings/DisplaySettingsDrawer.vue`（新增）、`APS/composables/useDisplayPreferences.ts`（新增） |
| 不碰 | 所有 `src/views/**`、`DataTable.vue`、`PageState.vue`（留给 B2/B3–B5） |
| 验收 | ① `html` 初始 `data-theme="light" data-font="standard" data-density="standard"`；② 顶栏可见文案“系统总览”与副标题“APS 调度与排产总览”；③ 侧栏 `.nav-group-label` 计数 = 4；④ 点击 `.display-settings-trigger` 打开抽屉，抽屉内 2 个主题按钮、3 个字号按钮、3 个密度按钮；⑤ 切到“large”后 `getComputedStyle(document.documentElement).getPropertyValue('--font-scale')` = `1.2` |

### B2：共享展示组件（页头/表格/状态）

| 项 | 内容 |
|---|---|
| 目标 | 新增 `PageHeader`（标题+副标题+竖条）；`DataTable` 统一工具栏左右布局、错误态保留表头、行内空态；`PageState` 增加 `inline` 变体；表格密度走 `--d-cell-pad` |
| 文件范围 | `APS/components/common/PageHeader.vue`（新增）、`APS/components/common/DataTable.vue`、`APS/components/common/PageState.vue` |
| 不碰 | `src/views/**`；`AppLayout.vue`；`main.css` |
| 验收 | ① 物料页仍渲染 `.data-table`，空数据时 `.el-empty__description` 文案 = “暂无物料数据”；② 模拟接口 500 时 `.page-state` 内 `.el-result` 可见、按钮“重试”可点击，且 `.el-table` 表头仍存在；③ `.toolbar` 左对齐“新建物料”、右对齐分页，工具栏与分页间距 12/16px；④ `type-check/lint/build` 通过 |

### B3：主数据四页（物料 / BOM / 资源 / 日历）

| 项 | 内容 |
|---|---|
| 目标 | 四页统一 `PageHeader`；筛选与表格合并为 1 张卡；资源加 3 个计数卡、日历加 2 个版本统计块；详情抽屉统一 640px；危险操作沿用 `ElMessageBox` |
| 文件范围 | `APS/views/master-data/materials/MaterialListView.vue`、`MaterialDetailDrawer.vue`、`MaterialFormDialog.vue`；`.../boms/BomListView.vue`、`BomTreePanel.vue`、`BomDetailDrawer.vue`、`BomFormDialog.vue`；`.../resources/ResourceListView.vue`、`ResourceFormDialog.vue`；`.../calendars/CalendarListView.vue`、`CalendarFormDialog.vue` |
| 不碰 | 订单、工艺路线、计划、甘特、MRP、策略、共享组件 |
| 验收 | ① 每页 `.page-header__desc` 有 1 行副标题；② 物料页筛选项 3 个、资源页 4 个、日历页 2 个、BOM 页 2 个；③ 物料/BOM 两个详情 `el-drawer` 计算宽度均为 640px；④ 物料/资源/日历删除均出现 `ElMessageBox` 标题“删除确认”；⑤ 四页 `npm run build` 通过 |

### B4：订单 / 工艺路线列表 / 排产求解

| 项 | 内容 |
|---|---|
| 目标 | 订单页加 4 段状态管线与 4 个 KPI；订单详情抽屉 560→640；工艺列表加页头，编辑页保留独立路由；求解页进度条 16→6px、加三阶段条 |
| 文件范围 | `APS/views/OrdersView.vue`、`APS/views/orders/OrderDetailDrawer.vue`、`OrderFormDialog.vue`、`APS/views/process/routings/RoutingListView.vue`、`RoutingEditorView.vue`、`RoutingVersionDialog.vue`、`APS/views/SolveTaskView.vue` |
| 不碰 | 主数据四页、计划/甘特/MRP/策略、共享组件 |
| 验收 | ① 订单页 `.order-pipeline` 存在且包含 4 个阶段节点；② 订单详情 `el-drawer` 宽度 640px；③ 求解页 `.el-progress-bar__outer` 高度 = 6px，且状态文案按 `提交中/求解中/已完成/失败` 四选一；④ 工艺列表 `.page-title` 文案 = “工艺路线”，编辑页 URL 仍为 `/process/routings/:id`；⑤ `type-check/lint/build` 通过 |

### B5：计划发布 / 甘特 / MRP / 排产策略

| 项 | 内容 |
|---|---|
| 目标 | 计划页加发布流程条；甘特页标题去掉内部编号；MRP 顶部加 3 个缺料计数条；策略工具区固定 3 个按钮、隐藏 input 保留 |
| 文件范围 | `APS/views/plans/PlanPublishView.vue`、`APS/views/gantt/ResourceGanttView.vue`、`APS/views/gantt/OrderGanttView.vue`、`APS/views/mrp/MrpAnalysisView.vue`、`APS/views/strategies/StrategyListView.vue`、`APS/views/strategies/StrategyEditorDrawer.vue` |
| 不碰 | 前四批文件；`src/components/**` |
| 验收 | ① 计划页 `.plan-release-steps` 含 4 步，当前步高亮；② 资源/订单甘特 `.page-title` 文案分别为“资源甘特图”“订单甘特图”，副标题保留“只读增强”；③ MRP `.mrp-summary` 含 3 个计数：缺料物料/齐套订单/阻塞开关状态；④ 策略页工具栏固定 3 个可见按钮：新建策略/导入 JSON/刷新，`input[type=file]` 仍为 `display:none`；⑤ `type-check/lint/build` 通过 |

## 5. 不做清单（明确不照搬）

| 不做项 | 参考位置 | 理由 |
|---|---|---|
| 深色驾驶舱作为默认主题 | `REF/app.css:6-70`、`REF/aging.css:48-58` | 参考默认深色 + 亮色覆盖层；我们现有 Element Plus 是亮色业务后台（`APS/styles/main.css:13-17`）。直接切换会造成全部 EP 组件对比度重做；仅在用户显式选择时提供深色 |
| 对话式/协同问数 UI | `REF/app.js:58-63`；`REF/screens.js:2145-2159` | 面向“问数/智能体”的聊天结构，与 APS 的表格/表单/甘特主流程不同；不把聊天面板嵌入排产页 |
| HITL 审批演示屏与角色切换 | `REF/app.js:68-71`；`REF/screens.js:288` | 参考是演示型多岗位切换；我们尚无对应角色权限模型，照搬会出现“有入口无权限”的空页 |
| 880px 居中详情弹窗 | `REF/app.css:3142-3143` | 我们的订单详情有 2 组 `el-descriptions`（`APS/views/orders/OrderDetailDrawer.vue:43,63`），`el-drawer` 更适配现有信息密度；只移植“单容器、头部三段、固定底部” |
| 原生 `window.confirm` | `REF/screens.js:1295` | 我们已有 `ElMessageBox.confirm`（`APS/views/OrdersView.vue:141,159,181`），保留可取消/可定制文案与 EP 主题 |
| 参考的 `document.createElement + innerHTML` 渲染 | `REF/screens.js:1090-1104` | 与 Vue 模板/响应式冲突，且无类型检查；禁止复制参考 JS |
| 参考内联 SVG 图标体系 | `REF/app.js:135-170` | 我们由 Element Plus 图标/组件统一提供；逐个替换会造成 20+ 种图标双轨 |
| 甘特 / DAG / 画布组件替换 | 本次 Grep `gantt` 在 `REF` 无匹配；参考中的“场景 DAG”是数据编排文案（`REF/app.js:116`），不是画布；我们现有 `@infectoone/vue-ganttastic`、`@vue-flow/core`（`aps-frontend/package.json:17,19`） | 这是领域组件，参考项目不能提供可复用实现；继续用现有库，只统一页头/卡片/空态外框 |
| 演示数据降级横幅 | `REF/screens.js:1757`、`REF/screens.js:1043` | 参考用于演示后端离线；我们的页面直接走后端接口，错误态由 `DataTable`/`PageState` 承担，不显示“演示数据”字样 |
| 全站统一 12.5px 表格字号 | `REF/app.css:3002` | 我们的 Element Plus 表格需同时承载可编辑单元/选择器/展开行；直接压到 12.5px 会影响表单控件可读性，改为只调 `--d-cell-pad` 密度 |
| localStorage 偏好的服务端同步 | `REF/theme-switcher.js:15-16` | 参考只做本机偏好；我们当前无用户体系接口，先按本机存储，避免伪造服务端字段 |

---

**结论**：参考项目可对标的是“令牌 + 骨架 + 页头 + 空错态 + 异步进度 + 显示偏好”六类范式；不可对标的是其原生 JS 渲染、演示型角色/对话屏、居中弹窗容器与甘特/DAG 领域能力。后续按 B1→B5 执行，每批只动本批列出的文件。
