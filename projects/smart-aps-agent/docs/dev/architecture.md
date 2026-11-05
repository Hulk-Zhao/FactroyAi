# 架构说明（开发者文档）

> 适用版本：`0.1.0-SNAPSHOT`（Java 21 / Spring Boot 3.3.13 / MyBatis-Plus 3.5.9 / PostgreSQL / Vue 3.5）
> 验证口径：本文按 2026-11-04 工作区代码逐类核对；仓库当前有其它车道未提交的改动，若与后续提交不一致，以代码为准。
> 文中 `路径` 均相对仓库根目录。

## 1. 总体形态

系统是**模块化单体**：一个 Spring Boot 进程（`aps-admin`）承载 REST API、求解任务执行与数据库访问；前端 `aps-frontend` 独立构建，通过 HTTP 与 STOMP 访问后端。

求解器不是独立微服务，而是编译期装配到 `aps-admin` 的 JAR（`solver-timefold` / `solver-ortools`），服务层仅依赖中立的 `solver-api` 抽象。

```
aps-frontend (Vue3 + Vite + Element Plus + Pinia + Axios)
        │  HTTP /api/v1/**  +  STOMP /ws
        ▼
aps-admin (Spring Boot 启动类 + 组合根)
        └── aps-web (Controller / Filter / Interceptor / Advice / WebSocket)
              └── aps-service (业务编排)
                    ├── aps-infrastructure (MyBatis-Plus 持久化实现)
                    │     └── aps-domain (领域枚举/模型)
                    │           └── aps-common (响应/异常/常量/租户/Trace)
                    ├── solver-api (引擎中立 DTO 与 SolverAdapter)
                    └── solver-dsl (约束 DSL 解析/校验/编译)
                          └── solver-api

aps-admin 额外依赖 solver-timefold / solver-ortools（仅组合根可见引擎实现）
```

## 2. 模块职责

| 模块 | 职责 | 关键类 / 文件 |
|---|---|---|
| `aps-common` | 统一响应、分页、异常与错误码、常量、租户上下文、Trace、幂等存储抽象 | `common/api/Result.java`、`common/api/PageQuery.java`、`common/api/PageResult.java`、`common/exception/ErrorCode.java`、`common/exception/BizException.java`、`common/tenant/TenantContext.java`、`common/trace/TraceContext.java`、`common/idempotency/IdempotencyStore.java`、`common/constant/ApsConstants.java` |
| `aps-domain` | 领域枚举与领域模型（无 Spring 依赖） | `domain/model/OrderStatus.java`、`domain/model/FreezeStatus.java`、`domain/masterdata/material/MaterialType.java`、`domain/masterdata/resource/CapacityMode.java` |
| `aps-infrastructure` | MyBatis-Plus 配置、实体、Mapper、Repository、JSONB 处理、租户与审计字段填充 | `persistence/config/MybatisPlusConfiguration.java`、`persistence/config/ApsTenantLineHandler.java`、`persistence/config/ApsTenantFilterProperties.java`、`persistence/config/ApsMetaObjectHandler.java` |
| `aps-solver/solver-api` | 求解器中立契约：问题/结果 DTO、适配器接口、HTTP 信封映射 | `solver/api/SolverAdapter.java`、`solver/api/ScheduleProblem.java`、`solver/api/ScheduleSolution.java`、`solver/api/SolveRequest.java`、`solver/api/SolveEnvelopeMapper.java`、`solver/api/task/SolveTaskDetail.java` |
| `aps-solver/solver-dsl` | 约束 YAML DSL 的解析、校验、序列化、编译结果模型 | `solver/dsl/ConstraintDslParser.java`、`solver/dsl/ConstraintDslCompiler.java`、`solver/dsl/ConstraintDslSerializer.java`、`solver/dsl/RuleCode.java`、`solver/dsl/DslErrorCodes.java`、`solver/dsl/DslValidationReport.java` |
| `aps-solver/solver-timefold` | Timefold 规划模型、约束流与引擎适配器（唯一允许出现 Timefold 类型的模块） | `solver/timefold/TimefoldSolverAdapter.java`、`solver/timefold/TimefoldModelBuilder.java`、`solver/timefold/constraint/ApsConstraintProvider.java`、`solver/timefold/constraint/ConstraintCodes.java` |
| `aps-solver/solver-ortools` | OR-Tools CP-SAT 适配器（当前为预留实现，`isAvailable()` 固定 `false`） | `solver/ortools/OrtoolsSolverAdapter.java` |
| `aps-service` | 业务编排：排产、主数据、策略 DSL、导入导出、RBAC、计划版本、审计、MRP/库存、通知派工 | `service/schedule/ScheduleService.java`、`service/schedule/SolveTaskLifecycleService.java`、`service/schedule/ScheduleProblemAssembler.java`、`service/schedule/ScheduleResultWriter.java`、`service/schedule/EngineRouter.java`、`service/strategy/ConstraintDslService.java`、`service/importexport/ImportEntity.java` |
| `aps-web` | REST Controller、认证/限流/链路过滤器、权限与幂等拦截器、异常转译、WebSocket 配置 | `web/filter/TraceIdFilter.java`、`web/filter/ApiSecurityFilter.java`、`web/filter/RateLimitFilter.java`、`web/security/PermissionInterceptor.java`、`web/idempotency/IdempotencyInterceptor.java`、`web/advice/RestExceptionHandler.java`、`web/config/WebMvcConfiguration.java` |
| `aps-admin` | 启动入口、运行时装配（数据源、Flyway、求解器 Bean、配置） | `admin/ApsAdminApplication.java`、`admin/config/SolverConfiguration.java`、`admin/src/main/resources/application.yml`、`application-dev.yml`、`application-prod.yml` |
| `aps-frontend` | Vue 3 单页应用；**不参与 Maven reactor**，独立 `npm` 构建 | `aps-frontend/package.json`、`aps-frontend/src/api/**`、`aps-frontend/src/views/**` |

