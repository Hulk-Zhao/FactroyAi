# 数据库 Schema 约定（P0-05）

> 适用版本：mfg-aps-platform v0.1 MVP（PostgreSQL 15+）
> 迁移文件：`db/migration/V1__init.sql`（本文件与迁移脚本为唯一事实来源，DTO/API 以此对齐）
> 依据：`mfg-aps-platform-PRD.md` 第 4 章 / 第 8 章；`aps-arch-page` 第 4 节数据库设计指引

---

## 1. 迁移与执行

### 1.1 目录约定

```
db/
└── migration/
    └── V1__init.sql        # 基线：扩展 + 28 张表 + 索引 + 种子数据
docs/
└── db/
    └── schema.md           # 本文档
```

### 1.2 Flyway 接入（工程骨架 P0-01 完成后）

P0-05 已新增 `aps-admin/src/main/resources/application-dev.yml`（dev profile 数据源 + Flyway 配置，未改动 `application.yml` 与任何 pom）。仍需 P0-01 补齐依赖与资源映射：

1. 依赖：`org.postgresql:postgresql`、`org.flywaydb:flyway-core`、`org.flywaydb:flyway-database-postgresql`（Flyway 10+ 模块化）。
2. 资源：把仓库根目录 `db/migration` 复制/映射到 classpath `db/migration`（Maven `<resources>` 或构建脚本）。
3. 若仅在本地跑，不想做资源映射，可把 `spring.flyway.locations` 改为 `filesystem:db/migration`（工作目录须为仓库根）。

优先方案（推荐）：把 `db/migration` 作为 Maven resources 目录（或构建时复制）打进 classpath，`application-dev.yml` 中对应配置为：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/aps?currentSchema=public
    username: aps
    password: ${DB_PASSWORD:aps_dev_pwd}
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true
    out-of-order: false
```

若开发期希望直接从仓库根目录读取（不复制到 resources）：

```yaml
spring:
  flyway:
    locations: filesystem:db/migration
```

> 注意：`filesystem:` 位置在打包为 jar 后不生效，生产镜像必须使用 classpath 方案。

Maven 插件方式（可选，用于 CI 空库演练）：

```xml
<plugin>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-maven-plugin</artifactId>
  <version>10.x</version> <!-- 使用当时最新稳定版 -->
  <configuration>
    <url>jdbc:postgresql://localhost:5432/aps</url>
    <user>postgres</user>
    <password>postgres</password>
    <locations>
      <location>filesystem:db/migration</location>
    </locations>
  </configuration>
