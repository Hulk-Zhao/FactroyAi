# M3-BE-04 多租户字段与过滤预留 — 交付报告

盘点表数 34 / 缺 tenant_id 的表 3 / 拦截器覆盖表 20 / 忽略表 3 / 裸 SQL 121 处调用点（19 个类，归并约 108 条语句，0 处缺谓词；其中 4 处关联表写入因表结构无该列而按设计不写 tenant_id）

> 说明：3 张缺 `tenant_id` 的表均为结构上不应有该列的表（租户根表 `tenant`、RBAC 关联表 `user_role` / `role_permission`），**不存在需要补列的业务表**。未新增 V13，未修改 V1–V7/V11/V12 中的任何一个字节。

---

## 1. 盘点清单（`information_schema.columns`）

扫描库：临时库 `aps_tenant_filter`（由 9 个既有迁移全量构建后扫描）。全部 31 个含 `tenant_id` 的列均为 `uuid` 且 `NOT NULL`。

| # | 表名 | tenant_id | 类型 | NOT NULL | tenant_id 索引 |
|---|------|-----------|------|----------|----------------|
| 1 | audit_log | YES | uuid | YES | idx_audit_log_tenant_created_action, idx_audit_log_user_created |
| 2 | bom | YES | uuid | YES | idx_bom_tenant_status, uq_bom_one_active_per_parent |
| 3 | bom_item | YES | uuid | YES | -（无 tenant_id 索引） |
| 4 | calendar | YES | uuid | YES | idx_calendar_tenant_type_range, uq_calendar_tenant_name_version |
| 5 | constraint_dsl | YES | uuid | YES | idx_constraint_dsl_tenant_code_status, uq_constraint_dsl_tenant_code_active, uq_constraint_dsl_tenant_code_version |
| 6 | constraint_justification | YES | uuid | YES | -（无 tenant_id 索引） |
| 7 | customer | YES | uuid | YES | idx_customer_tenant_priority, uq_customer_tenant_code |
| 8 | import_job | YES | uuid | YES | idx_import_job_tenant_created, uq_import_job_inflight |
| 9 | inventory | YES | uuid | YES | idx_inventory_tenant_material, uq_inventory_tenant_material_warehouse |
| 10 | inventory_transit | YES | uuid | YES | idx_inventory_transit_tenant_material_date |
| 11 | material | YES | uuid | YES | idx_material_tenant_type_category, uq_material_tenant_code |
| 12 | material_category | YES | uuid | YES | uq_material_category_tenant_code |
| 13 | notification_outbox | YES | uuid | YES | idx_notification_outbox_due, uq_notification_outbox_target |
| 14 | operation_template | YES | uuid | YES | uq_operation_template_code_version |
| 15 | orders | YES | uuid | YES | idx_orders_tenant_customer, idx_orders_tenant_product_due_date, idx_orders_tenant_status_due_date, uq_orders_tenant_order_no |
| 16 | permission | YES | uuid | YES | idx_permission_module, uq_permission_tenant_code |
| 17 | plan_version | YES | uuid | YES | idx_plan_version_published_at, idx_plan_version_tenant_sandbox_status, uq_plan_version_tenant_plan_no |
| 18 | resource | YES | uuid | YES | idx_resource_tenant_group, idx_resource_tenant_type, uq_resource_tenant_code |
| 19 | resource_group | YES | uuid | YES | uq_resource_group_tenant_code |
| 20 | role | YES | uuid | YES | uq_role_tenant_code |
| 21 | role_permission | **NO** | - | - | - |
| 22 | routing | YES | uuid | YES | idx_routing_tenant_status, uq_routing_one_active_per_product |
| 23 | routing_edge | YES | uuid | YES | -（无 tenant_id 索引） |
| 24 | routing_node | YES | uuid | YES | -（无 tenant_id 索引） |
| 25 | sandbox | YES | uuid | YES | idx_sandbox_tenant_type_expires, uq_sandbox_baseline |
| 26 | solve_task | YES | uuid | YES | uq_solve_task_idempotency, uq_solve_task_sandbox_idempotency |
| 27 | strategy | YES | uuid | YES | idx_strategy_tenant_effective, uq_strategy_tenant_name_version |
| 28 | strategy_rule | YES | uuid | YES | -（无 tenant_id 索引） |
| 29 | supplier | YES | uuid | YES | uq_supplier_tenant_code |
| 30 | task | YES | uuid | YES | -（无 tenant_id 索引） |
| 31 | task_adjustment | YES | uuid | YES | -（无 tenant_id 索引） |
| 32 | tenant | **NO** | - | - | - |
| 33 | user_role | **NO** | - | - | - |
| 34 | users | YES | uuid | YES | idx_users_email, idx_users_tenant_status, uq_users_tenant_username |