## 3. 依赖方向与边界

### 3.1 实际依赖（以各模块 `pom.xml` 为准）

- `aps-common`：无内部依赖。
- `aps-domain` → `aps-common`。
- `aps-infrastructure` → `aps-common`、`aps-domain`。
- `aps-service` → `aps-common`、`aps-domain`、`aps-infrastructure`、`solver-api`、`solver-dsl`。
- `aps-web` → `aps-common`、`aps-domain`、`aps-service`、`aps-infrastructure`（直接依赖，用于少数 Controller 访问持久化实体/仓库）。
- `aps-admin` → `aps-web`、`solver-timefold`、`solver-ortools`。
- `aps-solver/solver-dsl` → `solver-api`。
- `solver-timefold` / `solver-ortools` → `solver-api`。

### 3.2 边界约定

1. **Timefold 类型只能出现在 `solver-timefold`**；`aps-service` 只通过 `SolverAdapter` 接口调用。
2. **引擎实现由组合根注入**：`aps-service` 的编译期依赖不含 `solver-timefold`/`solver-ortools`，运行时由 `aps-admin` 放入 classpath；`ScheduleService` 构造注入 `List<SolverAdapter>` 并按 `engine.code()` 建索引。
3. `aps-web` 直接依赖 `aps-infrastructure` 是已存在的例外（`RoutingController` 等直接使用实体/仓库）。新增业务代码应优先走 `aps-service`，不要扩大该例外。
4. 数据库迁移脚本位于仓库根 `db/migration`，由 `aps-admin` 的 `pom.xml` 以资源方式复制到 classpath，再由 Flyway 执行（`spring.flyway`）。

## 4. 一次求解请求的完整调用链

以 `POST /api/v1/solve-tasks` 为例。

### 4.1 Web 入站