</plugin>
```

### 1.3 版本命名

- 使用 `V{版本}__{描述}.sql`（双下划线），一经合入禁止修改已有版本内容。
- 增量变更新建 `V2__xxx.sql`、`V3__xxx.sql`；视图/函数等可重复执行对象使用 `R__xxx.sql`。
- 破坏性变更（删列/改类型）必须另附回滚说明，禁止直接修改 V1。

---

## 2. 表清单（28 张）

### 2.1 十张核心表（PRD/架构页口径）

| # | 表名 | 说明 | 主键 |
|---|---|---|---|
| 1 | `material` | 物料主数据 + 行业属性 | UUID |
| 2 | `bom` / `bom_item` | 多级 BOM、版本、拼板、子件行 | UUID |
| 3 | `resource` | 主/辅/人力/工具资源、产能、维护计划 | UUID |
| 4 | `calendar` | 班次/节假日/可用工时 | UUID |
| 5 | `operation_template` | 可复用工序模版（版本化） | UUID |
| 6 | `routing` / `routing_node` / `routing_edge` | 工艺路线 DAG（主表 + 节点 + 边） | UUID |
| 7 | `orders` | 销售/生产订单 | UUID |
| 8 | `task` | 排产任务（求解结果） | BIGSERIAL |
| 9 | `strategy` / `strategy_rule` | 排产策略 + 规则权重 | UUID |
| 10 | `solve_task` | 求解任务生命周期 | BIGSERIAL |

> 架构页把「bom+bom_item」「routing+node/edge」「strategy+rule」各视为一张核心表，故上表展示为 10 个逻辑核心实体、13 张物理表。

### 2.2 配套表（MVP 亦需）

| 表名 | 说明 | 主键 |
|---|---|---|
| `constraint_justification` | Timefold 约束归因 + Agent 中文解释 | UUID |
| `sandbox` | 场景（BASELINE / WHAT_IF / ARCHIVED） | UUID |
| `audit_log` | 审计日志（追加只读） | BIGSERIAL |
| `users` | 用户 | UUID |
| `role` | 角色 | UUID |
| `permission` | 权限点 | UUID |
| `role_permission` | 角色-权限关联 | 复合主键 |
| `user_role` | 用户-角色关联 | 复合主键 |
| `task_adjustment` | 人工调整历史 / 撤销栈 | BIGSERIAL |

### 2.3 支撑表（外键完整性所需）

| 表名 | 说明 | 主键 |
|---|---|---|
| `tenant` | 租户（MVP 单租户，默认 UUID 固定） | UUID |
| `customer` | 客户主数据 | UUID |
| `supplier` | 供应商主数据 | UUID |
| `material_category` | 物料分类（树形） | UUID |
| `resource_group` | 资源组（同组资源可互换） | UUID |

### 2.4 依赖关系概览

```
tenant ─┬─ users ─ user_role ─ role ─ role_permission ─ permission
        ├─ customer / supplier / material_category / material
        ├─ bom ─ bom_item ─ material
        ├─ resource_group ─ resource ─ calendar（双向引用，外键后置 ALTER）
        ├─ operation_template ─ routing ─ routing_node / routing_edge
        ├─ orders（material / customer / routing）
        ├─ sandbox ─ task（orders / operation_template / resource）
        ├─ strategy ─ strategy_rule
        ├─ solve_task（sandbox / strategy）─ constraint_justification
        ├─ task ─ task_adjustment
        └─ audit_log（仅 tenant_id，user_id 弱外键）
