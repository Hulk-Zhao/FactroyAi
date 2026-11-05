# M3 全量并发切分预案（D0 已冻结 · 2026-11-04）

> 状态：**D0 已执行**。C30–C41 形状与 V8–V13 号段已冻结，正文见 `docs/api/contract-freeze.md` **§25**；执行结论、卡号映射与遗留项见本文 **§7**。文中 §1 的原始提案保留不改，冲突处以 §7 为准。
>
> 原状态说明（保留）：预案，不是派工单；D0 评审后才生效。
>
> 本轮不写业务代码、不打包、不碰运行实例；D0 只追加契约（§25）与本文 §7，未改写 C1–C29。
>
> 前置：M2 收口后进行（门槛见第 4 节）；M2 当前只剩最后一次制品级终验（另一条流执行中）。

---

## 0. 依据与边界

### 0.1 M3 范围来源清单

| # | 文件 + 行号 | 原文摘录（压缩） | 用途 |
|---|---|---|---|
| S1 | [m2-plan.md#L41](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L41) | 「12/14 条完整规则、策略 A/B 对比 → M3/v0.5」 | W2 策略补全与对比 |
| S2 | [m2-plan.md#L43](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L43) | 「重试编排、分布式求解 → M3」 | W3 重试编排（分布式不立卡） |
| S3 | [m2-plan.md#L44](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L44) | 「全量多策略自动择优 → M3」 | W2 多策略择优 |
| S4 | [m2-plan.md#L45](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L45) | 「采购建议优化、SMT 飞达/储能行业特例 → M3/v0.5」 | W6 采购建议（行业特例待确认） |
| S5 | [m2-plan.md#L46](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L46) | 「5000 任务、虚拟滚动、ATP 2 秒 → M3-QA-01/04」 | W4/W7 容量、W9 性能专项 |
| S6 | [m2-plan.md#L47](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L47) | 「MES 下发、邮件/短信/飞书通知 → M3」 | W5 下发与通知 |
| S7 | [m2-plan.md#L48](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L48) | 「RBAC 角色权限、全量审计 → M3-BE-01/03」 | W1 平台 |
| S8 | [m2-plan.md#L49](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L49) | 「全量字段模板、异步大文件 → M3」 | W9 Excel 扩展 |
| S9 | [m2-plan.md#L50](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L50) | 「生产加固、监控告警 → M3-QA-05」 | W9 部署加固 |
| S10 | [m2-plan.md#L281](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L281) | 「`agent_event/agent_decision` 属 M3，不占 M2」 | W10 条件流（见 §0.3） |
| S11 | [m2-plan.md#L445](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L445) | 「`constraint:` 别名至少保留到 M2 结束；M3 若清理，需产品确认并保留一个版本」 | W1 兼容清理卡 |
| S12 | [m2-plan.md#L513](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L513) | 「5000 留 M3-FE-04」 | W7 虚拟滚动 |
| S13 | [m2-plan.md#L519](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L519) | 「M2 只做权限占位…M3 接真实 RBAC」 | W1/W4 联调 |
| S14 | [m2-plan.md#L531](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L531) | 「前端登录页属 M3-FE-02」 | W8 登录页 |
| S15 | [contract-freeze.md#L1868-L1874](file:///d:/tools/smart-aps-agent/docs/api/contract-freeze.md#L1868-L1874) | §24.12：`SPLIT/MERGE` 枚举已冻结、M2 返回 50100 → M3；`PARTIAL` 降级模式 M2 不产出 → M3 | W4/W3 |
| S16 | [contract-freeze.md#L1876-L1895](file:///d:/tools/smart-aps-agent/docs/api/contract-freeze.md#L1876-L1895) | §24.13：非 M2 冻结面（OPS=1 / M1=3 / **M3=10**） | M3 端点池（见 §1.1 逐条） |
| S17 | [contract-freeze.md §24.8](file:///d:/tools/smart-aps-agent/docs/api/contract-freeze.md#L1717-L1726) | C26：`customers` 未交付、不在 M2；扩展留 M3 单开卡片 | W9 `customers` 导入 |
| S18 | [PRD#L1620](file:///d:/tools/smart-aps-agent/mfg-aps-platform-PRD.md#L1620) | 「M3（第 3 月）：Sandbox + 审计日志 + RBAC + 性能优化 + 文档 + 种子客户试用 + 缺陷修复」 | 主轴 |
| S19 | [PRD#L1624-L1637](file:///d:/tools/smart-aps-agent/mfg-aps-platform-PRD.md#L1624-L1637) | v0.5 清单：Agent 重排、mfg-agent-platform、换型矩阵、储能分选、ATP、OR-Tools 预研、ERP、Webhook、多租户 | **不属于 M3**（冲突见 §0.3） |
| S20 | [db/README.md#L8](file:///d:/tools/smart-aps-agent/docs/db/README.md#L8) | 「当前已执行到 V7（V7 由 M2-SE-05 引入）」 | M3 从 V8 起 |

**范围纪律**：S18 的七个词是 M3 主轴；S1–S17 是落到具体流/卡的依据；S19 的 v0.5 项**不因“M3 全量并发”被顺手并入**。

### 0.2 §24.13 的 M3 十条（逐条点出）

| # | 端点 | 直接归属 | 落地条件 |
|---|---|---|---|
| E1 | `POST /auth/login` | W1（V8 之后） | 需先定 JWT 与 API Key 并存顺序（C27 已冻结骨架） |
| E2 | `POST /agent/events` | W10（条件流） | 见 §0.3：PRD 归 v0.5 |
| E3 | `POST /agent/nl-to-dsl` | W10（条件流） | 同上 |
| E4 | `GET /agent/explain/{taskId}` | W10（条件流） | 同上；M2 已有 C19 结构化 justification |
| E5 | `POST /agent/whatif` | W10（条件流） | 同上 |
| E6 | `GET/POST /sandboxes` | W5 | V1 已有 `sandbox` 表，CRUD 可能零迁移（D0 审计后定） |
| E7 | `POST /sandboxes/{id}/apply` | W5 | 需冻结 WHAT_IF→BASELINE 语义（C32 提案） |
| E8 | `GET /sandboxes/{id}/compare` | W5 | 需冻结差异结构（C32 提案） |
| E9 | `POST /orders/atp` | W6（条件卡） | PRD 归 v0.5（S19），M3 是否承接待用户确认 |
| E10 | `GET /mrp/suggestions` | W6 | 采购建议优化（S4）；输出形状未冻结 |

### 0.3 已知范围冲突（必须先裁）

1. **Agent 四端点（E2–E5）**：契约 §24.13 标 `M3`，但 PRD §7.2 明确列在 **v0.5**（Agent 异常重排、NL→DSL、what-if）。→ M3 预案只留 **W10 条件流**，不排入主关键路径；开工须用户确认。
2. **ATP（E9）**：§24.13 标 `M3`，PRD §7.2 列在 **v0.5**（FR-OM-003）。→ W6 条件卡，同上。
3. **采购建议（E10）**：m2-plan 写 `M3/v0.5`（S4），PRD v0.5 未单列。→ 暂按 M3 承接，行业特例（SMT 飞达/储能）明确排除。
4. **OR-Tools / 引擎路由（FR-SE-002/003）**：PRD 列 **v0.5 预研**；M3 只消费 C28 已冻结的降级语义，不做 OR-Tools 接入。

---

## 1. D0 冻结清单（提案 · 待冻结）

### 1.1 契约条目：C30 起（提案，不宣布生效）

| 编号 | 能力 | 必须冻结的端点/字段/枚举/事件 | 归属流 | 未冻结后果 |
|---|---|---|---|---|
| **C30** | RBAC 权限模型 | `PermissionCode` 枚举（至少 `SCHEDULE_ADJUST/SCHEDULE_PUBLISH/MASTERDATA_WRITE/AUDIT_READ`）、角色-权限绑定、401/403 判定顺序、`40300` 语义扩展 | W1 | 各流各自写权限判断，403 口径分裂 |
| **C31** | 审计事件 | `AuditEventType` 枚举、`audit_log` 字段全集、写审计的切面（控制器/服务）、不可篡改约定、查询分页与筛选 | W1 | 审计要么漏记、要么每流一套格式 |
| **C32** | Sandbox 生命周期 | `SandboxCreateRequest/SandboxView`、`POST /sandboxes/{id}/apply`（WHAT_IF→BASELINE，锁定/覆盖规则）、`GET /sandboxes/{id}/compare` 差异结构、与 `plan_version` 的关系 | W5 | 场景语义与计划版本双写 |
| **C33** | 计划下发与通知 | `POST /plans/{id}/dispatch`（M3 是否真做 MES 待确认，先冻结 `outbox` 语义）、`NotificationChannel{EMAIL,SMS,FEISHU}`、失败重试与幂等 | W5 | 下发不可重试、重复通知 |
| **C34** | 采购建议 | `GET /mrp/suggestions` 入参（`sandboxId/orderNos/horizonDays`）、`MrpSuggestionView`（`materialCode/requiredQty/availableQty/shortageQty/suggestedOrderDate`）、排序、上限 | W6 | 前端先造一套字段 |
| **C35** | 多策略择优 | 多策略提交/批量求解的请求形状、`StrategyCompareView`（按 `hard/soft/makespan` 对比）、选优规则与并列处理 | W2 | A/B 对比口径不一致 |
| **C36** | `SPLIT/MERGE` 调整语义 | 拆分后任务 `parentTaskId`/序号重排、合并的前置校验（同资源/同订单/时间相邻）、审计字段 | W4 | 甘特删除/合并数据不可逆 |
| **C37** | `PARTIAL` 降级模式 | 部分排位的 `status` 规则（不得 `SOLVED`）、未排任务的返回形状、`degraded/engineFallbackDetail` 组合 | W3 | 部分解被误判为可用 |
| **C38** | `customers` 导入导出 | `ImportEntity` 增加 `customers`、模板列、外键/唯一约束、与 BOM/订单的引用关系 | W9 | 名单再次反复（M2 已冻结 9 类） |
| **C39** | 异步大文件导入 | `POST /{entity}/import` 的异步模式（`202 + jobId`）、`ImportJobView`、轮询端点、取消、文件上限 | W9 | 大文件把请求线程打满 |
| **C40** | Agent 端点（条件） | `AgentEvent/AgentDecision` DTO、事件幂等键、决策回写边界 | W10 | 条件流，未确认不开卡 |
| **C41** | ATP（条件） | `POST /orders/atp` 入参与 `AtpResult`（最早可交期/瓶颈资源/置信度） | W6（条件） | 同上 |

编号规则沿用 M2：**只增不改**，C1–C29 的编号与结论一律不动；与既有条目冲突时在旧条目顶部加一行「以 C30+ 为准」。

### 1.2 迁移号段：从 V8 起（提案 · 待冻结）

> 现状：V1 基线 + V2–V6（M2）+ V7（M2-SE-05 约束归因权重/扣分）。**V8 起属 M3**；旧表 V8–V9「测试部署流」预留由 S0 在 D0 重发（与 M2 重发 V2–V9 同一做法）。

| 版本 | 唯一写者 | 文件名 | 用途 | 风险/回滚 |
|---|---|---|---|---|
| V8 | W1 | `V8__rbac_permission.sql` | 角色-权限关联（若 V1 角色权限表不足）、`permission_code` 约束 | 权限表可整表重建；不改既有 `role` 语义 |
| V9 | W1 | `V9__audit_event.sql` | 审计事件扩展（事件类型、索引 `(tenant_id, occurred_at)`） | 只加表/索引；不删既有 `audit_log` 列 |
| V10 | W5 | `V10__sandbox_lifecycle.sql` | Sandbox 扩展（`parent_id/applied_at/applied_by`，视 V1 结构审计结果） | 先审计 V1 `sandbox`；若已够用则**不建此迁移** |
| V11 | W5 | `V11__notification_outbox.sql` | 通知/MES 下发 `outbox`（幂等键、状态、重试次数） | 只加表；不触碰 `plan_version` |
| V12 | W9 | `V12__import_job.sql` | 异步导入任务（`job_id/entity/status/file_key/统计`） | 只加表；同步导入路径不受影响 |
| V13（条件） | W10 | `V13__agent_event.sql` | `agent_event/agent_decision`（S10 已说明 M3 预留） | 条件流；未确认不发号 |

**硬规则**：D0 一次发号、每段唯一写者；已应用的迁移文件连注释都不许改（Flyway checksum，见 [db/README#L29-L32](file:///d:/tools/smart-aps-agent/docs/db/README.md#L29-L32)）。

### 1.3 主数据编码号段（提案 · 待用户确认）

仓库内**没有**主数据编码规范（PRD 未给编码规则；仅测试夹具用过 `MAT-/RES-/ORD-` 前缀，见 [m1-closed-loop.md#L73](file:///d:/tools/smart-aps-agent/docs/testing/m1-closed-loop.md#L73) 与 [m2-qa-01-perf.md#L37](file:///d:/tools/smart-aps-agent/docs/perf/m2-qa-01-perf.md#L37)）。因此：

- **不得**在 M3 凭空定义业务编码规则；下表仅为**测试夹具**建议，业务编码规范标「待用户确认」。
- 测试夹具统一 `M3-<流>-<序号>`（如 `M3-W9-001`），与 M2 的 `MAT-W9-001` 隔离，避免误清理。
- 若 `customers` 在 M3 落地，业务前缀提案 `CUS-`；`suppliers` 已有数据沿用既有 `SUP-`（需 D0 核对库内实际值）。

| 实体 | 业务前缀 | 号段 | 状态 |
|---|---|---|---|
| materials | `MAT-` | 现有夹具段已用，M3 新段 `M3-W9-001..` | 测试夹具提案 |
| customers | `CUS-` | `CUS-001..` | **待用户确认**（无编码规范） |
| suppliers | `SUP-` | 沿用现网 | **待确认**（需核对库内值） |
| 其他 | 沿用既有 | — | 不新增号段 |

### 1.4 公共依赖与公共契约

| 类别 | 新增项 | 唯一写者 | 说明 |
|---|---|---|---|
| DTO | `SandboxView/SandboxCreateRequest/SandboxCompareView/ApplyRequest`、`MrpSuggestionView`、`StrategyCompareView`、`ImportJobView`、`AuditLogView`、`AtpResult`（条件） | W1 定形状，各流填实现 | 统一放 `aps-common` 或 `solver-api`（按 M2 分层，D0 定） |
| 枚举 | `PermissionCode`、`AuditEventType`、`NotificationChannel`、`ImportJobStatus` | W1 | 入 [enums.md](file:///d:/tools/smart-aps-agent/docs/api/enums.md)，加值须同步 schema |
| 错误码 | `40300` 细分（`40301` 权限不足/`40302` 角色不可用，提案）、`40904` Sandbox 已应用（提案）、`42202` 下发目标未就绪（提案） | W1 | 4xxxx 段内，登记 `ErrorCode` |
| 事件 | `AuditEvent`、`NotificationRequested`、`ImportJobCompleted` | W1 | 进程内事件；不引入消息中间件（M3 无 Kafka/Rabbit 依赖证据） |
| 依赖 | 无新增三方库（通知走通道抽象 + outbox，不发真实短信/邮件）；若需钉钉/飞书 SDK → **待用户确认** | W1 | 避免“先引依赖后补契约” |

### 1.5 看板卡清单（提案 · 待冻结）

> 已存在引用的卡号（M3-BE-01/03、M3-FE-02/04、M3-QA-01/04/05）**沿用不改**；其余为新提案。

| 卡号 | 标题 | 归属流 | 依据 |
|---|---|---|---|
| M3-BE-01 | RBAC 角色权限与 `SCHEDULE_ADJUST` 接入 | W1 | S7/S13 |
| M3-BE-02 | JWT 登录与 API Key 并存（`POST /auth/login`） | W1 | E1/S14 |
| M3-BE-03 | 全量审计（事件类型 + 查询端点） | W1 | S7 |
| M3-BE-04 | `constraint:` 别名兼容清理（保留一版） | W1 | S11 |
| M3-SE-01 | 策略规则补至 12/14 条 + A/B 对比 | W2 | S1 |
| M3-SE-02 | 多策略自动择优 | W2 | S3 |
| M3-SE-03 | 求解重试编排 + `PARTIAL` 降级模式 | W3 | S2/S15 |
| M3-SE-04 | `SPLIT/MERGE` 调整语义 | W4 | S15 |
| M3-BD-01 | Sandbox CRUD + apply + compare | W5 | E6–E8/S18 |
| M3-BD-02 | 计划下发 outbox + 通知通道 | W5 | S6 |
| M3-BD-03 | 采购建议 `GET /mrp/suggestions` | W6 | E10/S4 |
| M3-BD-04 | `customers` 导入导出（含模板） | W9 | S17 |
| M3-FE-01 | 权限/审计页面 | W8 | S18 |
| M3-FE-02 | 登录页与令牌刷新 | W8 | S14 |
| M3-FE-03 | Sandbox 场景管理与对比页 | W8 | E6–E8 |
| M3-FE-04 | 5000 任务虚拟滚动 | W7 | S12 |
| M3-QA-01 | 性能专项（500 单收敛 + 5000 任务容量） | W9 | S5 |
| M3-QA-02 | 文档补齐（用户/开发/部署手册） | W9 | S18 |
| M3-QA-03 | 种子客户试用与反馈 | W9 | S18 |
| M3-QA-04 | 甘特 5000 任务验收 | W7/W9 | S5 |
| M3-QA-05 | 生产加固与监控告警 | W9 | S9 |
| M3-AG-01~04（条件） | Agent 四端点 | W10 | S19/§0.3 |
| M3-ATP-01（条件） | ATP 交期预估 | W6 | S19/§0.3 |

---

## 2. 流切分 W1–W10

> 写入范围以**目录级互斥**为准；同一文件不得出现在两条流。M3 沿用 M2 的 W1–W9 编号与职责，新增 W10（条件）。

### W1 平台与契约（`dev-CONTRACT`）

- **目标**：RBAC/认证/审计三件套 + M3 契约出口；保证所有流按同一权限与错误码口径落地。
- **写入范围（独占）**：
  - `aps-common/.../common/{api,exception,idempotency,constant,security}/**`（新增 `security/` 放 `PermissionCode`、注解）
  - `aps-web/.../web/{advice,filter,idempotency}/**`、`aps-web/.../controller/{system,auth,audit}/**`
  - `aps-infrastructure/.../persistence/{config,audit}/**`
  - `db/migration/V8__rbac_permission.sql`、`V9__audit_event.sql`
  - `docs/api/**`、根 `pom.xml`
- **不得进入**：`aps-service/**` 业务包、`aps-solver/**`、`aps-frontend/**`、V10+、`deploy/**`。
- **依赖前置**：M2 收口；C30/C31 冻结；V1 `role/permission` 结构审计（D0）。
- **验收口径**：
  1. 未带权限调用 `POST /tasks/{id}/adjust` 返回 `403/40300`，带 `SCHEDULE_ADJUST` 通过（curl 证据 + traceId）。
  2. `POST /auth/login` 正确签发、过期返回 `401/40100`；API Key 与 JWT 并存时顺序符合 C27。
  3. 审计：冻结/发布/回滚/调整各写一条 `audit_log`，字段完整；`GET /audit-logs` 可按 `tenant/type/时间` 过滤。
  4. `constraint:` 别名清理后，旧样例仍可用，且有一个版本的兼容窗口。
- **预估规模**：3–4 卡，约 8–10 人日。

### W2 策略与择优（`dev-SE1`）

- **目标**：**规则码扩容不在 M3 范围**（维持 C19 冻结的 8 条码，裁定见本节末注记）；M3 只做 A/B 对比与多策略择优。
- **写入范围（独占）**：`aps-solver/solver-dsl/**`、`aps-solver/solver-timefold/.../constraint/**`、`model/TaskPlan.java`、`aps-service/.../service/strategy/**`、`aps-web/.../controller/strategy/**`、`persistence/strategy/**`。
- **不得进入**：`solver-api/**`、`timefold/{setup,calendar,duration,dependency}/**`、`TimefoldModelBuilder`、`service/schedule/**`、`aps-frontend/**`。
- **依赖前置**：C35 冻结；M2 C19 已实现。
- **验收口径**：每条新规则至少 1 正 1 反例单测；A/B 两次求解的 `hard/soft/makespan` 可对比；择优结果可复现（固定 `randomSeed`）；并列有明确规则。
- **范围裁定（2026-11-04，D0 确认）**：M3 **不扩规则码**，仍为 C19 冻结的 8 条软约束白名单（`SOFT_RULE_COUNT = 8`）。
  理由：C19 白名单只有 8 条；`SOFT_RULE_COUNT` 位于 W3 禁改目录；硬加规则码会让既有软权重静默失效。
  因此 `m2-plan.md#L41` 与 PRD 的「12/14 条完整规则」**不属于 M3 范围**，原文不改，以本裁定为准；
  本文 §0.1 S1、§1.5 `M3-SE-01` 与 §7.2 卡表中的「补至 12/14 条」仅为历史引用，不再作为 W2 验收项。
- **预估规模**：3–4 卡，约 9–12 人日。

### W3 求解异步增强（`dev-SE3`）

- **目标**：重试编排 + `PARTIAL` 降级模式（M3 唯一新增终态变体）。
- **写入范围（独占）**：`solver-api` 新增文件、`service/schedule/{ScheduleService,SolveTaskLifecycleService,SolveProgressPublisher}.java`、`persistence/schedule/{SolveTaskRepository,ConstraintJustificationRepository}.java`、`controller/solve/**`、`config/SolveProgressWebSocketConfiguration.java`、`application.yml`。
- **不得进入**：`ScheduleProblemAssembler/ScheduleResultWriter/ScheduleRepository`、`controller/gantt/**`、`solver-timefold/**`。
- **依赖前置**：C37 冻结；M2 C20/C28 已实现；W4 装配器接口不变。
- **验收口径**：
  1. 引擎异常自动重试 ≤N 次（N 由 D0 定，提案 1 次），重试仍失败 → `FAILED` + `engineFallback*`。
  2. `PARTIAL`：部分排位时 `status != SOLVED`，响应含未排任务清单与原因；`persisted` 与落库行数一致。
  3. 5s 超时回归仍 `TIMEOUT`/`persisted=0`（D3 口径不回退）。
- **预估规模**：2–3 卡，约 6–8 人日。

### W4 甘特后端与调整语义（`dev-SE4`）

- **目标**：`SPLIT/MERGE` 可执行；5000 任务读路径可支撑（后端侧）。
- **写入范围（独占）**：`solver-api` 既有文件、`solver-timefold/{setup,calendar,duration,dependency}/**`、`model/**`（除 `TaskPlan.java`）、`TimefoldModelBuilder/TimefoldSolverAdapter`、`service/schedule/{ScheduleProblemAssembler,ScheduleResultWriter}.java`、`IncrementalScheduleService`、`ScheduleRepository.java`、`controller/gantt/**`、`db/migration/V10__*`（若涉及 `task` 扩展，与 W5 的 V10 二选一，D0 定号）。
- **不得进入**：`solver-dsl/**`、`timefold/constraint/**`、`TaskPlan.java`、`ScheduleService.java`、`service/order/**`、`aps-frontend/**`。
- **依赖前置**：C36 冻结；M2 C21/C22/C29 已实现；W1 RBAC 注解可用。
- **验收口径**：SPLIT 后父子关系与序号正确、审计可追溯；MERGE 前置校验（同资源/同订单/时间相邻）失败返回 `422xx`；`GET /gantt/order` 在 5000 任务下 P95 < 2s（后端仅计响应时间）。
- **预估规模**：3–4 卡，约 9–11 人日。

### W5 场景与计划下发（`dev-BD5`）

- **目标**：Sandbox CRUD/apply/compare；计划下发 outbox 与通知通道。
- **写入范围（独占）**：`aps-service/.../service/plan/**`、新建 `service/sandbox/**`、`service/notification/**`、`controller/plan/**`、新建 `controller/sandbox/**`、`persistence/plan/**`、`persistence/sandbox/**`、`persistence/notification/**`、`db/migration/V10__sandbox_lifecycle.sql`、`V11__notification_outbox.sql`。
- **不得进入**：`service/schedule/**`、`controller/gantt/**`、`persistence/audit/**`（已移交 W1）、`aps-frontend/**`、其他迁移。
- **依赖前置**：C32/C33 冻结；V1 `sandbox` 结构审计；W1 审计切面可用。
- **验收口径**：`POST /sandboxes` → apply 后 BASELINE 指向新版本且原 BASELINE 可回退；compare 返回差异（新增/删除/移位任务计数与明细）；下发 outbox 幂等（同 `Idempotency-Key` 只一条），通道失败可重试且不重复通知。
- **预估规模**：4–5 卡，约 11–13 人日。

### W6 MRP 与采购建议（`dev-MRP`）

- **目标**：采购建议端点（E10）；ATP 仅在用户确认后开卡。
- **写入范围（独占）**：`aps-service/.../service/mrp/**`、`controller/mrp/**`、`persistence/mrp/**`。
- **不得进入**：`service/schedule/**`、`controller/solve/**`、`aps-frontend/**`（前端在 W8）、`db/migration/**`。
- **依赖前置**：C34 冻结；M2 C25 已实现；W6 不依赖其他流。
- **验收口径**：给定 BOM/库存/在途，建议量与 `availableQty = onHand + transit - safetyStock` 一致；按 `suggestedOrderDate` 升序；空结果 200 + `[]`；不出现负数建议。
- **预估规模**：2–3 卡，约 5–7 人日（ATP 条件卡另计 3 人日）。

### W7 甘特前端与容量（`dev-FE7`）

- **目标**：5000 任务虚拟滚动；调整/合并交互。
- **写入范围（独占）**：`aps-frontend/src/components/gantt/**`、`views/gantt/**`、`api/gantt.ts`、`types/gantt.ts`、`mocks/gantt-data.ts`。
- **不得进入**：`src/router/**`、`src/api/index.ts`、其他 `views/**`、`mocks/handlers.ts`、任何后端目录、`package.json`。
- **依赖前置**：M3-FE-04 卡；W4 甘特接口 D+；C36 冻结。
- **验收口径（可执行）**：5000 任务数据集下首屏 P95 < 2s、滚动不掉帧（Chrome Performance 记录 ≤ 30 帧丢）；缩放/拖拽 50 步不崩；合并/拆分交互回写后端成功；`npm run type-check`、`lint` 0 错。
- **预估规模**：3–4 卡，约 8–10 人日。

### W8 前端页面与集成（`dev-FE8`）

- **目标**：登录页、权限/审计页、Sandbox 页；路由与 API 出口统一。
- **写入范围（独占）**：`src/views/{login,system,sandbox,strategies}/**`、`src/router/**`、`src/api/index.ts`、`src/constants/**`、`src/mocks/{handlers,data}.ts`、`src/stores/**`。
- **不得进入**：`src/components/gantt/**`、`views/gantt/**`、`api/gantt.ts`、任何后端目录、`package.json`。
- **依赖前置**：W1 `auth/audit` API、W5 sandbox API、W2 A/B 结果形状；C30/C32 冻结。
- **验收口径**：登录→令牌过期→刷新/重登闭环；无权限菜单隐藏且直接调用返回 403 可读提示；Sandbox 页可建/对比/应用；审计页可按时间过滤；`type-check`/`lint` 0 错。
- **预估规模**：4–5 卡，约 11–14 人日。

### W9 QA / Excel / 部署（`dev-QA2`）

- **目标**：性能专项、Excel 扩展（`customers` + 异步大文件）、生产加固、文档、种子客户试用。
- **写入范围（独占）**：`aps-service/.../service/importexport/**`、`controller/importexport/**`、`scripts/perf/**`、`docs/testing/**`、`deploy/**`、`.gitlab-ci.yml`、`scripts/ci-*`、`aps-service/pom.xml`、`db/migration/V12__import_job.sql`。
- **不得进入**：其他业务 Controller/Service、`db/migration/**`（除 V12）、`aps-frontend/**`、根 `pom.xml`。
- **依赖前置**：C38/C39 冻结；M3-QA-01 需 W4/W3 收敛后启动；V12 D0 发号。
- **验收口径**：`customers` 模板/预览/执行/导出四段实测；异步导入 5000 行不阻塞请求线程且可轮询；性能专项出具报告（500 单收敛曲线 + 5000 任务容量）；监控告警可触发（健康/错误率/队列积压各一条）；文档三册齐全。
- **预估规模**：5–6 卡，约 14–16 人日。

### W10 Agent（条件流，`dev-AG`）

- **目标**：E2–E5 四端点；**仅当用户确认把 v0.5 的 Agent 能力前移到 M3 时启动**。
- **写入范围（独占）**：新建 `aps-service/.../service/agent/**`、`controller/agent/**`、`persistence/agent/**`、`V13__agent_event.sql`。
- **不得进入**：求解核心、`service/schedule/**`、其他迁移。
- **依赖前置**：C40 冻结；LLM 服务可用性（**待用户确认**）；PRD §7.2 前移裁决。
- **验收口径**：事件上报幂等；NL→DSL 仅在 C19 白名单内生成且校验通过；explain 引用真实 `constraint_justification`；what-if 不落已发布计划。
- **预估规模**：暂不估（条件流）。

---

## 3. 并行度设计

### 3.1 依赖链（文字版）

```
D0（契约 C30–C41 + V8–V13 发号 + V1 结构审计）
 │
 ├─ W1 平台三件套 ──────────────┐
 ├─ W3 重试/PARTIAL ────┐        │
 ├─ W4 SPLIT/MERGE ────┼──┐     │
 ├─ W6 采购建议 ───────┘  │     │
 └─ W9 性能专项（只读基线）│     │
                          │     │
   W2 择优 ←── W3(批量入口)┘     │
   W5 Sandbox/下发 ←── W1(审计)──┘
   W7 5000 任务 ←── W4(接口) + C36
   W8 页面 ←── W1(auth/audit) + W5(sandbox) + W2(A/B)
   W10 Agent ←── 用户确认（条件）
```

- **可同时开工（第一波）**：W1、W4、W6、W9（W9 仅基线摸底）。
- **第二波（有前置）**：W3（不依赖 W4 代码，可与 W4 并行；仅接口约定）、W2（等 W3 批量入口）、W5（等 W1 审计切面）、W7（等 W4 接口）、W8（等 W1/W5/W2 的 API 形状）。
- **必须串行**：V8→V13 的应用顺序（单一应用者）；制品构建与发布；全局重构（跨流改名/移动）。

### 3.2 单权约束（写死）

1. **打包单权**：同一时刻只有 **W9** 可执行 `mvn package` / `-pl aps-admin -am package`；其他流只能 `mvn -pl <模块> -am test`。
2. **迁移单权**：同一时刻只有 **W1（S0）** 执行 `flyway migrate`；各流只写自己的 `V*.sql`。
3. **全局重构单权**：跨目录改名/移动/接口批量替换，须 S0 发窗口通知，且同一时刻只允许一条流执行。
4. **前端 dev server 单权**：5181 只允许一个实例（W7），其他流不得占用。
5. **构建窗口**：全量 `verify` 由 W1 在固定窗口执行，各流测试错峰。

### 3.3 批次建议（不排日期，仅顺序）

| 批次 | 参与流 | 内容 |
|---|---|---|
| B0 | W1 | D0 冻结、V8–V13 发号、V1 结构审计 |
| B1 | W1/W4/W6/W9 | RBAC/审计、SPLIT/MERGE、采购建议、性能基线 |
| B2 | W3/W2/W5 | 重试+PARTIAL、择优、Sandbox/下发 |
| B3 | W7/W8 | 5000 任务、页面与登录 |
| B4 | W9/W1 | 性能报告、全量 verify、制品级终验 |
| B5（条件） | W10 | Agent 前移（如确认） |

---

## 4. M3 门槛（即 M2 收口条件）

M3 不得开工，除非同时满足：

1. **三条 P1 闭合**：QA2-01（降级任务查询 500）、QA2-02（未知 sandbox 500）、QA2-03（不存在订单号被静默剔除）在**当前源码构建的制品**上复测通过（依据：[m2-qa-02-defects.md#L13-L21](file:///d:/tools/smart-aps-agent/docs/qa/m2-qa-02-defects.md#L13-L21)）。
2. **制品级终验 `Skipped=0`**：开启 `APS_TEST_DB_ENABLED=true` 的全量 `verify`，不得以跳过/禁用凑绿（依据：[m2-build-incidents.md#L88-L93](file:///d:/tools/smart-aps-agent/docs/testing/m2-build-incidents.md#L88-L93)）。
3. **演示口径 D1–D3 在新制品通过**：8 单/48 任务、50 单 60s、50 单 5s 三项（依据：[m2-plan.md#L56-L64](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L56-L64)）。
4. 契约侧：§0.5 制品级验收规则无待补（`checksum`/`dryRun` 等 M2 必须补项已闭合或明确转 M3 卡）。

---

## 5. 风险与已知技术债

| # | 风险/债 | 证据 | 影响 | 处置 |
|---|---|---|---|---|
| R1 | **500 单容量上限**：构解约 168s，60s/300s 均未完整收敛 | [m2-plan.md#L54](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L54)；[m2-qa-01-perf.md#L145](file:///d:/tools/smart-aps-agent/docs/perf/m2-qa-01-perf.md#L145)（构解 ≈167.8s） | M3 性能优化周期不可控 | 单列 M3-QA-01；先剖析构解（O(n²) 关系结构），不以调大 timeout 掩盖 |
| R2 | **真库集成测试需 `APS_TEST_DB_ENABLED=true`**，默认跳过 25 条 | [m2-build-incidents.md#L90-L93](file:///d:/tools/smart-aps-agent/docs/testing/m2-build-incidents.md#L90-L93) | “全绿”可能未验真库 | 所有终验强制开启并要求 `Skipped=0` |
| R3 | **前端无单测运行器**（`package.json` 仅有 dev/build/type-check/lint/format） | [package.json#L6-L15](file:///d:/tools/smart-aps-agent/aps-frontend/package.json#L6-L15) | 前端回归靠手测，M3 页面增多后风险放大 | D0 决策：引入 Vitest（需 W1 批依赖）或明确“手测 + 用例清单” |
| R4 | **`moveThreadCount` 企业版能力缺失**：社区版单线程，传 7 无效 | [perf#L146](file:///d:/tools/smart-aps-agent/docs/perf/m2-qa-01-perf.md#L146)；[contract#L300](file:///d:/tools/smart-aps-agent/docs/api/contract-freeze.md#L300) | 无法靠多线程提速 | 不在 M2/M3 范围；语义保持「缺省或 0 = 未指定」 |
| R5 | **制品未入库，需自建**：仓库无 `target` 外的制品存储 | 现状（`aps-admin/target` 仅本地构建产物） | 终验依赖现场构建，构建漂移风险 | W9 建立“构建时间 + commit + jar 摘要”登记；终验只认登记过的制品 |
| R6 | 分布式求解无容量证据 | [m2-plan.md#L43](file:///d:/tools/smart-aps-agent/docs/plan/m2-plan.md#L43) 仅列名 | 过早引入会放大故障面 | M3 只做重试编排，分布式不立卡 |
| R7 | Agent/ATP 属 v0.5，却出现在 §24.13 的 M3 名单 | §0.3 | 范围膨胀 | 条件流 + 用户裁决，未确认不开卡 |

---

## 6. 待用户确认（缺材料，不许自造）

| # | 事项 | 缺什么材料 | 若不确认的默认处理 |
|---|---|---|---|
| U1 | Agent 四端点是否前移 M3 | PRD §7.2 明列 v0.5；需产品裁决与 LLM 服务可用性说明 | W10 不启动，端点维持 `50100` |
| U2 | ATP 是否前移 M3 | PRD 将 FR-OM-003 列 v0.5；无 ATP 算法/数据要求 | E9 不开卡，维持占位 |
| U3 | 主数据业务编码规范 | 仓库无编码规则文档；PRD 未给 | 仅用测试夹具前缀，不定义业务号段 |
| U4 | `customers` 是否 M3 落地 | 用户已说“M3 单开卡片”，但未给模板列/外键要求 | 按 M2 9 类模板风格提案，卡前确认 |
| U5 | 前端单测运行器是否引入 | `package.json` 无 test 脚本；无团队约定 | 继续 type-check + lint + 手测清单 |
| U6 | MES 下发是真实现还是 outbox 占位 | PRD 列 v0.5「ERP/MES 连接器」；M2 C24 仅占位 | 只做 outbox + 通道抽象，不接真实 MES |
| U7 | 通知通道是否发真实邮件/短信/飞书 | 无凭据/网关信息 | 只落 outbox + 状态，不外发 |
| U8 | `constraint:` 别名清理窗口 | m2-plan 要求产品确认并保留一版 | 默认保留一整版，不删 |
| U9 | 重试次数 N 与退避策略 | 无 SLA/运维口径 | 提案 1 次、固定退避，D0 冻结 |
| U10 | V8–V9 旧「测试部署流」预留是否重发 | M2 已重发 V2–V9 的先例，但 M3 未正式拍板 | 按本文件 §1.2 重发，D0 记录 |

---

## 7. D0 执行结论（2026-11-04）

### 7.1 结论（先给放行判断）

- **第一批可放行**：**C30 / C31 / C32 / C36** 已冻结。W1（RBAC+审计）、W4（SPLIT/MERGE）、W5（Sandbox）可按 `contract-freeze.md` §25 开工，无需再等 D0。
- **W6 的真实阻塞项是 C34（不是 C33 SPLIT/MERGE）**：本批已把 C34 冻结，W6 可直接开工。派工单原文「第一批阻塞 W1/W4/W6」与清单不一致，以本条为准。
- **本批实际落笔 12 份**：C30–C41 全部写入 §25（一次往返，避免第二趟再改口径）。其中 **C33/C35/C37/C39 形状已冻结但依赖各自上游**；**C40/C41 仅条件冻结**（门禁未过，形状不冻结、不开卡）。
- **契约编号冲突（未改号）**：派工单把 `SPLIT/MERGE` 标为 C33，本文件 §1.1 的 **C33=下发通知、C36=SPLIT/MERGE**。D0 **保持 C36**；C33 按原义冻结下发。若坚持 C33，需整体重编号（见 §25.14-1）。
- **V8–V13 只发 2 个 DDL**：**V11（notification_outbox）**、**V12（import_job）**。V8/V9/V10/V13 经 V1 审计后确认**无需 DDL**（V1 已含 RBAC 全量结构与种子、audit_log+索引、sandbox 类型/唯一 BASELINE、task_adjustment SPLIT/MERGE），**空号保留不建文件**。详见 §25.13。
- **已应用 V1–V7 未改一字**；C1–C29 未改号、未覆盖；只追加 §25 与本节。

### 7.2 22 张卡：13 张存量 + 9 张新增（无 owner、待开始）

> 卡号冲突处置：看板存量 `M3-BE-02` 已是 **Sandbox**、`M3-BE-04` 已是 **多租户**，§1.5 提案的 BE-02（JWT）/BE-04（别名清理）**不能复用原号**。按「已存在不重复建」原则，新增卡改用未占用号 `M3-BE-05/06`；Sandbox 由存量 `M3-BE-02` 承接（§1.5 的 `M3-BD-01` 不另建）。

| # | 卡号 | 标题 | 类型 | 归属流 | 契约 | 看板现状 |
|---|---|---|---|---|---|---|
| 1 | `M3-BE-01` | RBAC 用户/角色/权限（FR-SYS-001） | 存量 | W1 | C30 | 已存在·待开始 |
| 2 | `M3-BE-02` | Sandbox 场景管理（FR-SYS-003） | 存量 | W5 | C32 | 已存在·待开始 |
| 3 | `M3-BE-03` | 审计日志（FR-SYS-004） | 存量 | W1 | C31 | 已存在·待开始 |
| 4 | `M3-BE-04` | 多租户字段与过滤预留 | 存量 | W1 | —（非 M3 主轴，保留不排期） | 已存在·待开始 |
| 5 | `M3-FE-01` | 登录/用户/角色管理页面 | 存量 | W8 | C30 | 已存在·待开始 |
| 6 | `M3-FE-02` | Sandbox 对比视图 | 存量 | W8 | C32 | 已存在·待开始 |
| 7 | `M3-FE-03` | 审计日志查询页面 | 存量 | W8 | C31 | 已存在·待开始 |
| 8 | `M3-FE-04` | 甘特图性能优化与 UI 打磨 | 存量 | W7 | —（C29） | 已存在·待开始 |
| 9 | `M3-QA-01` | 集成/E2E 测试 | 存量 | W9 | — | 已存在·待开始 |
| 10 | `M3-QA-02` | 非功能验收测试 | 存量 | W9 | — | 已存在·待开始 |
| 11 | `M3-QA-03` | 安全扫描与加固 | 存量 | W9 | C30 | 已存在·待开始 |
| 12 | `M3-QA-04` | 种子客户真实数据验证 | 存量 | W9 | C38 | 已存在·待开始 |
| 13 | `M3-QA-05` | 文档输出 | 存量 | W9 | — | 已存在·待开始 |
| 14 | `M3-BE-05` | JWT 登录与 API Key 并存（后端） | **新增** | W1 | C30 | 待落库 |
| 15 | `M3-BE-06` | `constraint:` 别名清理与兼容窗口 | **新增** | W1 | —（C19 兼容） | 待落库 |
| 16 | `M3-SE-01` | 策略规则补至 12/14 条 + A/B 对比 | **新增** | W2 | C35 | 待落库 |
| 17 | `M3-SE-02` | 多策略自动择优 | **新增** | W2 | C35 | 待落库 |
| 18 | `M3-SE-03` | 重试编排 + `PARTIAL` 降级 | **新增** | W3 | C37 | 待落库 |
| 19 | `M3-SE-04` | `SPLIT/MERGE` 调整 | **新增** | W4 | C36 | 待落库 |
| 20 | `M3-BD-02` | 计划下发与通知 outbox | **新增** | W5 | C33 | 待落库 |
| 21 | `M3-BD-03` | 采购建议优化（MRP） | **新增** | W6 | C34 | 待落库 |
| 22 | `M3-BD-04` | `customers` 导入导出 + 异步大文件 | **新增** | W9 | C38/C39 | 待落库 |

- **不开卡**：`M3-AG-01~04`、`M3-ATP-01`（条件项，U1/U2 未确认；`contract-freeze.md` §25.11/25.12 仅登记端点，不冻结形状）。
- 存量 13 张**不改标题、不改 owner、不置完成**；本次只登记映射。

### 7.3 落库受阻（需你处理）

- `GET /api/tasks` 实测 M3 存量 **13** 张，全部「待开始」、无 owner，符合 22 张基线的 13/22。
- 远端看板 **只支持 GET/PATCH，`POST /api/tasks` 返回 404**（试过 `/api/tasks/import`、`/api/tasks/bulk` 同样 404），**无法建卡**。
- 9 张新增卡的完整 payload 已写入 [m3-d0-cards.json](file:///d:/tools/smart-aps-agent/docs/plan/m3-d0-cards.json)；需要看板写权限（POST 接口或库直写）后导入。未擅自改 seed、未把任何卡置完成。

### 7.4 U 项回答（原 §6 未改，结论在此）

| U | D0 结论 |
|---|---|
| U4 `customers` | 承接，slug `customers`，6 列模板冻结（§25.9） |
| U6 MES 下发 | 只做 outbox + 通道 SPI，不接真实 MES（§25.5） |
| U7 通知真实外发 | 不外发；`NOOP`/`FILE` 两实现用于验收（§25.5.3） |
| U9 重试 N | 冻结为 **2 次尝试**（首次+1 次重试）、退避 30s（§25.8.1） |
| U1/U2 Agent/ATP | 维持条件项，不开卡、不冻结形状（§25.11/25.12） |
| U8 `constraint:` 别名 | 保留一整版，M3 只做清理卡 `M3-BE-06`，不删兼容 |

### 7.5 需要你拍板

完整 13 条见 `contract-freeze.md` §25.14。最影响开工的 5 条：

1. **C33 vs C36**：是否确认「SPLIT/MERGE 归 C36」，派工单笔误不改号。
2. **文件上限 5MB（C26）vs 实现 10MB**：收紧实现，还是走变更单把 C26 改 10MB。
3. **API Key 等价 ADMIN**：M3 是否接受该让步（否则需给 key 绑角色，超出 M3 预算）。
4. **refresh token**：确认 M3 只做 8h 重登（不新增 `/auth/refresh`）。
5. **22 卡落库方式**：给看板写权限 / 由你导入 payload / 允许改 seed。