1. `TraceIdFilter`（`@Order(HIGHEST_PRECEDENCE)`，`aps-web/.../filter/TraceIdFilter.java`）
   - 读取/校验 `X-Trace-Id`（8–64 位安全字符），否则生成 32 位无连字符 UUID；
   - 写入 `TraceContext`、MDC（traceId、tenantId），并把 traceId 回写响应头 `X-Trace-Id`；
   - **注意**：这里的 `tenantId` 仅来自 `X-Tenant-Id` 并写入 MDC/日志，**不写入 `TenantContext`**。
2. `ApiSecurityFilter`（`HIGHEST_PRECEDENCE + 10`，`filter/ApiSecurityFilter.java`）
   - 跳过 `OPTIONS`、`GET /api/v1/health`、`POST /api/v1/auth/login`；
   - `prod`：强制 `Authorization: Bearer <JWT>`，校验 HS256 与 `APS_USER` 角色；成功后写入 `ApsSecurityContext`、`AuditContext`、`TenantContext`（租户来自 JWT claim `tenantId`）；
   - `dev`：写端点强制认证；支持 `X-API-Key`（需显式开启且 `dev` profile）与 Bearer；读端点无凭证可匿名；
   - `off`：全放行，只能显式配置。
   - `finally` 清理 `AuditContext`、`TenantContext`、`ApsSecurityContext`。
3. `RateLimitFilter`（`HIGHEST_PRECEDENCE + 20`）
   - 默认启用，单实例固定窗口 100 次 / 60 秒（`aps.security.rate-limit.*`），超限返回 HTTP 429。
4. `PermissionInterceptor`（`WebMvcConfiguration#addInterceptors`，order=0）
   - 作用于 `/api/**`，读取方法/类上的 `@RequiresPermission`，经 `PermissionService` 校验；`POST /solve-tasks` 需要 `SE_SOLVE`。
5. `IdempotencyInterceptor`（order=1）
   - 作用于 `/api/**`，排除 `/api/v1/plans/*/dispatch`（派工走确定性 `dispatchId` + outbox 唯一索引，不采用“重复键返回 409”的全局语义）。

### 4.2 控制器与服务层

6. `SolveTaskController#submit`（`aps-web/.../controller/SolveTaskController.java`）
   - 入参 `SolveRequest` 与可选 `Idempotency-Key`；
   - 调用 `SolveTaskLifecycleService#submit`，返回 `Result<SolveTaskDetail>`。
7. `SolveTaskLifecycleService#submit`（`aps-service/.../schedule/SolveTaskLifecycleService.java`）
   - 归一化幂等键（求解任务最多 64 位）；按 `(tenantId, idempotencyKey)` 查重，命中则直接返回既有任务；
   - 调用 `ScheduleService#route(request)` 解析引擎与场景；
   - 写入 `solve_task`（`PENDING`），重复键竞争抛 `40901`；场景外键不存在映射 `40400`；
   - 发布 STOMP 进度；
   - `aps.solve.async-enabled` 为 `false` 时同步执行 `runSynchronously` 并返回终态详情；否则提交到**单工作线程 + 有界队列**（默认容量 32），返回 `PENDING/RUNNING` 详情。队列满时任务直接落 `FAILED`，`errorCode=SOLVER_UNAVAILABLE`。
8. `executeTask(RunningTask, SolveRequest)`
   - 恢复 `TenantContext.setTenantId(run.tenantId)`（异步线程必须，结束时 `clear()`）；
   - 调用 `ScheduleService#execute(config, problemInput)`。

### 4.3 求解编排（`ScheduleService`）

9. `ScheduleProblemAssembler#assemble(...)`
   - 读取已确认订单（未显式传 `orderNos` 时取场景内全部 `CONFIRMED`）；
   - 读取 ACTIVE 工艺路线/节点/依赖，构建 `OperationDependency`（FS/SS/FF）；
   - 读取资源、资源组、日历、班次、工序模板、冻结分配；
   - 计算工时/换型，产出中立 `ScheduleProblem` 与 `metrics`（`calendarFallback` / `dependencyFallback` / `setupMatrixFallback` 等）；
   - `requireExistingOrderNos` 校验订单存在且非 `CANCELLED`（`40400/40000` 语义见实现）。