```

---

## 3. 命名与字段约定

### 3.1 命名

| 约定 | 示例 |
|---|---|
| 表名：小写复数或领域名，snake_case | `orders`、`bom_item`、`routing_node` |
| 字段：snake_case | `due_date`、`qty_per_unit`、`created_at` |
| 外键：`{实体}_id` | `order_id`、`resource_id`、`group_id` |
| 业务编码：`code` / `{实体}_no` | `material.code`、`orders.order_no` |
| 时间点：`*_at`（TIMESTAMPTZ） | `start_time`、`finished_at`、`created_at` |
| 日期：`*_date`（DATE） | `due_date`、`effective_date` |
| 布尔：`is_*` / `has_*` / 语义名 | `is_panel`、`is_phantom`、`deleted` |
| 数量/金额：`qty` / `*_value` / `*_rate`（NUMERIC） | `qty_per_unit NUMERIC(18,6)`、`scrap_rate NUMERIC(5,2)` |
| JSONB：`*_attrs` / `*_rules` / `snapshot` / `config` / `metrics` | `ext_attrs`、`optimization_rules` |
| 索引：`idx_{表}_{列}`；唯一：`uq_{表}_{列}`；约束：`chk_{表}_{字段}`；外键：`fk_{表}_{引用}` | `idx_task_sandbox_resource_start` |

### 3.2 通用字段（业务表统一）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | UUID / BIGSERIAL | PK | 见 3.3 |
| `tenant_id` | UUID | NOT NULL，FK→tenant | 查询自动注入租户过滤；MVP 默认租户 `00000000-0000-0000-0000-000000000001`（由应用层统一填充，不写在 DDL DEFAULT，避免跨租户误写） |
| `version` | INTEGER | NOT NULL DEFAULT 1，CHECK >=1 | 乐观锁版本号（MyBatis-Plus `@Version`） |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | UTC 存储；`updated_at` 由应用层 MetaObjectHandler 维护（未建 DB 触发器，避免与应用双写冲突） |
| `created_by` / `updated_by` | UUID | 可空 | 指向 `users.id`，不建强外键（避免引导期循环与历史数据不可删） |
| `deleted` | BOOLEAN | NOT NULL DEFAULT false | 软删除；唯一约束全部使用 `WHERE deleted=false` 的部分索引 |

**例外（追加型表）**：

- `audit_log`：仅 `created_at`，无 `updated_at/version/deleted`（不可篡改）。
- `task_adjustment`：仅 `created_at/created_by` + `undone` 标记（保留撤销栈语义，不做软删除）。
- `role_permission` / `user_role`：仅 `created_at/created_by`。

### 3.3 主键策略

| 类型 | 表 | 理由 |
|---|---|---|
| UUID（`gen_random_uuid()`） | 主数据、业务主表、关联表 | 分布式友好、租户安全、避免 ID 猜测 |
| BIGSERIAL | `task`、`solve_task`、`audit_log`、`task_adjustment` | 高频大表，索引紧凑、写入友好（架构页 4.2） |

> 对外 API 一律另设业务编码（`material.code`、`orders.order_no`）供人阅读与幂等校验；`task`/`solve_task` 对外使用 bigint id。

---

## 4. 枚举与状态机

> 枚举以 `VARCHAR + CHECK` 落地（非 PostgreSQL 原生 ENUM），便于 MyBatis-Plus 字符串映射与后续值扩展；新增枚举值需 `ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT` 新建迁移。

### 4.1 枚举字典

| 表.字段 | 取值 |
|---|---|
| `tenant.status` | ACTIVE / SUSPENDED |
| `users.status` | ACTIVE / DISABLED / LOCKED |
| `customer.priority` | VIP / NORMAL / LOW |
| `supplier.quality_grade` | A / B / C（可空） |
| `material.type` | FINISHED / SEMI / RAW / AUX / PACK |
| `bom.status` / `routing.status` | DRAFT / ACTIVE / INACTIVE |
| `resource.type` | PRIMARY / SECONDARY / HUMAN / TOOL |
| `resource.capacity_mode` | UNIT_PER_HOUR / SECONDS_PER_UNIT / BATCH_SECONDS |
| `calendar.type` | FACTORY / RESOURCE / SHIFT |
| `routing_edge.depend_type` | FS / SS / FF |
| `orders.source` | ERP_SYNC / MANUAL / AGENT |
| `orders.priority` | URGENT / HIGH / NORMAL / LOW |
| `orders.status` | DRAFT / CONFIRMED / SCHEDULED / RELEASED / IN_PROGRESS / COMPLETED / CANCELLED |
| `orders.freeze_status` | UNFROZEN / FROZEN / LOCKED_TIME_WINDOW / LOCKED_RESOURCE |
| `task.status` | PLANNED / FROZEN / IN_PROGRESS / COMPLETED |
| `strategy.direction` | FORWARD / BACKWARD / BOTTLENECK |
| `strategy_rule.rule_phase` | INITIAL / OPTIMIZATION |
| `strategy_rule.rule_type` | HARD / SOFT |
| `solve_task.engine` | TIMEFOLD / ORTOOLS / RULE_BASED |
| `solve_task.status` | PENDING / RUNNING / SOLVED / FAILED / CANCELLED / TIMEOUT |
| `sandbox.type` | BASELINE / WHAT_IF / ARCHIVED |
| `task_adjustment.adjust_type` | SPLIT / MERGE / MOVE / LOCK / FREEZE / UNFREEZE |

### 4.2 订单状态机（`orders.status`）

```
DRAFT ──confirm──▶ CONFIRMED ──schedule──▶ SCHEDULED ──release──▶ RELEASED
                        │                      │                    │
                        │                      │                    ▼
                        │                      │              IN_PROGRESS
                        │                      │                    │
                        └────────── CANCELLED ◀┘                    ▼
                                                            COMPLETED