无 `tenant_id` 索引的表 9 张：`bom_item`、`constraint_justification`、`routing_edge`、`routing_node`、`strategy_rule`、`task`、`task_adjustment`、`operation_template`、`solve_task`。

---

## 2. 拦截器覆盖核对

### 2.1 生效表集合（20 张，MyBatis-Plus `@TableName`）

`bom`、`bom_item`、`calendar`、`material`、`material_category`、`inventory`、`inventory_transit`、`orders`、`operation_template`、`routing`、`routing_edge`、`routing_node`、`permission`、`role`、`users`、`resource`、`resource_group`、`constraint_dsl`、`strategy`、`strategy_rule`。

Mapper XML（`RoutingMapper.xml`、`RoutingReferenceMapper.xml`、`RoutingNodeMapper.xml`、`OperationTemplateMapper.xml`）中的表（`routing*`、`operation_template`、`material`、`task`、`orders`）均含 `tenant_id`，由 `TenantLineInnerInterceptor` 自动注入。

### 2.2 显式忽略表（3 张）

| 表 | 忽略理由 |
|----|----------|
| `tenant` | 租户根表，自身即租户标识，不存在 `tenant_id` 列；DDL 无该列，注入会导致 SQL 报错 |
| `role_permission` | RBAC 关联表，V1 起 DDL 即无 `tenant_id`；租户隔离通过父表 `role` 的租户校验保证 |
| `user_role` | RBAC 关联表，V1 起 DDL 即无 `tenant_id`；租户隔离通过父表 `users` 的租户校验保证 |

对应代码：`ApsTenantLineHandler.IGNORED_TABLES`。

### 2.3 绕过 MyBatis 的裸 `JdbcTemplate` SQL

共 **19 个类、121 处 `JdbcTemplate` 调用点**（归并约 108 条 SQL）。逐条核对结论：**0 处缺 `tenant_id` 谓词**。唯一不写 `tenant_id` 的是 4 条关联表语句（`user_role` / `role_permission` 的 DELETE/INSERT），因为这两张表没有该列，且其 DELETE 用 `EXISTS` 绑定父表租户、INSERT 前先校验父表归属。

| 类 | 涉及表 | 结论 |
|----|--------|------|
| `TaskAdjustmentRepository`（17 语句） | `task`、`orders`、`resource`、`task_adjustment` | 全部显式 `tenant_id`；INSERT 写 `tenant_id` |
| `RbacRepository`（16 语句） | `role`、`permission`、`users`、`user_role`、`role_permission` | 查/删均带租户或 `EXISTS` 守卫；2 条关联表 INSERT 按设计无该列 |
| `ScheduleRepository`（16 语句） | `orders`、`material`、`task`、`solve_task`、`resource`、`constraint_justification` | 主表全部带 `tenant_id`；跨表 JOIN 依赖主表谓词或全局唯一 `sandbox_id`（见风险 4） |
| `PlanVersionRepository`（10 语句） | `plan_version`、`task`、`orders` | 全部显式 `tenant_id` |
| `NotificationOutboxRepository`（8 语句） | `notification_outbox` | 全部显式 `tenant_id`（含 `FOR UPDATE SKIP LOCKED` 领取） |
| `ImportJobRepository`（8 语句） | `import_job` | 全部显式 `tenant_id` |
| `SandboxRepository`（7 语句） | `sandbox`、`task`、`orders` | 全部显式 `tenant_id` |
| `SolveTaskRepository`（6 语句） | `solve_task`、`sandbox` | 全部显式 `tenant_id` |
| `IncrementalSolveRepository`（4 语句） | `solve_task`、`order`、`task`、`plan_version` | 全部显式 `tenant_id` |
| `EntityDataExporter`（3 语句） | `customer`、`supplier`、`resource` | 全部显式 `tenant_id`（曾踩坑类已确认修复） |
| `AuditLogRepository`（3 语句） | `audit_log` | `WHERE tenant_id = ?` 为固定首条谓词 |
| `ConstraintJustificationRepository`（2 语句） | `constraint_justification` | 全部显式 `tenant_id` |
| `PlanCompareRepository`（1 语句） | `solve_task` | 显式 `tenant_id` |
| `OrderTaskFreezeRepository`（1 语句） | `task` | 显式 `tenant_id` |
| `CustomerImportHandler` / `SupplierImportHandler` / `MaterialImportHandler` / `StrategyImportHandler` / `OperationTemplateImportHandler`（共 6 语句） | `customer`、`supplier`、`material`、`strategy`、`operation_template`、`resource` | INSERT 写 `tenant_id`；查询显式 `tenant_id` |