10. `ScheduleService#execute`
    - `solverAdapter = adapters.get(config.engineCode())`，缺失抛 `50010 SOLVER_NOT_AVAILABLE`；
    - 调用 `SolverAdapter#solve(problem, config)`；对 `TimeoutException`/`RuntimeException` 按 `aps.solve.fallback-enabled` 决定抛错还是**标记降级**（见 5.3）；
    - 结果成功时调用 `ScheduleResultWriter#persist(...)`。
11. `TimefoldSolverAdapter#solve`（`solver-timefold/.../TimefoldSolverAdapter.java`）
    - `TimefoldModelBuilder#build(problem)` 生成 `SchedulePlan`；
    - `SolverFactory` + `ApsConstraintProvider`（9 个硬约束：`UNASSIGNED_TASK` 及资源冲突、工序顺序、日历、冻结等）；
    - `solve()` 返回 `ScheduleSolution`，含 assignments、score、metrics。
12. `ScheduleResultWriter#persist`（`Service` 层事务）
    - 先检查场景内是否存在 `FROZEN/IN_PROGRESS` 任务，存在则 `40903`（禁止覆盖已执行/已冻结任务）；
    - 删除同场景旧 `task`/`constraint_justification`（仅可重排的草稿态），写新 `task` 行与逐任务 `constraint_justification`；
    - 写/更新 `plan_version` 与 `solve_task.result_task_id`、分数、指标（JSONB）。
13. `SolveTaskLifecycleService#finish...`
    - 任务落 `SOLVED/FAILED/TIMEOUT/CANCELLED`，进度 1.0，写入 `elapsedMs`、`errorCode`、`metrics`；
    - 通过 STOMP 推送终态，前端也可轮询 `GET /api/v1/solve-tasks/{id}`。

### 4.4 关键数据表

`orders`、`material`、`bom`/`bom_item`、`routing`/`routing_node`/`routing_edge`、`resource`/`resource_group`、`calendar`（班次在 `shifts` JSONB 中，没有独立 `calendar_shift` 表）、`operation_template`、`sandbox`、`strategy`/`strategy_rule`、`solve_task`、`task`、`constraint_justification`、`plan_version`（V3）、`constraint_dsl`（V4）、`inventory`（V2）、`audit_log`、`notification_outbox`（V11）、`import_job`（V12）。表结构以 `db/migration/V1__init.sql` 与后续迁移为准。

## 5. 求解引擎路由与降级

### 5.1 路由入口

`EngineRouter#resolve(SolveRequest)`（`aps-service/.../schedule/EngineRouter.java`）。解析优先级：**请求 `engine` → 策略 JSON `optimizationRules.engine`（缺失再读 `initialRules.engine`）→ 系统默认 `timefold`**。

- `null/空白/timefold` → `TIMEFOLD`；路由层**不校验** Timefold 可用性，缺失适配器由 `ScheduleService#execute` 抛 `50010`。
- `ortools` → 仅当 `aps.solve.engine.ortools-enabled=true` 时才保留 `ORTOOLS`；否则：
  - `fallback-enabled=true` → `effectiveEngine=TIMEFOLD`，`fallback=true`，`fallbackReason=SOLVER_UNAVAILABLE`；
  - `fallback-enabled=false` → 仍返回 `ORTOOLS`，随后执行时抛 `50010 SOLVER_NOT_AVAILABLE`。
- 未知引擎编码 → `400/40000`，`message` 列出可选值 `timefold, ortools`。

### 5.2 引擎可用性与软约束权重来源