```
- `DRAFT`：可编辑；`CONFIRMED` 后关键字段（产品/数量/交期）变更需走变更流程。
- `CANCELLED` 仅允许从 `DRAFT / CONFIRMED / SCHEDULED` 进入；`RELEASED` 之后取消需人工干预并记录审计。
- 订单冻结轴独立：`orders.freeze_status`，`UNFROZEN` ↔ `FROZEN / LOCKED_TIME_WINDOW / LOCKED_RESOURCE`。

### 4.3 排产任务状态机（`task.status`）

```
PLANNED ──freeze──▶ FROZEN ──unfreeze──▶ PLANNED
   │                   │
   └────start────▶ IN_PROGRESS ──▶ COMPLETED（FROZEN 也可开工，开工后不可 MOVE/SPLIT）
```
- `IN_PROGRESS` 仅允许 `FREEZE`，不允许 `MOVE / SPLIT / MERGE`（PRD BR-GT-004-05）。
- 人工调整写入 `task_adjustment`，`task.version` 乐观锁拦截并发修改。

### 4.4 求解任务状态机（`solve_task.status`）

```
PENDING ──▶ RUNNING ──┬─▶ SOLVED
                      ├─▶ FAILED
                      ├─▶ TIMEOUT
                      └─▶ CANCELLED（PENDING 亦可取消）
```
- `idempotency_key` 在租户内唯一（`WHERE idempotency_key IS NOT NULL AND deleted=false`），重复提交返回同一任务。
- 终态（SOLVED/FAILED/TIMEOUT/CANCELLED）不可回退；重试创建新记录。

### 4.5 版本生效状态机（`bom.status` / `routing.status`）

```
DRAFT ──activate──▶ ACTIVE ──expire/新版本生效──▶ INACTIVE
```
- 同一 `parent_material_id` / `product_id` 同时最多一个 `ACTIVE`（部分唯一索引强制）。
- 历史版本只读；修改 = 新建版本（`bom_version` / `routing_version`）。
- 日历同理：历史日历只读，变更新建 `calendar_version`。

### 4.6 场景状态机（`sandbox.type`）

```
BASELINE（每租户唯一，不可删除） ──复制──▶ WHAT_IF ──过期──▶ ARCHIVED（只读）
WHAT_IF ──应用为 BASELINE──▶ 覆盖 BASELINE（需 SCHEDULE_PUBLISH 权限与确认）
```

---

## 5. JSONB 内部 Schema 约定

> 原则：只有「扩展属性 / 行业属性 / 规则 / 快照 / 明细」使用 JSONB；参与排产计算或高频过滤的字段（数量、交期、优先级、时间、状态）必须为结构化列。
> 所有 JSONB 列均校验顶层类型；`ext_attrs` / `industry_attrs` / `payload` 等建立 GIN 索引。

### 5.1 `material.ext_attrs`（object，自由扩展）

```json
{ "color": "black", "brand": "TI", "customs_code": "8542.31", "tags": ["rohs", "reach"] }
```

### 5.2 `material.industry_attrs`（object，按行业约定）

```json
// SMT
{ "msd_level": 3, "exposure_hours": 168, "bake_hours": 24, "panel_size": 4, "package": "0603" }
// 储能
{ "cell_model": "IFR32140", "nominal_capacity_ah": 15, "nominal_voltage_v": 3.2,
  "grading_rule": { "capacity_tolerance_pct": 0.5, "resistance_max_mohm": 3 } }
```

### 5.3 `resource.industry_attrs` / `resource.maintenance_plan`

```json
// SMT 贴片机
{ "feeder_slots": 120, "supported_packages": ["0402","0603","QFN"], "program_capacity": 50 }
// 储能老化柜
{ "aging_cabinet_capacity": 20, "aging_hours": 24 }

