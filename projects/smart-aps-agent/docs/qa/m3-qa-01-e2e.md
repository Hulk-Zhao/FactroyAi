# M3-QA-01 集成 / E2E 验收报告

**用例数 6 / 通过 6 / 失败 0 / 新增用例类 3 / 全量 Skipped 0**

- 验收日期：2026-11-05
- 测试库：`aps_e2e`（自建，Flyway 全量迁移 V1–V12）
- 环境门控：`APS_TEST_DB_ENABLED=true`
- 端口：**未启动任何应用实例**，独立端口 18096 未被占用/使用；`aps_e2e` 验收后已 DROP，净变化 0
- 全量命令：`mvn -B -ntp test`（结果 `BUILD SUCCESS`，所有模块 `Skipped: 0`）

## 1. 用例清单

| 用例类 | 模块 | 覆盖流程 | 结果 |
|---|---|---|---|
| `CoreFlowE2ETest` | `aps-service`（`service.it`） | 主数据（物料/BOM/资源/日历/工艺路线）→ 订单 创建→确认→冻结→释放→开工 → 场景 → 求解 → `plan_version` 落库 → 导出 orders xlsx | 通过（1/1） |
| `PermissionE2ETest` | `aps-web`（`web.qa`） | `POST /api/v1/materials`：ADMIN 200 / PLANNER 403 / VIEWER 403 / 匿名 401；VIEWER `GET /api/v1/materials` 200 | 通过（4/4） |
| `ConstraintDslLifecycleE2ETest` | `aps-service`（`service.it`） | `activate` → `activate` 新版本 → `rollback`；同 code 唯一 ACTIVE、回滚后旧版本重新 ACTIVE、`compiledSpec` 完整 | 通过（1/1） |

累计 6 个用例、3 个新增用例类，全部真库执行（无 `Assumptions` 跳过、无 `@Disabled`）。

## 2. 三条 E2E 的关键断言与实测值

### 2.1 核心流程（`CoreFlowE2ETest`）

| 断言 | 期望 | 实测 |
|---|---|---|
| 订单状态机 | `DRAFT → CONFIRMED → FROZEN → RELEASED → IN_PROGRESS` | 全部命中 |
| `solve_task.status` | `SOLVED` | `SOLVED` |
| `solve_task.score_hard` | `0` | `0` |
| `metrics.unassignedTaskCount` | `0` | `0` |
| 场景内落库 `task` 行数 | 路线 2 节点 → 2 条 | `2` |
| `plan_version` | 同场景仅 1 条 `PUBLISHED` | `planNo=PLAN-20261105-001, version=1, status=PUBLISHED` |
| 计划快照任务数 | 与库内 `task` 一致 | `taskCount=2` |
| 导出 orders xlsx 数据行数 | 与库内订单数一致 | `rows=1`，`dbOrders=1` |

证据（`aps-service/target/qa-evidence/m1-qa-01-evidence.log`）：

```
solve: status=SOLVED, scoreHard=0, unassignedTaskCount=0, task=2
plan: planNo=PLAN-20261105-001, version=1, status=PUBLISHED, taskCount=2
export: orders xlsx rows=1, dbOrders=1
```

> 口径说明：`aps-service` 测试作用域不含 `solver-timefold`（且本次禁止改 pom），求解步骤使用测试内联的确定性 `SolverAdapter` 替身，返回「每道工序一条分配 + `0hard/0soft`」。因此本用例验证的是**求解生命周期、结果落库、计划发布与导出**的端到端链路，不覆盖 Timefold 求解质量；真实引擎覆盖由 `solver-timefold` 模块自测（全量运行 96 个用例通过）承担。

### 2.2 权限（`PermissionE2ETest`，真库 + 全量上下文 + MockMvc）

令牌由 `JwtTokenService` 按生产同款 HS256 直接签发；PLANNER/VIEWER 用户与 `user_role` 关系真实落库到 V1 种子角色。

| 角色 | 端点 | 期望 | 实测 HTTP | 实测 code | traceId（全量运行） |
|---|---|---|---|---|---|
| ADMIN | `POST /api/v1/materials` | 200 | 200 | `0` | `12f50e46d02c4d3b82a0752c0462e35a` |
| PLANNER | `POST /api/v1/materials` | 403 | 403 | `40300` | `4721a541a5ab45eba2775f503e841c6f` |
| VIEWER | `POST /api/v1/materials` | 403 | 403 | `40300` | `225d1a1f7b444afe940b247e2629fd9e` |
| VIEWER | `GET /api/v1/materials` | 200 | 200 | `0` | `2510fc5aa5ec441c98513106d98d731b` |
| 匿名（无 token） | `POST /api/v1/materials` | 401 | 401 | `40100` | `6c99e371924e44a6b55b3721147f1419` |

结论：`@RequiresPermission("MD_WRITE")` 由 `PermissionInterceptor` 真实拦截（PLANNER/VIEWER 无 `MD_WRITE` → 403/40300）；匿名写请求由 `ApiSecurityFilter` 在进入 MVC 前直接拒绝（401/40100）。

### 2.3 约束 DSL 发布回滚（`ConstraintDslLifecycleE2ETest`）