- Timefold：实现完整，`isAvailable()` 为 `true`。
- OR-Tools：`OrtoolsSolverAdapter#isAvailable()` 固定 `false`，`solve(...)` 抛 `UnsupportedOperationException`；`aps.solve.engine.ortools-enabled` 在 `application.yml` 中**未配置**（默认 `false`）。
- **软约束权重来源**：`TimefoldSolverAdapter#resolveSoftWeights` 读取 `SolveConfig.parameters.softWeights`。`ScheduleService#toConfig` 在传入 `strategyId` 时从 `strategy.rules` 解析 8 条 `SOFT` 规则（合计必须 100）并写入该参数；请求也可直接传 `parameters.softWeights` 覆盖/启用。两者都没有时，适配器显式 `SoftConstraintWeights.disable()`，**仅硬约束**。
- **约束 DSL 当前不是求解输入**：`constraint_dsl.compiled_spec` 只被校验/存储/激活，`ScheduleService` 未读取激活的 DSL 版本。DSL 与 `strategy.rules` 是两套数据，后续需要一条“DSL → 策略规则/求解权重”的接线（见交付报告“不一致”）。

### 5.3 运行时降级（重要语义）

`ScheduleService#execute` 捕获引擎 `TimeoutException`/`RuntimeException` 后：

- `aps.solve.fallback-enabled=true`：**不重新求解**，只在 `metrics` 中写入 `engineFallback=true`、`fallbackMode=HARD_ONLY`、`degraded=true`，任务仍按已有解成功或按超时失败；
- `aps.solve.fallback-enabled=false`：直接抛 `50010/500`。

因此“降级”是**状态标记**，不是“换引擎重跑”。`SolveTaskDetail.degraded` 即 `metrics.engineFallback=true`。

### 5.4 重试

`SolveTaskLifecycleService` 支持最多 1 次重试（`aps.solve.retry.max-retries`，默认 1；间隔 `aps.solve.retry.delay-seconds`，默认 30 秒），仅对 `ENGINE_ERROR / TIMEOUT / SOLVER_UNAVAILABLE` 生效，第二次仍失败即终态。

## 6. 租户隔离机制

1. **上下文**：`TenantContext` 是 `ThreadLocal<UUID>`，未设置时回落默认租户 `00000000-0000-0000-0000-000000000001`（与 V1 种子/契约 C5 一致）。
2. **写入来源**：`prod` JWT 的 claim `tenantId` 由 `ApiSecurityFilter#applyJwt` 写入；`dev` Bearer 同样；`dev` API Key **不设置**，因此落到默认租户；异步执行时由 `RunningTask.tenantId` 在 worker 线程恢复。
3. **SQL 注入**：`MybatisPlusConfiguration` 注册拦截器链，顺序为 **多租户 → 分页 → 乐观锁**：
   - `TenantLineInnerInterceptor` + `ApsTenantLineHandler`：INSERT 自动补 `tenant_id`，SELECT/UPDATE/DELETE 自动加 `tenant_id = ?`；
   - 忽略表：`tenant`、`role_permission`、`user_role`（DDL 无 `tenant_id`）；
   - `PaginationInnerInterceptor` 上限 `ApsConstants.MAX_PAGE_SIZE`（200）；
   - `OptimisticLockerInnerInterceptor` 处理 `version`。
4. **自动填充**：`ApsMetaObjectHandler` 在 INSERT 时填 `tenant_id/created_at/updated_at`，UPDATE 刷新 `updated_at`；时间统一 UTC `OffsetDateTime`。
5. **开关**：`aps.tenant.filter.enabled`（默认 `true`）可关闭所有表谓词，仅限单租户迁移/排障，启动会打 WARN。
6. **已知边界（与契约描述不一致，见交付报告）**：
   - `X-Tenant-Id` 只进 MDC/日志，**不参与数据过滤**；真正生效的是 JWT claim；
   - 全局幂等键的作用域也取 MDC 的 `X-Tenant-Id`（缺省为默认租户），与 `TenantContext` 的 JWT 租户不是同一来源。

## 7. 并发、幂等与一致性

- **求解并发**：单工作线程 + 有界队列；Timefold 适配器另有进程级 `ReentrantLock`，覆盖 `activate → 建工厂 → solve → disable`，因此**全进程同一时刻最多 1 个求解会话**。多实例部署时该互斥只在单进程内生效。
- **幂等两层**：
  1. `IdempotencyInterceptor`：对 `/api/**` 写请求（不含 dispatch）校验 `Idempotency-Key` 8–128 位；重复键返回 `40900`（**不回放首次响应**），TTL 24 小时（`ApsConstants.IDEMPOTENCY_TTL`），存储为进程内 `InMemoryIdempotencyStore`（重启失效）。
  2. `SolveTaskLifecycleService`：`Idempotency-Key` 最长 64 位，`solve_task` 唯一约束保证同键返回既有任务（与全局拦截器语义不同）。