// maintenance_plan
{ "periodic": [{ "cycle": "WEEKLY", "duration_minutes": 120 }],
  "next_due_at": "2026-11-10T00:00:00Z", "note": "每周三保养" }
```

### 5.4 `bom.items`（array，冗余快照）与 `bom_item`（事实来源）

```json
[{ "materialId": "uuid", "qtyPerUnit": 2.0, "scrapRate": 1.5, "position": "R12",
   "isPhantom": false, "level": 2, "seqNo": 1,
   "substitutes": [{ "materialId": "uuid", "priority": 1, "ratio": "1:1" }] }]
```
- `bom_item` 为唯一事实来源；`bom.items` 仅用于树形渲染/版本 diff 的快速读取，保存时由服务层同步刷新。
- `bom_item.substitutes` 为同结构数组。

### 5.5 `routing.nodes` / `routing.edges`（array，冗余快照）与 `routing_node` / `routing_edge`

```json
// nodes
[{ "id": "n10", "templateId": "uuid", "operationCode": "SMT-01", "optional": false,
   "overrides": { "standardSeconds": 45, "primaryResourceId": "uuid" },
   "position": { "x": 120, "y": 80 }, "seqNo": 10 }]
// edges
[{ "from": "n10", "to": "n20", "type": "FS", "lagMinutes": 0 }]
```
- `routing_node.node_key` 对应快照中的 `id`；关系表为事实来源，JSONB 为前端 DAG 渲染快照。
- DAG 环检测由服务层保存时执行（递归 CTE + 深度 ≤ 20）。

### 5.6 `calendar.shifts` / `calendar.holidays`（array）

```json
// shifts
[{ "startTime": "08:00", "endTime": "17:00", "daysOfWeek": [1,2,3,4,5] },
 { "startTime": "20:00", "endTime": "06:00", "daysOfWeek": [1,2,3,4,5] }]
// holidays
[{ "date": "2026-10-01", "name": "国庆节", "kind": "LEGAL" },
 { "date": "2026-10-10", "name": "调休上班", "kind": "MAKEUP" }]
```

### 5.7 `operation_template` 数组字段

- `secondary_resource_ids`：`["uuid", ...]`
- `input_material_ids`：`["uuid", ...]`

### 5.8 `strategy.initial_rules` / `optimization_rules` / `apply_condition`

```json
// initial_rules：初排方向与缺省顺序
{ "sequence": ["DUE_DATE", "PRIORITY"], "defaultSetupMinutes": 30 }
// optimization_rules：权重集合（明细以 strategy_rule 为准）
{ "weights": { "tardiness": 10, "setup": 3, "utilization": 1 },
  "rules": [{ "code": "MIN_SETUP", "type": "SOFT", "weight": 5 }] }
// apply_condition
{ "productIds": [], "customerIds": [], "productLines": ["SMT-A"], "orderSources": ["ERP_SYNC"] }
```

### 5.9 `solve_task.config` / `metrics`

```json
// config
{ "timeoutSeconds": 60, "threadCount": 8, "acceptor": "LATE_ACCEPTANCE",
  "randomSeed": 42, "maxSteps": 100000, "constraints": ["uuid-or-code"] }
// metrics
{ "stepCount": 12000, "scoreHistory": [0, -120, -80, -45], "durationMs": 15000 }
```

### 5.10 `sandbox.snapshot`（object）

```json
{ "capturedAt": "2026-11-03T02:00:00Z",
  "orderIds": ["uuid"], "resourceIds": ["uuid"], "strategyId": "uuid",
  "baselineSolveTaskId": 123, "note": "what-if 推演" }
