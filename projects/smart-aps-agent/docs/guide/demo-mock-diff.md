# 前端 Mock 与 OpenAPI / 真后端一致性核对

> 目的：演示切真后端时不穿帮。
> 范围：`aps-frontend/src/mocks/handlers.ts`、`mocks/data.ts`、`mocks/gantt-data.ts` 与 `docs/api/openapi/*.yaml`、18085 实测响应。
> 方式：只读核对，未改任何代码。实测时间 2026-11-05 03:00。

## 结论

Mock 的**分页外壳、大部分核心字段和状态枚举**与契约一致（`items/page/size/total/totalPages`、订单 `status/freezeStatus`、任务 `MOVE/LOCK/FREEZE/...`、计划 `DRAFT/PUBLISHED`）。真正会让演示穿帮的差异集中在 **登录口令、计划版本、客户字段、用户数量、甘特日历字段** 五处。

## 不一致项（按演示影响排序）

| # | 端点/页面 | Mock 现状 | 契约/真后端 | 演示影响 | 建议（不改代码，仅准备） |
|---|---|---|---|---|---|
| M01 | `POST /auth/login` | 固定口令 `admin123`，仅校验用户名存在 | 18085 dev 初始口令来自 `APS_ADMIN_INIT_PASSWORD`（环境变量注入，仓库不记录明文）；连错 5 次锁 15 分钟 | **P0**：Mock 口令在真后端直接 401；试错会锁号 | 演示前统一用真后端口令；Mock 模式只在离线联调时用 |
| M02 | `GET /plans`、计划发布/对比 | Mock 首次进入自动为**任意 sandbox** 造 3 个版本（1 PUBLISHED + 2 DRAFT，各 6 条快照任务） | 真后端主计划 `...0401` 返回 `total=0`；只有 QA2 场景有 4 个 PUBLISHED 版本 | **P0**：Mock 里发布/对比看起来正常，切真后端主计划空白 | 演示前按 G01 预置主计划版本；不要用 Mock 预演后直接切真后端 |
| M03 | `GET /orders` | 订单对象含 `customerId`、`routingId`，列表可按客户 UUID 过滤并显示客户名 | 18085 实测订单 JSON **不含** `customerId`、`routingId`；契约 `OrderView` 有这两个可选字段 | **P1**：真后端“客户”列空白；编辑订单时表单回填为空，保存会把两个字段写成 null | 数据侧补客户表/外键（G09）；演示不要点“编辑”已确认订单；客户列用备注口头补充 |
| M04 | `GET /users`、`GET /roles` | 3 个用户（admin/planner01/viewer01）、4 个角色 | 真后端 1 个用户（admin）、5 个角色 | **P2**：切换后用户页只剩 1 行，角色页多 1 行 | 演示前补 3 个演示账号（G07）；或跳过用户页 |
| M05 | `GET /gantt/resource` | 资源行 `calendarId=null` 是刻意保留的“Mock 缺口”，分析面板会提示未绑定 | 18085 实测返回 `calendarId` 与 `calendarBound`，4/6 行已绑定 | **P2**：Mock 里日历绑定率 0/6，真后端 4/6；讲解词要跟着变 | 用 `?source=api`；台词按“4 台主资源已绑日历，2 个辅助资源不绑”说 |
| M06 | `GET /sandboxes` | 列表项返回完整 `Sandbox`，含 `snapshot` 字段 | 契约 `SandboxSummary` 列表不返回 `snapshot`（详情才有） | **P3**：页面不读该字段，无可见影响 | 无需处理；切真后端不要期待列表里有快照 |
| M07 | `GET /audit-logs` | 日志 id 为字符串；数据量随 Mock 动作增长 | 真后端 id 为数字；当前仅 14 条 | **P2**：列表能显示，但条数和 id 类型不同；真后端筛选结果很少 | 演示前补审计（G08）；按 `action` 筛选，不按 id |
| M08 | `GET /audit-logs` 分页 | Mock 统一 `total/totalPages` | 真后端一致 | 无 | — |
| M09 | `GET /orders` 状态分布 | Mock 场景内订单以 DRAFT/CONFIRMED 混合，无 QA2/SE04 噪音 | 真后端 17 条，13 CONFIRMED，含 8 张非演示单 | **P1**：Mock 干净、真库嘈杂，甘特行数对不上台词 | 数据侧清理（G04）；演示台词只说 8 张 `DEMO-*` |
| M10 | 甘特数据窗口 | Mock 固定 2026-11-01–11-12，与真后端页面窗口一致 | 真后端同窗口，但任务可能排到窗口外 | **P3**：窗口外任务不显示，属预期 | 只讲窗口内；不承诺“全部任务都在画面上” |
| M11 | `GET /materials` | Mock 物料含中文名与状态字段 | 真后端 `DEMO-FG-100` 的 `name` 是英文 `Demo Finished Good`，列表不返回 `status/categoryCode` | **P2**：客户看到英文物料名；状态列为空 | 数据侧本地化名称（并入 G10）；或演示时用编码讲解 |
| M12 | `GET /plans` 发布字段 | Mock `publishedBy` 为 null、`reason` 固定 | 真后端同样 `publishedBy=null`；QA2 4 个版本均 PUBLISHED | 无 | 不把“发布人”当演示点 |

## 契约自身的两处需统一（不是 Mock 问题）

1. `docs/api/openapi/gantt.yaml` 的资源甘特描述写“每行返回 `calendarId`（可空）与 `calendarBound`”，但前端类型 `types/gantt.ts` 的注释写“C4 不返回 `calendarId`”；18085 实际返回了。演示以**实测**为准：返回且 4/6 绑定。
2. `docs/api/openapi/orders.yaml` 的 `OrderView` 定义了 `customerId/routingId`，18085 实际不返回；`OrderUpdateRequest` 又允许更新这两个字段。切真后端编辑订单会把它们清空，属于接口实现缺口，不是 Mock 造假。

## 切真后端前的 10 分钟核对

1. 退出 Mock 模式（`VITE_ENABLE_MOCK=false`），重新登录，确认 `/me` 返回 admin 权限。
2. 打开订单页：应有 17 行；`DEMO-ORD-001..008` 的“客户”列允许为空（已知）。
3. 打开 `plans?sandboxId=...0401`：预期 0 行；如果预置完成，应 ≥2 行。
4. 打开 `users`：预期 1 行；`roles`：预期 5 行。
5. 打开 `/gantt?source=api`：数据源标签必须是 API；资源行 6 行、4 行绑定日历。
6. 打开 `/system/audit`：`action=ORDER_FROZEN` 应查到 7 条。
7. 不要在真后端使用 `admin123`；不要用 Mock 的 `mock-access-token`。