- **乐观锁**：实体 `version` 字段 + `OptimisticLockerInnerInterceptor`；冲突统一 `40901`。
- **任务冻结**：`FROZEN/IN_PROGRESS` 任务禁止被新解覆盖（`40903`），`ScheduleResultWriter` 在删除/重写前强校验。

## 8. 配置与启动

关键配置（`aps-admin/src/main/resources/application.yml`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `aps.security.mode` | `prod` | `prod/dev/off`；`off` 必须显式 |
| `aps.security.jwt.secret` | 空 | 无默认值，缺失启动失败；至少 32 字节 |
| `aps.security.jwt.ttl-seconds` | `28800` | 8 小时 |
| `aps.security.jwt.required-role` | `APS_USER` | JWT 必须含该角色 |
| `aps.security.api-keys.write/read` | 空 | 仅 `dev` profile + 显式开启 `aps.security.dev.api-key-enabled` 时生效 |
| `aps.security.rate-limit.enabled/capacity/window-seconds` | `true/100/60` | 单实例固定窗口 |
| `aps.solve.progress-push` | `stomp` | `stomp`/`none` |
| `aps.solve.fallback-enabled` | `true` | 引擎异常降级标记 |
| `aps.solve.engine.ortools-enabled` | `false`（yml 未配置，代码默认） | OR-Tools 预留开关 |
| `aps.solve.queue-capacity` | `32` | 有界队列 |
| `aps.solve.timeout-grace-seconds` | `5` | 看门狗宽限 |
| `aps.solve.retry.max-retries/delay-seconds` | `1/30` | 最多 2 次尝试 |
| `aps.solve.async-enabled` | **代码默认 `true`；yml 未配置** | 见交付报告“不一致” |
| `aps.tenant.filter.enabled` | `true` | 租户谓词总开关 |
| `aps.audit.dev-user-id` | 空 | 仅 dev 生效 |

启动链路：`ApsAdminApplication` → Spring Boot 自动装配 → Flyway 执行 `db/migration` → MyBatis-Plus 拦截器 → `SolverConfiguration` 装配 `SolverAdapter` Bean → Web 过滤器/拦截器生效。

## 9. 扩展点

| 需求 | 落点 |
|---|---|
| 新增求解引擎 | 实现 `solver-api` 的 `SolverAdapter`；在 `aps-admin` 加模块依赖；在 `EngineRouter` 与 `SolverConfiguration` 注册编码/开关 |
| 新增软约束规则 | `solver-dsl` 的 `RuleCode` + `ConstraintDslParser` 校验 + `solver-timefold` 的 `ApsConstraintProvider` 打分映射；同步 DSL 文档 |
| 新增主数据实体 | `db/migration` 新迁移 + `aps-infrastructure` 实体/Mapper/Repository + `aps-service` 服务 + `aps-web` Controller；如需 Excel，再扩 `ImportEntity` |
| 替换幂等存储 | 实现 `IdempotencyStore` 并在 `WebMvcConfiguration` 替换 Bean（当前为进程内） |
| 前端接入 | `aps-frontend/src/api` 增加模块；认证走登录返回的 Bearer Token；进度可订阅 STOMP 或回退轮询 |

## 10. 待确认

- `aps.solve.async-enabled` 的部署预期：代码默认 `true`（异步），但 `application.yml` 注释称“不再提供部署开关”，Controller Javadoc 仍写“同步执行（默认）”。三者需产品/架构确认唯一口径。
- `solver-ortools` 的具体接入计划（当前仅预留）。
- `X-Tenant-Id` 是否应正式参与租户解析；现状与 `docs/api/conventions.md` §6 描述不一致。