---

## 3. 可启停开关实现

| 项 | 内容 |
|----|------|
| 配置项 | `aps.tenant.filter.enabled` |
| 默认值 | `true`（`ApsTenantFilterProperties.DEFAULT_ENABLED`） |
| 绑定方式 | `@ConfigurationProperties(prefix = "aps.tenant.filter")` + `@EnableConfigurationProperties`，**启动时绑定一次**；handler 每次 SQL 只读内存字段，不读环境变量 |
| 覆盖方式 | 环境变量 `APS_TENANT_FILTER_ENABLED=false`（Spring Boot relaxed binding）或 `-Daps.tenant.filter.enabled=false` |
| 关闭行为 | `ApsTenantLineHandler.ignoreTable()` 对**所有表**返回 `true`，MyBatis 侧无任何表残留谓词（含 INSERT 自动列注入一并停用） |
| 关闭告警 | 启动时打印一次 `WARN`：`租户过滤已关闭（aps.tenant.filter.enabled=false）：MyBatis 自动 tenant_id 谓词已全部停用；JdbcTemplate 显式谓词不受此开关控制，仅限单租户历史数据迁移/排障，请勿在正常业务环境使用` |
| 开启日志 | 启动时打印一次 `INFO`：`租户过滤已启用（aps.tenant.filter.enabled=true）：所有非忽略表自动注入 tenant_id 谓词` |

改动文件：

- `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/config/ApsTenantFilterProperties.java`（新增）
- `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/config/ApsTenantLineHandler.java`
- `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/config/MybatisPlusConfiguration.java`

未改 `docs/api/contract-freeze.md`、未改 `docs/api/openapi/*.yaml`、未改任何迁移脚本。

---

## 4. 开启 / 关闭两态证据

### 4.1 开启态（默认，`aps.tenant.filter.enabled=true`）

**全量回归**（`APS_TEST_DB_ENABLED=true`，独立库 `aps_tenant_filter`）：

```
[INFO] Tests run: 19,  Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 34,  Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 8,   Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 19,  Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 96,  Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 319, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 49,  Failures: 0, Errors: 0, Skipped: 0
TOTAL tests=544 failures=0 errors=0 skipped=0
[INFO] BUILD SUCCESS
```

**新增真库隔离测试**（`MaterialMapperTenantFilterRealDbTest`，直接构造 MyBatis-Plus `SqlSessionFactory`，不装载 Spring 上下文）：

- `enabledFilterHidesOtherTenantRowsAndInjectsPredicate`：租户 B 上下文用同编码查询只返回 1 条（B 自己），按主键 `selectById` 读租户 A 物料返回 `null`；断言实际下发 SQL 含 `tenant_id =`。
- `disabledFilterRemovesPredicateButSingleTenantDataStaysCorrect`：同一工厂/同一 properties 切到关闭后，同编码查询返回 2 条；断言实际 SQL 无 `tenant_id =`；切回租户 A 按主键读取本租户数据仍正确。
- `toggleOnLivePropertiesIsReadByHandlerWithoutEnvReread`：不重建 handler/工厂，仅切换内存 properties，谓词立即消失，证明不依赖每次请求读环境变量。

三测均 `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`。

**接口 + SQL 原文**（18099 实例，单租户默认租户）：

```
INFO ... MybatisPlusConfiguration - 租户过滤已启用（aps.tenant.filter.enabled=true）：所有非忽略表自动注入 tenant_id 谓词
DEBUG ... MaterialMapper.selectList_mpCount - ==>  Preparing: SELECT COUNT(*) AS total FROM material WHERE deleted = false AND (LOWER(code) LIKE LOWER(CONCAT('%', ?, '%'))) AND tenant_id = '00000000-0000-0000-0000-000000000001'
GET /api/v1/materials?code=QA-M3BE04-RT-20261104 -> total=1（仅默认租户行）
```