| 步骤 | 期望 | 实测 |
|---|---|---|
| 创建 v1 | `versionNo=1`、`DRAFT`、`compiledSpec=8` | 命中 |
| `activate(v1)` | `ACTIVE`，同 code ACTIVE 数 = 1 | 命中（DB 计数 1） |
| 创建 v2（改权重、总和仍 100） | `versionNo=2`、`DRAFT`、`compiledSpec=8` | 命中 |
| `activate(v2)` | v2 `ACTIVE`；v1 自动 `ROLLBACK`；ACTIVE 数仍 = 1 | 命中 |
| `rollback(v1)` | v1 重新 `ACTIVE`；v2 `ROLLBACK`；`rollbackOf=v2.id`；`compiledSpec` 8 条且 `jsonb_array_length(compiled_spec)=8` | 命中 |
| 唯一索引兜底 | 直接 `UPDATE constraint_dsl SET status='ACTIVE'` 写第二个 ACTIVE 必须被拒 | 抛 `DataIntegrityViolationException`，事后 ACTIVE 数仍 = 1 |

## 3. 缺陷清单

| # | 严重度 | 缺陷 / 观察 | 证据 | 是否阻塞 |
|---|---|---|---|---|
| 1 | 中（测试基建口径） | `aps-web` 既有 `SolveTaskRepositoryRealDbTest` 的 `RealDbTargetGuard` 白名单仅允许 `aps_test*` / `aps_m2_*`；当 `APS_TEST_DB_NAME=aps_e2e` 时其在 `@BeforeAll` 自中止，Surefire 记为 `Tests run: 0`（不计入 Skipped）。该真库验收类在本次统一库口径下未实际执行。 | 全量日志：`Running ...SolveTaskRepositoryRealDbTest` → `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0` | 否（既有测试守护策略与新库名口径冲突，非业务缺陷；建议后续统一守卫前缀） |
| 2 | 低（日志噪音） | 真库测试上下文二次触发 Flyway（Boot 自动配置、`classpath:db/migration`），日志出现 `ERROR ... Schema "public" has version 12, but no migration could be resolved in the configured locations !`；schema 实际已由 `RealDbEnvironment`（`filesystem:../db/migration`）迁移完成，启动与用例均正常。 | 全量日志 `DbMigrate` 行，紧随其后 `Schema "public" is up to date` | 否 |
| 3 | 低（编排约束，非缺陷） | 订单创建要求产品存在唯一 `ACTIVE` BOM，否则 422。E2E 必须先创建并激活 BOM 才能下单；属于既有业务规则，仅作为后续 E2E 编排约束记录。 | `CoreFlowE2ETest` 首次运行报 `产品 ... 缺少唯一 ACTIVE 的 BOM` | 否 |

本次未发现阻塞级业务缺陷；**未修改任何业务代码、pom、迁移脚本或 `docs/api/*`**。

## 4. 全量执行结果（模块级）

`APS_TEST_DB_ENABLED=true APS_TEST_DB_NAME=aps_e2e mvn -B -ntp test`

| 模块 | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| aps-common | 19 | 0 | 0 | 0 |
| aps-infrastructure | 34 | 0 | 0 | 0 |
| solver-api | 8 | 0 | 0 | 0 |
| solver-dsl | 19 | 0 | 0 | 0 |
| solver-timefold | 96 | 0 | 0 | 0 |
| aps-service（含 2 个新增用例） | 321 | 0 | 0 | 0 |
| aps-web（含 4 个新增用例） | 53 | 0 | 0 | 0 |
| aps-domain / solver-ortools / aps-admin | 0 | 0 | 0 | 0 |
| **合计** | **550** | **0** | **0** | **0** |

## 5. 怎么重跑（可直接复制）

PowerShell（Windows，仓库根目录 `D:\tools\smart-aps-agent`）：

```powershell
$env:JAVA_HOME='D:\tools\_aps-toolchain\jdk-21.0.13+9'; $env:PATH="$env:JAVA_HOME\bin;D:\tools\_aps-toolchain\apache-maven-3.9.16\bin;$env:PATH"; $env:PGPASSWORD='aps_dev_pwd'; & 'D:\tools\_aps-toolchain\pgsql\bin\psql.exe' -h 127.0.0.1 -p 55432 -U aps -d postgres -c "DROP DATABASE IF EXISTS aps_e2e;"; $env:APS_TEST_DB_ENABLED='true'; $env:APS_TEST_DB_NAME='aps_e2e'; mvn -B -ntp test
```

只跑这三条 E2E（快速迭代）：

```powershell
$env:JAVA_HOME='D:\tools\_aps-toolchain\jdk-21.0.13+9'; $env:PATH="$env:JAVA_HOME\bin;D:\tools\_aps-toolchain\apache-maven-3.9.16\bin;$env:PATH"; $env:APS_TEST_DB_ENABLED='true'; $env:APS_TEST_DB_NAME='aps_e2e'; mvn -B -ntp -pl aps-service "-Dtest=CoreFlowE2ETest,ConstraintDslLifecycleE2ETest" test; mvn -B -ntp -pl aps-web "-Dtest=PermissionE2ETest" test
```

> 说明：`aps_e2e` 不存在时测试基座会用维护库 `postgres` 自动建库并执行 Flyway 迁移；`aps_local*` 会被守卫直接拒绝，不要绕过。