```
> 大快照可后续外置对象存储；MVP 允许 JSONB 直接存放，但单条建议 < 1 MB。

### 5.11 `constraint_justification.implicated_facts`（array）

```json
[{ "type": "TASK", "id": "123" }, { "type": "RESOURCE", "id": "uuid" },
 { "type": "ORDER", "id": "uuid" }, { "type": "MATERIAL", "id": "uuid" }]
```

> V7（M2-SE-05）新增 `weight`（INTEGER，软约束生效权重，硬约束为空）与
> `deduction`（NUMERIC(18,2)，软约束扣分 = 权重 × 违反量，正数；硬约束为 0）。
> `constraint_id` 存储 `RuleCode`（如 `MIN_TARDINESS`），不存中文标题。

### 5.12 `task_adjustment.before_value` / `after_value`（object）

```json
{ "taskId": 123, "resourceId": "uuid", "startTime": "2026-11-03T08:00:00Z",
  "endTime": "2026-11-03T12:00:00Z", "sequenceInResource": 5, "status": "PLANNED" }
```

### 5.13 `audit_log.before_value` / `after_value`（object，可空）

按资源类型存关键字段前后值；敏感字段（密码）必须脱敏后写入。

### 5.14 `users.ext_attrs` / `tenant.config`

```json
// users.ext_attrs
{ "locale": "zh-CN", "timezone": "Asia/Shanghai", "position": "计划员" }
// tenant.config
{ "solverTimeoutSeconds": 60, "maxUsers": 20, "maxSandboxes": 20,
  "features": { "agent": true, "multiTenant": false } }