### 4.2 关闭态（`APS_TENANT_FILTER_ENABLED=false`）

启动告警原文：

```
2026-11-04T23:17:07.064+08:00 WARN  [main] [traceId=] [tenantId=] c.h.a.i.p.c.MybatisPlusConfiguration - 租户过滤已关闭（aps.tenant.filter.enabled=false）：MyBatis 自动 tenant_id 谓词已全部停用；JdbcTemplate 显式谓词不受此开关控制，仅限单租户历史数据迁移/排障，请勿在正常业务环境使用
```

同一接口 + SQL 原文（谓词消失）：

```
DEBUG ... MaterialMapper.selectList_mpCount - ==>  Preparing: SELECT COUNT(*) AS total FROM material WHERE deleted = false AND (LOWER(code) LIKE LOWER(CONCAT('%', ?, '%')))
GET /api/v1/materials?code=QA-M3BE04-RT-20261104 -> total=2（默认租户 + 另一租户同编码行均可见）
```

单租户数据仍正确：同一请求返回的默认租户行内容与开启态一致；真库测试 `disabledFilterRemovesPredicateButSingleTenantDataStaysCorrect` 额外验证了关闭态下按主键读取本租户数据正常。

---

## 5. 需要拍板的风险 / 决策项

1. **关闭开关只作用于 MyBatis 自动谓词，不作用于 `JdbcTemplate` 硬编码谓词。**
   14 张仅走 `JdbcTemplate` 的表（`task`、`solve_task`、`sandbox`、`plan_version`、`audit_log`、`import_job`、`notification_outbox`、`task_adjustment`、`constraint_justification`、`customer`、`supplier`、`tenant`、`user_role`、`role_permission`）里的 `tenant_id = ?` 是 SQL 常量，开关关闭后仍生效。
   在拦截器覆盖范围内行为 100% 一致（全表跳过）；但若验收口径要求"关闭后全库无任何租户谓词"，需另行改造 19 个仓库类/约 108 条 SQL（例如统一 `(? IS NULL OR tenant_id = ?)`），本卡未做，**需你决定是否作为后续卡**。

2. **9 张表无 `tenant_id` 索引**：`bom_item`、`constraint_justification`、`routing_edge`、`routing_node`、`strategy_rule`、`operation_template`、`task`、`task_adjustment`、`solve_task`。MVP 单租户数据量小无感，v1.0 SaaS 多租户后 `task`/`solve_task` 等高频表会出现跨租户扫描劣化。补索引需要新迁移（本卡按纪律未新增），建议在 v1.0 索引专项统一处理。

3. **`user_role` / `role_permission` 无 `tenant_id`**：租户隔离依赖父表校验（`EXISTS` + 先查父表归属），不是数据库级约束。若未来允许并发跨租户写同一用户/角色，存在理论越权窗口，建议 v1.0 评估是否补列或加复合唯一键。

4. **跨表 JOIN 的隔离依赖主表谓词或全局唯一键**：
   - `ScheduleRepository.findOrderGanttRows` 中 `task t` 未带 `t.tenant_id`，靠 `sandbox_id`（全局 UUID 唯一）间接隔离；
   - `findResourceOverlaps` 中 `t2` 未带 `t2.tenant_id`，同上靠 `sandbox_id`；
   - `findGanttTasks` 的 `orders`/`resource`/`operation_template` 依赖 `task` 主表谓词。
   当前不会跨租户，但属于"隐式不变量"。若未来允许 `sandbox_id` 跨租户复用，需补显式谓词。

5. **`tenant` 表本身不参与租户过滤**：租户管理接口必须由平台级权限而不是租户谓词保护，需确认 v1.0 的租户管理入口只对平台管理员开放。

---

## 6. 环境收尾

- 自起实例端口：`18099`（已停止，无残留监听）。
- 基线实例 `18085`：全程未连接、未重启，仍在原 pid 监听。
- 自建库 `aps_tenant_filter`：已 `DROP DATABASE`，净变化 0（创建 1 → 删除 1）。
- 未执行 commit / push；未执行 `mvn package`（回归用 `mvn test`，构建用 `mvn install -DskipTests`）。