```

---

## 6. 索引说明

### 6.1 唯一约束（部分唯一索引）

| 索引 | 定义 | 对应业务规则 |
|---|---|---|
| `uq_material_tenant_code` | `(tenant_id, code) WHERE deleted=false` | 物料编码租户内唯一 |
| `uq_orders_tenant_order_no` | `(tenant_id, order_no) WHERE deleted=false` | 订单号租户内唯一 |
| `uq_bom_parent_version` | `(parent_material_id, bom_version) WHERE deleted=false` | BOM 版本唯一 |
| `uq_bom_one_active_per_parent` | `(tenant_id, parent_material_id) WHERE status='ACTIVE' AND deleted=false` | 同父件仅一个生效版本 |
| `uq_routing_product_version` | `(product_id, routing_version) WHERE deleted=false` | 工艺版本唯一 |
| `uq_routing_one_active_per_product` | `(tenant_id, product_id) WHERE status='ACTIVE' AND deleted=false` | 同产品仅一个生效版本 |
| `uq_sandbox_baseline` | `(tenant_id) WHERE type='BASELINE' AND deleted=false` | 每租户一个 BASELINE |
| `uq_solve_task_idempotency` | `(tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL AND deleted=false` | 求解幂等 |
| `uq_calendar_tenant_name_version` | `(tenant_id, name, calendar_version) WHERE deleted=false` | 日历版本唯一 |
| `uq_operation_template_code_version` | `(tenant_id, code, template_version) WHERE deleted=false` | 模版版本唯一 |
| `uq_strategy_tenant_name_version` | `(tenant_id, name, strategy_version) WHERE deleted=false` | 策略版本唯一 |
| `uq_users_tenant_username` / `uq_role_tenant_code` / `uq_permission_tenant_code` | `(tenant_id, code) WHERE deleted=false` | RBAC 唯一 |

### 6.2 查询索引（对齐 PRD 8.3 与架构页 4.3）

| 索引 | 用途 |
|---|---|
| `idx_task_sandbox_resource_start` `(sandbox_id, resource_id, start_time)` | 资源甘特图时间轴（按 sandbox + 资源 + 时间范围） |
| `idx_task_order_operation` `(order_id, operation_index)` | 订单工序链 |
| `idx_orders_tenant_status_due_date` `(tenant_id, status, due_date)` | 订单池按状态 + 交期 |
| `idx_solve_task_sandbox_status_created` `(sandbox_id, status, created_at)` | 求解任务列表与并发控制 |
| `idx_audit_log_tenant_created_action` `(tenant_id, created_at DESC, action)` | 审计倒序分页 |
| `idx_material_tenant_type_category` `(tenant_id, type, category_id)` | 物料筛选 |
| `idx_material_ext_attrs_gin` / `idx_material_industry_attrs_gin` | JSONB 扩展/行业属性查询 |
| `idx_material_tenant_name_trgm` `GIN (name gin_trgm_ops)` | 物料名称模糊搜索（pg_trgm） |
| `idx_task_sandbox_status` / `idx_task_resource_start` | 状态过滤、跨场景资源甘特 |
| `idx_task_adjustment_task_created` `(task_id, created_at DESC)` | 调整历史 / 撤销栈 |
| `idx_constraint_justification_solve_task` `(solve_task_id, constraint_id)` | 归因查询 |
| `idx_calendar_tenant_type_range` | 日历生效期冲突校验 |

> 其余外键列（`bom_item.bom_id`、`routing_edge.routing_id`、`orders.customer_id` 等）均已建普通索引，避免级联/回收时全表扫描。
> 性能建议：甘特图查询必须带 `sandbox_id + 时间范围`；单表 > 千万行走时间分区（PRD 三期）；BOM 展开用递归 CTE 且限深 ≤ 20 级。

### 6.3 外键策略

- 强外键：`orders.product_id`、`task.order_id`、`task.sandbox_id`、`routing.product_id`、`bom.parent_material_id` 等核心链路。
- 弱外键/无外键：`audit_log.user_id`、`created_by/updated_by`、`task_adjustment.operator_id`（仅索引）。
- 明细子表使用 `ON DELETE CASCADE`：`bom_item`、`routing_node`、`routing_edge`、`strategy_rule`、`constraint_justification`、RBAC 关联表。
- 软删除实体保留外键，查询时统一附加 `deleted = false`。

---

## 7. 种子数据（V1 内置，最小集合）

| 数据 | 内容 |
|---|---|
| 默认租户 | `00000000-0000-0000-0000-000000000001`（DEMO，MVP 单租户） |
| 预置角色 | ADMIN / PLANNER / PROCESS_ENGINEER / SALES / VIEWER |
| 预置权限 | 18 个模块级 + 操作级权限点（MD/PR/OM/SS/SE/GT/SYS/AG） |
| 角色权限映射 | 按 PRD FR-SYS-001 的职责划分 |
| 管理员账号 | `admin`（`password_hash` 为空，首次部署由应用初始化流程设置，避免默认口令风险） |
| 默认场景 | BASELINE「主计划（BASELINE）」 |
| 默认日历 | `CAL-7X24`（全厂 7×24） |

> 业务主数据（物料、BOM、资源、订单等）不预置，由导入或界面创建。

---

## 8. 与 PRD 第 8 章的差异与对齐说明

| 项 | PRD | 本 Schema | 原因 |
|---|---|---|---|
| 订单表名 | `Order` | `orders` | `order` 是 SQL 关键字；统一复数表名 |
| 用户表名 | `USER`（ER） | `users` | `user` 是 PostgreSQL 保留字 |
| `version` 语义 | 部分实体（bom/routing/operation_template/strategy/calendar）把 `version` 当业务版本 | `version` 统一为乐观锁；业务版本改名为 `bom_version` / `routing_version` / `template_version` / `strategy_version` / `calendar_version` | 落实统一通用字段要求，避免一列双语义 |
| 主键 | `task` / `solve_task` 为 UUID | `BIGSERIAL` | 架构页 4.2：高频大表用 BIGSERIAL；PRD 8.2 与架构页存在冲突，按架构页执行 |
| 时间类型 | `TIMESTAMP` | `TIMESTAMPTZ` | PRD 5.6 要求统一 UTC 存储，带时区类型可避免应用时区歧义 |
| 枚举 | `ENUM` | `VARCHAR(16~24) + CHECK` | 便于 MyBatis-Plus/JPA 映射与增量演进 |
| BOM 子件 | `items JSONB` 或独立表 | `bom_item` 为事实来源 + `bom.items` 快照 | PRD 允许二选一；两者保留以兼顾查询与渲染，服务层负责同步 |
| 工艺路线 | `nodes/edges JSONB` | `routing_node` / `routing_edge` 为事实来源 + JSONB 快照 | 同上；环检测由服务层执行 |
| 策略规则 | `initial_rules/optimization_rules JSONB` | 保留 JSONB + `strategy_rule` 结构化明细 | 便于按权重/规则类型查询与校验 |
| 数量精度 | `DECIMAL(18,4)` | BOM 单件用量 `NUMERIC(18,6)`，其余 `NUMERIC(18,4)` | 单件用量常小于 0.0001，保留 6 位小数 |
| `score_impact` | VARCHAR(64) | VARCHAR(64) 保留 | 可存 "+120 / -30s" 等描述；如需数值排序，v0.2 增加 `score_impact_value` |
| 未纳入表 | `agent_event` / `agent_decision` / `constraint_dsl` / `setup_matrix` / `sys_config` / `webhook_*` | 本 P0-05 不创建 | 任务范围只要求 10 核心表 + 指定配套表；这些表在 M2/M3 模块任务中新增迁移，见第 9 节风险 |

---

## 9. 已知缺口与后续迁移建议

1. **Agent 域表缺失**：`agent_event`、`agent_decision`、`constraint_dsl` 属 M2（P0-05 范围外），M2 任务应新增 `V2__agent.sql`，并在本文件同步更新。
2. **换型矩阵缺失**：`setup_matrix_entry`（SMT/储能核心约束）在 FR-MD-006，建议 `V3__setup_matrix.sql`。
3. **系统配置/字典缺失**：`sys_config`、`dict_item` 可在系统管理模块任务中补充。
4. **Webhook/集成日志缺失**：`webhook_config` / `webhook_log` 属集成模块。
5. **计划版本缺失**：FR-GT-005 的 `plan_version`（发布快照/回滚）建议以 `V4__plan_version.sql` 引入，`task` 增加 `plan_version_id`。
6. **替代料**：PRD BR-MD-001-06 的物料级替代关系未单独建表（当前仅 BOM 行内 `substitutes`）；若需要全局替代料查询，后续补 `material_substitute`。
7. **审计哈希链**：PRD 二期要求，当前 `audit_log` 未加哈希列。
8. **分区**：`task` / `audit_log` 未来按 `created_at` 或 `tenant_id` 分区（PRD 三期），当前为普通表。

---

## 10. 空库验证

### 10.1 Docker（推荐，postgres:15）

```powershell
docker run --rm -d --name aps-pg15 -e POSTGRES_PASSWORD=postgres -p 55432:5432 postgres:15
# 等待就绪
docker exec aps-pg15 pg_isready -U postgres
docker exec aps-pg15 psql -U postgres -c "CREATE DATABASE aps"
Get-Content -Raw db\migration\V1__init.sql |
  docker exec -i aps-pg15 psql -U postgres -d aps -v ON_ERROR_STOP=1 -f -
# 校验表数量（应为 28）
docker exec aps-pg15 psql -U postgres -d aps -c "select count(*) from information_schema.tables where table_schema='public'"
docker rm -f aps-pg15
```

### 10.2 本机 psql

```powershell
createdb -h localhost -U postgres aps_empty
psql -h localhost -U postgres -d aps_empty -v ON_ERROR_STOP=1 -f db/migration/V1__init.sql
```

预期：`ON_ERROR_STOP=1` 下零错误；`information_schema.tables` 中 `public` schema 表数为 **28**；`pg_extension` 含 `pgcrypto`、`pg_trgm`。
