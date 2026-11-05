# M1 契约冻结（S0 契约守门）

> 状态：**冻结**（2026-11-04）
> 适用：M1 全部业务流（主数据、工艺订单、求解、测试部署）
> 事实来源：`mfg-aps-platform-PRD.md`、`db/migration/V1__init.sql`、`docs/db/schema.md`、`docs/api/conventions.md`、`docs/api/openapi/*.yaml`
> 责任：S0 只做契约冻结、依赖预置与收口，**不实现业务逻辑**，不发号以外的迁移。

本文件是 M1 期间跨流协作的唯一契约基线。业务流实现与本文件冲突时，以本文件为准；需要变更时走第 0.1 节的契约变更流程。

---

## 0. 冻结规则

### 0.1 契约变更流程（已确认）

1. 任何字段/路径/枚举/错误码变更，先提交「契约变更单」：说明变更点、影响流、兼容性（是否破坏 `/api/v1`）。
2. 破坏性变更（删除/重命名字段、改路径、改语义）**不得**在 M1 内直接落地，必须走 `/api/v2` 或延后。
3. 兼容性变更（新增可选字段、新增枚举值）由 S0 更新本文件 + `docs/api/openapi/*.yaml` 后，业务流才能引用。
4. 数据库迁移号段由 S0 统一分配，见第 2 节。

### 0.2 契约占位锁定通知（已确认）

- 业务流**不得编辑** `aps-web/src/main/java/com/hengce/aps/web/controller/contract/**`。
- 业务流**不得编辑** `docs/api/openapi/*.yaml`。
- 上述目录/文件仅由 S0 维护；字段变更走契约变更单。
- 已实现的业务 Controller（如 `OrderController`、`SolveTaskController`）由对应业务流维护，但必须与本文件及静态 YAML 保持一致。

### 0.3 前端分工（已确认）

| 角色 | 范围 |
|---|---|
| FE1 | `aps-frontend/src/views/{orders,gantt}`、`aps-frontend/src/components/gantt`、`router/api/stores/types` 基座 |
| FE2 | `aps-frontend/src/views/{material,bom,resource,calendar,routing}` 与各自的 `src/api/<domain>.ts` |

- `src/types/api.ts`、`src/api/http.ts`、`src/api/index.ts`、路由与全局 store 属于基座，由 FE1 维护；FE2 只新增/修改各自 domain 的 api 文件，跨域类型变更提给 FE1。
- 后端契约字段变更仍由 S0 发单，前端不得先行改类型。

### 0.4 版本决策（已确认）

- Timefold 以骨架现有 **2.8.0** 为准；PRD 中「1.35+」视为最低要求，不回退、不升级大版本。
- MyBatis-Plus 选 **3.5.9**：该版本按 Spring Boot 3.3.4 构建，是 3.3 线最后一个官方对齐版本；3.5.10+ 已转向 Boot 3.4/3.5。
- Testcontainers 选 **1.21.4**（1.x 最新线）；2.x 为破坏性大版本，M1 不跟随。
- Spring Boot 3.3.13 已管理 actuator / validation 版本，根 pom 的 `dependencyManagement` 仅做显式锁定，不改插件配置。

### 0.5 制品级验收规则（2026-11-04 冻结）

**涉及行为变化的验收，必须在“当前源码构建的制品”上执行**。用旧制品测出的结论必须标注构建时间与源码时间差异，不得表述为“现状缺陷”；旧制品与当前源码存在行为差异时，该结论只能记为“旧制品观测”，不得进入缺陷清单。真缺陷须在当前制品上复现后才成立。

---

## 1. 冻结结论速览

| 编号 | 结论 | 状态 |
|---|---|---|
| C1 | 保留现有 `SolveRequest/SolveResult` 作为 HTTP 过渡信封；求解内部改用中立 DTO `ScheduleProblem/ScheduleSolution/TaskAssignment/SolveConfig/SolveMetrics`，映射见 3 节。**C20 起 `POST /solve-tasks` 改为单一返回 `SolveTaskDetail`，`SolveResult` 信封与 `SUCCESS` 一并废弃** | 已确认（C20 修订） |
| C2 | 产能/工时按 `capacity_mode` 三式换算，`batch_capacity` 空按 1 兜底，三种模式均追加 `setup_seconds` | **默认假设，待产品最终确认** |
| C3 | `task.operation_index = routing_node.seq_no`；M1 不加 `task.routing_node_id` | 已确认 |
| C4 | `GET /api/v1/gantt/resource` 正式归求解流（S3）实现 | 已确认 |
| C5 | 默认场景 `sandboxId=00000000-0000-0000-0000-000000000401`；`tenantId` 由应用层注入；`solve_task.strategy_id` 允许空 | 已确认 |
| C6 | 路径 ID：materials/orders/resources 用 code，boms/routings/calendars 用 UUID，task/solve-task 用 bigint | 已确认 |
| C7 | 统一返回体、错误码、时间格式沿用既有实现，不另造 | 已确认 |
| C8 | 订单确认校验：产品存在、BOM 唯一 ACTIVE、routing 唯一 ACTIVE；客户黑名单 M1 跳过 | 已确认 |
| C9 | 枚举字典统一从 schema CHECK 导出，见 `docs/api/enums.md` | 已确认 |
| C10 | 列表筛选增量：materials `code/name` OR、boms `parentMaterialCode/status`、resources 与 calendars 同类筛选补齐 | 已确认 |
| C11 | 单条详情增量：新增 `GET /boms/{bomId}`，并补齐 resources/calendars 单条详情 | 已确认 |
| C12 | 求解问题能力补全：资源可用时段、工序依赖与滞后、换型矩阵；辅助资源与冻结任务语义补注 | 已确认（矩阵 M1 降级） |
| C13 | 订单更新与动作端点定稿：保留 `PUT /orders/{orderNo}`、`PUT /confirm`、`PUT /freeze`，解冻走 body，不设独立 `/unfreeze` | 已确认 |
| C14 | 资源与日历契约补全：详情/更新/停用端点、资源响应暴露 id、calendarVersion 改 INTEGER、不引入 status、可用时段 M1 不涉及 | 已确认 |
| C15 | 工艺路线图/工序模版端点、routings 筛选参数、路由节点资源引用统一 `primaryResourceId`（UUID）；`overrides` 三态（缺键 / 显式 `null` / UUID），见 20.3.1 | 已确认 |
| C16 | 工序模版详情统一 `{id}`（UUID），禁止 `{code}` 详情路径；新增 `process` OpenAPI 分组 | 已确认 |
| C17 | 登记 `DELETE /boms/{bomId}`、`DELETE /calendars/{id}`；同步已实现端点的 `x-implementation-status` | 已确认 |
| C19 | M2 策略规则与受控 DSL：8 条软约束数组 + `constraint:` 兼容别名，见 24.1 | 已确认（M2） |
| C20 | M2 异步求解生命周期、取消与 STOMP 进度，见 24.2 | 已确认（M2） |
| C21 | M2 增量重排与 `lockType/pinnedWindow`，见 24.3 | 已确认（M2） |
| C22 | M2 甘特人工调整（MOVE/LOCK/FREEZE/UNFREEZE）与撤销重做，见 24.4 | 已确认（M2） |
| C23 | M2 订单优先级、四态冻结与释放/开工自动冻结，见 24.5 | 已确认（M2） |
| C24 | M2 计划版本发布/回滚与快照，见 24.6 | 已确认（M2） |
| C25 | M2 库存落表 + `PUT /inventory` + MRP 齐套状态，见 24.7 | 已确认（M2） |
| C26 | M2 Excel 导入导出通用组件，见 24.8 | 已确认（M2） |
| C27 | M2 REST 认证分级、限流、幂等与新增错误码，见 24.9 | 已确认（M2） |
| C28 | M2 引擎路由与超时/异常降级标记，见 24.10 | 已确认（M2） |
| C29 | M2 订单甘特读接口，见 24.11 | 已确认（M2） |

---

## 2. 数据库迁移号段（已确认）

**M1 默认零迁移。** 所有 M1 需求优先在 `V1__init.sql` 已有结构内实现；不得修改 V1。

| 号段 | 归属流 | 说明 |
|---|---|---|
| V2–V3 | 主数据流 | 物料、BOM、资源、日历、客户、供应商 |
| V4–V5 | 工艺订单流 | 工序模板、工艺路线、订单 |
| V6–V7 | 求解流 | 求解任务、排产任务、场景、甘特 |
| V8–V9 | 测试部署流 | 仅限测试夹具、索引/约束修正 |

规则：

1. 只有 S0 发号；业务流不得自行新增 `V*.sql`。
2. 申请时说明「流 / 目的 / 是否改列 / 是否回填」，S0 分配具体号段。
3. 迁移文件命名 `V{n}__{snake_case_description}.sql`，放在 `db/migration/`。
4. 禁止改 `db/migration/V1__init.sql`（含注释、种子数据、CHECK 约束）。
5. 号段用尽或需要跨流共享时，由 S0 重新分配，不得抢占他流号段。
6. `aps-admin` 会把 `db/migration` 原样打进 classpath（见 `aps-admin/pom.xml` 的 resources 配置），迁移文件不要移动目录。
7. **任何已应用过的迁移文件连注释都不许改**：Flyway 校验和包含注释与空行，改一个字就会让共享库启动失败（V3 曾因此触发 `checksum mismatch`，V5 的 `locked_to` 注释即属此类）。校验和不一致一律由 S0 执行 `flyway repair`；业务流不得私改历史文件。**需要修注释也必须通过新迁移**（如 `COMMENT ON COLUMN task.locked_to IS '...'`）实现，绝不改本体。

### 2.1 M2 号段重新分配（D0 已发号）

M1 实际零迁移，上表 V2–V9 属 M1 阶段的预留，未使用。M2 重新发号如下，**每段唯一写者**，禁止跨段抢号：

| 版本 | 唯一写者 | 表/字段 | 应用时点 |
|---|---|---|---|
| V2 | W6 `dev-MRP` | `inventory`、`inventory_transit` | D3（W1 统一应用） |
| V3 | W5 `dev-BD5` | `plan_version`、`task.plan_version_id` | D3 |
| V4 | W2 `dev-SE1` | `constraint_dsl` | D3 |
| V5 | W4 `dev-SE4` | `task.lock_type/locked_from/locked_to/locked_resource_ids` | D3 |
| V6 | W3 `dev-SE3` | `solve_task` 幂等唯一索引（不加列） | D3 |

约束：

1. 五份迁移由各流在 D2 18:00 前交齐；**只有 W1 在 D3 按 V2→V6 顺序应用**，业务流不得自行 `flyway migrate`。
2. 已应用的 V2–V6 不得再修改；修订走新号段。
3. `strategy_rule` 已在 V1（677-736），M2 不新增策略表。
4. `setup_matrix_entry` **不建表**：M2 继续静态矩阵 + `setupMatrixFallback=true`，不占 V7。
5. `agent_event`/`agent_decision` 属 M3，不占 M2 号段。

---

## 3. C1 求解中立 DTO（已确认）

### 3.1 结论

- **HTTP 层**：`SolveRequest(taskNo,engine,orderNos,parameters)` 请求字段保留；响应信封自 C20 起统一为 `SolveTaskDetail`，`SolveResult(taskNo,status,tasks,message)` 不再返回。
- **求解层**：`SolverAdapter` 改为接收/返回中立 DTO，Timefold/OR-Tools 不得把引擎专有类型（`HardSoftScore`、`@PlanningEntity` 等）泄漏到 `solver-api`。
- **S0 不写实现**：本节只冻结字段与方法签名；落地由求解流（S2/S3）完成。

依据：PRD 4.5「排产引擎模块」输入/输出定义、PRD 3.1 主排产流程；`aps-solver/solver-api` 目标包 `com.hengce.aps.solver.api`。

### 3.2 五个中立 DTO（Java 21 record）

```java
package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 求解输入。tenantId/sandboxId 必填；solveTaskId 可空（试算场景）。 */
public record ScheduleProblem(
        String tenantId,
        UUID sandboxId,
        Long solveTaskId,
        UUID strategyId,                       // 可空
        String direction,                      // FORWARD/BACKWARD/BOTTLENECK
        List<SchedulingOrder> orders,
        List<SchedulingResource> resources,
        List<TaskAssignment> frozenAssignments,
        List<ConstraintSpec> constraints
) {
}

/** 订单快照；operations 为该订单生效工艺路线的工序，operationIndex = routing_node.seq_no。 */
public record SchedulingOrder(
        UUID orderId,
        String orderNo,
        String materialCode,
        BigDecimal qty,
        LocalDate dueDate,
        String priority,                       // URGENT/HIGH/NORMAL/LOW
        String freezeStatus,                   // UNFROZEN/FROZEN/LOCKED_TIME_WINDOW/LOCKED_RESOURCE
        UUID routingId,
        List<SchedulingOperation> operations
) {
}

/** 工序快照；standardSeconds/setupSeconds 已按 C2 的覆盖优先级取值。 */
public record SchedulingOperation(
        int operationIndex,
        String operationCode,
        String operationName,
        UUID operationTemplateId,
        BigDecimal standardSeconds,
        BigDecimal setupSeconds,
        UUID primaryResourceId,
        UUID primaryResourceGroupId,
        List<UUID> secondaryResourceIds,
        boolean optional
) {
}

/** 资源快照；字段与 resource 表列一一对应。 */
public record SchedulingResource(
        UUID resourceId,
        String code,
        String type,                           // PRIMARY/SECONDARY/HUMAN/TOOL
        UUID groupId,
        String capacityMode,                   // UNIT_PER_HOUR/SECONDS_PER_UNIT/BATCH_SECONDS
        BigDecimal capacityValue,
        Integer batchCapacity,                 // 可空；C2 按 1 兜底
        BigDecimal efficiency,
        UUID calendarId,
        String status                          // ACTIVE/INACTIVE（见 enums.md）
) {
}

/** 约束快照；M1 只透传 DSL 加载结果，不在求解器内解释规则语义。 */
public record ConstraintSpec(
        String code,
        String type,                           // HARD/SOFT
        BigDecimal weight,
        boolean hard,
        String phase,                          // INITIAL/OPTIMIZATION
        Map<String, Object> params
) {
}

/** 排产分配结果，也是 Timefold 计划实体的中立投影。 */
public record TaskAssignment(
        Long taskId,                           // 新解为空，持久化后回填
        UUID orderId,
        String orderNo,
        int operationIndex,
        String operationName,
        UUID operationTemplateId,
        UUID resourceId,
        String resourceCode,
        List<UUID> secondaryResourceIds,
        OffsetDateTime setupStartTime,
        OffsetDateTime setupEndTime,
        OffsetDateTime startTime,              // 加工开始 = setupEndTime
        OffsetDateTime endTime,                // 加工结束
        BigDecimal qty,
        Integer sequenceInResource,
        String status,                         // PLANNED/FROZEN/IN_PROGRESS/COMPLETED
        boolean frozen,
        Integer version
) {
}

/** 求解输出。score 为引擎无关的硬/软分投影；METRICS 见下。 */
public record ScheduleSolution(
        String status,                         // SOLVED/INFEASIBLE/TIMEOUT/FAILED
        ScoreFact score,
        List<TaskAssignment> assignments,
        List<ConstraintJustification> justifications,
        SolveMetrics metrics,
        String message
) {
}

/** 引擎无关评分：硬分必须为 0 才可发布。 */
public record ScoreFact(
        BigDecimal hardScore,
        BigDecimal softScore,
        String scoreText
) {
}

/** 约束归因（用于 Agent/甘特解释），taskIds 指向 TaskAssignment.taskId。 */
public record ConstraintJustification(
        String constraintCode,
        String type,                           // HARD/SOFT
        List<Long> taskIds,
        String description
) {
}
```

> M1 求解流还需在 `SchedulingResource` / `SchedulingOperation` / `ScheduleProblem` 上补 C12 字段
> （`availabilityWindows`、`predecessors`、`setupMatrix`）；本节其余字段与签名保持不变。

### 3.3 求解配置与指标

```java
package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 算法参数；与具体引擎无关。 */
public record SolveConfig(
        String engine,                         // timefold/ortools/rule_based（外部小写，见 C9）
        int timeoutSeconds,                    // 默认 60
        int moveThreadCount,                   // 缺省或 0 = 未指定（不调用 withMoveThreadCount，走引擎默认）
        String acceptor,                       // 默认 LATE_ACCEPTANCE
        long randomSeed,
        long maximumStepCount,                 // <=0 表示不限
        int unimprovedSecondsSpentLimit,       // 默认 10
        String direction,                      // FORWARD/BACKWARD/BOTTLENECK
        boolean useFrozenAssignments,          // 默认 true
        Map<String, Object> parameters         // 请求 parameters 原样透传
) {
}
```

> `moveThreadCount` 语义（2026-11-04 实测冻结，**替代原「0 = CPU-1，最小 1」**）：**缺省或 0 = 未指定**——实现**不调用** `withMoveThreadCount`，走引擎默认（Timefold 2.8.0 社区版即单线程 `NONE`）；**仅非 0 值才尝试应用**；运行时不支持时如实标注 `ignored`，**不拒绝请求**（拒绝会打断既有调用方）。

```java
/** 求解度量，对应 solve_task.metrics JSONB。 */
public record SolveMetrics(
        long durationMs,
        long stepCount,
        int unimprovedStepCount,
        String terminationReason,              // TIME_LIMIT/STEP_LIMIT/UNIMPROVED_LIMIT/TERMINATED
        List<ScorePoint> scoreHistory,
        int assignedTaskCount,
        Map<String, Object> extra
) {
}

/** 评分轨迹采样点。 */
public record ScorePoint(
        long elapsedMs,
        long step,
        BigDecimal hardScore,
        BigDecimal softScore
) {
}
```

### 3.4 适配器方法签名

```java
public interface SolverAdapter {

    SolverEngine engine();

    /** 同步求解；异步生命周期由 aps-service 的求解任务调度负责，不在适配器内。 */
    ScheduleSolution solve(ScheduleProblem problem, SolveConfig config);
}
```

约束：

- `SolverAdapter` 在 M1 只保留上述一个 `solve` 方法，不保留 `solve(SolveRequest)` 重载，避免双入口。
- `TaskAssignment` 的 Timefold 注解（`@PlanningEntity`、`@PlanningVariable`）只能放在 `solver-timefold` 的包装类/子类中，禁止进入 `solver-api`。
- `OffsetDateTime` 一律 UTC（`ZoneOffset.UTC`）；`LocalDate` 表示交期日，不带时区。
- 禁止在 `solver-api` 引入 Spring、Jackson、Timefold、OR-Tools 依赖。

### 3.5 与现有 `SolveRequest/SolveResult` 的映射（C1 历史归档）

| 现有字段 | 目标字段 | 映射规则 |
|---|---|---|
| `SolveRequest.taskNo` | `ScheduleProblem.solveTaskId` | `taskNo` 仅作客户端关联号，**不是主键**；服务端按 `solve_task.id` 关联，响应回显 `taskNo` |
| `SolveRequest.engine` | `SolveConfig.engine` | 小写 code 归一化；空值 `timefold`；DB 存大写 |
| `SolveRequest.orderNos` | `ScheduleProblem.orders` | 按 `tenant_id + order_no` 查询（订单无沙箱维度）；空表示租户下全部 `CONFIRMED` 订单；显式点名覆盖 `onlyConfirmed`，但 `CANCELLED` 必须拒绝 `400/40000`，见 24.2 |
| `SolveRequest.parameters` | `SolveConfig.parameters` | 原样透传；已知键 `timeoutSeconds/moveThreadCount/acceptor/randomSeed` 提升为强类型字段 |
| `SolveResult.status=SUCCESS` | `ScheduleSolution.status=SOLVED` | **已废弃**：C20 起 DB 与 API 统一六值枚举，`POST /solve-tasks` 不再返回 `SolveResult` |
| `SolveResult.status=FAILED` | `ScheduleSolution.status=FAILED` | 回包写 `FAILED` |
| `SolveResult.status=UNSUPPORTED` | `ScheduleSolution.status=FAILED` | 引擎不支持属参数错误，M1 目标改回 42200；过渡期保留 |
| `SolveResult.tasks[]` | `ScheduleSolution.assignments[]` | 只投影 `orderNo/resourceCode/startTime/endTime`；完整字段走甘特读接口 |
| `SolveResult.message` | `ScheduleSolution.message` | 原样 |
| `ScheduledTask.startTime/endTime` | `TaskAssignment.startTime/endTime` | 语义为**加工窗口**；`setupStartTime/setupEndTime` 独立存放 |

迁移期要求：

- M1 的 `POST /api/v1/solve-tasks` 请求新增可选 `sandboxId`、`strategyId`，见 `docs/api/openapi/solve.yaml`；缺省 `sandboxId` 取 C5 默认值。
- **C20 起 `POST /solve-tasks` 一律返回 `SolveTaskDetail`**；本节 `SolveResult` 映射仅作为 M1 历史实现归档，不再是对外契约（`SUCCESS` 已废弃，见 24.2）。

### 3.6 受影响文件清单（由求解流 S2/S3 落地）

| 文件 | 改动 |
|---|---|
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/SolverAdapter.java` | 方法签名替换 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/ScheduleProblem.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/ScheduleSolution.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/TaskAssignment.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/SolveConfig.java` | 新增 |
| `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/SolveMetrics.java` | 新增 |
| `aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/TimefoldSolverAdapter.java` | 适配新输入/输出 |
| `aps-solver/solver-ortools/src/main/java/com/hengce/aps/solver/ortools/OrtoolsSolverAdapter.java` | 同步签名（可先抛不支持） |
| `aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java` | 组装问题、映射结果 |
| `aps-web/src/main/java/com/hengce/aps/web/controller/SolveTaskController.java` | 请求/响应适配 |
| `aps-frontend/src/types/api.ts` | `sandboxId/strategyId` 可选字段与任务时间类型 |
| `docs/api/openapi/solve.yaml` | S0 已补 `sandboxId/strategyId`；后续字段变更走变更单 |

---

## 4. C2 时长/产能换算公式（**默认假设，待产品最终确认**）

### 4.1 输入

| 输入 | 来源列 | 说明 |
|---|---|---|
| `capacity_mode` | `resource.capacity_mode` | `UNIT_PER_HOUR` / `SECONDS_PER_UNIT` / `BATCH_SECONDS` |
| `capacity_value` | `resource.capacity_value` | `NUMERIC(18,4)`，单位随模式变化 |
| `batch_capacity` | `resource.batch_capacity` | 可空；空按 **1** 兜底 |
| `efficiency` | `resource.efficiency` | `NUMERIC(5,2)`，默认 1.00；范围 0.5–1.5 |
| `standard_seconds` | `routing_node.standard_seconds` 优先，否则 `operation_template.standard_seconds` | 单件标准工时（秒） |
| `setup_seconds` | `routing_node.setup_seconds` 优先，否则 `operation_template.setup_seconds` | 换型/准备工时（秒） |
| `qty` | `orders.qty` / `task.qty` | 订单数量 |

依据：`db/migration/V1__init.sql` 的 `resource`、`operation_template`、`routing_node`、`orders` 列定义；PRD 4.1 资源产能、4.2 工序模板。

### 4.2 三种模式

设 `q = qty`、`cap = capacity_value`、`eff = efficiency`（空按 1.00）、`batch = batch_capacity`（空按 1）。

| 模式 | 有效产能/单件工时 | 加工时长 `T_run`（秒） |
|---|---|---|
| `UNIT_PER_HOUR` | 有效产能 `C = cap × eff`（单位/小时）；单件工时 = `60 / C`（分钟） | `T_run = q × 3600 / (cap × eff)` |
| `SECONDS_PER_UNIT` | 单件工时 = `standard_seconds / eff`（秒）；`capacity_value` 不参与 | `T_run = q × standard_seconds / eff` |
| `BATCH_SECONDS` | 批次数 `n = ceil(q / batch)`；每批工时 `cap / eff`（秒） | `T_run = n × cap / eff` |

总时长：

```text
T_total = setup_seconds + T_run
```

### 4.3 舍入与边界（默认假设）

1. `T_run` 与 `T_total` 均**向上取整到整秒**（CEILING），避免低估占用导致资源超载。
2. `efficiency` 为空按 `1.00`；`batch_capacity` 为空按 `1`；`setup_seconds` 为空按 `0`。
3. `UNIT_PER_HOUR` 且 `cap × eff <= 0`、`SECONDS_PER_UNIT` 且 `standard_seconds <= 0`、`BATCH_SECONDS` 且 `cap <= 0` → 返回 `42200`，不得静默排 0 工时。
4. 三种模式都**追加** `setup_seconds`；换型时间不计入 `T_run`，在甘特中单独体现为 `setupStartTime/setupEndTime`。
5. 甘特时间窗口约定：
   - `setupStartTime = 占用块开始`
   - `setupEndTime = setupStartTime + setup_seconds`
   - `startTime = setupEndTime`（加工开始）
   - `endTime = startTime + T_run`（加工结束）
   - `setup_seconds = 0` 时，`setupStartTime = setupEndTime = startTime`（不允许为空）。
6. 换型矩阵（setup matrix）不在 C2 范围；M1 使用静态 `setup_seconds`，矩阵寻优留待后续里程碑。
7. **待产品确认点**：`efficiency` 在 `UNIT_PER_HOUR` 下乘产能、在 `SECONDS_PER_UNIT` 下除工时，两种口径互为倒数；请产品确认业务含义一致。

---

## 5. C3 任务粒度（已确认）

1. `task.operation_index` 直接等于 `routing_node.seq_no`，从 0 或 1 开始均允许，按路线实际值原样存储，不做重编号。
2. M1 **不新增** `task.routing_node_id`；关联方式：
   - `task.order_id -> orders.id`
   - `orders.routing_id` 为空时取该产品唯一 `ACTIVE` routing
   - `routing_node(routing_id, seq_no = task.operation_index)`
3. 业务唯一键约定：`(tenant_id, sandbox_id, order_id, operation_index)` 唯一；M1 不新增唯一索引（零迁移），由服务层保证。
4. 同订单同工序允许拆分/合并时，`task_adjustment.adjust_type` 记录 `SPLIT/MERGE`，`task` 仍以 `operation_index` 标识工序归属；不引入工序实例 ID。
5. `task.sequence_in_resource` 由求解/调整按 `sandbox_id + resource_id` 内的时间顺序生成并持久化，用于甘特稳定排序。

依据：`V1__init.sql` 的 `task.operation_index`、`routing_node.seq_no`、`task_adjustment`；PRD 4.2 工艺路线、3.5 人工干预。

---

## 6. C4 甘特读接口（已确认）

### 6.1 归属

`GET /api/v1/gantt/resource` 正式归**求解流（S3）**实现，不是无人认领；`gantt.yaml` 已加 `x-owner: solve-flow`。

### 6.2 请求

```http
GET /api/v1/gantt/resource?sandboxId={uuid}&from={iso8601}&to={iso8601}&resourceCodes={code1,code2}
```

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `sandboxId` | query | 否 | 缺省 `00000000-0000-0000-0000-000000000401`（C5） |
| `from` | query | 是 | ISO-8601 UTC，闭区间起点 |
| `to` | query | 是 | ISO-8601 UTC，开区间终点；`from < to` |
| `resourceCodes` | query | 否 | 逗号分隔；空表示全部 |

### 6.3 响应 `data`

```json
{
  "sandboxId": "00000000-0000-0000-0000-000000000401",
  "from": "2026-01-01T00:00:00Z",
  "to": "2026-01-08T00:00:00Z",
  "resources": [
    {
      "resourceCode": "R-001",
      "resourceName": "CNC-01",
      "resourceType": "PRIMARY",
      "calendarId": "5f0c2e1a-0000-4000-8000-000000000301",
      "calendarBound": true,
      "tasks": [
        {
          "id": 1001,
          "orderNo": "SO-2026-001",
          "operationIndex": 10,
          "operationName": "粗车",
          "resourceCode": "R-001",
          "secondaryResourceCodes": ["R-009"],
          "setupStartTime": "2026-01-02T00:00:00Z",
          "setupEndTime": "2026-01-02T00:30:00Z",
          "startTime": "2026-01-02T00:30:00Z",
          "endTime": "2026-01-02T04:30:00Z",
          "qty": 120,
          "status": "PLANNED",
          "sequenceInResource": 1,
          "version": 3
        }
      ]
    }
  ]
}
```

字段规则：

- 顶层 `Result` 包装：`{code,message,data,traceId}`，见 C7。
- `resources[]`：按 `resource.code` 升序；`resourceType` 取 `resource.type`。
- **`calendarId`（C4 增量，可空）**：该行资源生效日历的 `calendar.id`，解析优先级 **资源专属 > 资源组 > 全厂默认**（同 `V1__init.sql` `resource.calendar_id` 注释）。未绑定任何日历 → `null`，前端直接按全厂默认日历兜底，**不得再对每行调 `GET /calendars/{id}`**（避免 N+1，且不会撞 `/resources` 分页上限）。
- **`calendarBound`（C4 增量，摘要）**：`true` 表示存在资源专属或资源组日历；`calendarId=null` 时固定 `false`。前端可用它区分"已绑定但为空"与"未绑定"。
- `calendarId/calendarBound` 均为只读投影，不参与排序、不改变 C4 的窗口过滤语义。
- `tasks[]`：按 `sequenceInResource` 升序，空值按 `startTime` 兜底；同资源内稳定。
- 时间窗口：返回与 `[from, to)` **有交集**的任务；`setupStartTime` 可早于 `from`。
- `startTime/endTime` 是加工窗口，`setupStartTime/setupEndTime` 是换型窗口；`endTime > startTime`，`setupEndTime = startTime`。
- `secondaryResourceCodes` 为空数组而不是 `null`。
- 非分页结构；不返回 `total`。
- 只读接口，不需要 `Idempotency-Key`。

依据：PRD 4.7 排产结果与甘特图、3.5 人工干预、9.1 接口清单；`docs/api/openapi/gantt.yaml`。

---

## 7. C5 默认场景/租户（已确认）

| 项 | 值 | 依据 |
|---|---|---|
| 默认 `sandboxId` | `00000000-0000-0000-0000-000000000401` | `V1__init.sql` 默认 BASELINE 场景 |
| 默认租户 | `tenantId = 00000000-0000-0000-0000-000000000001`（code `DEMO`） | `V1__init.sql` 默认租户 |
| 租户注入 | 由应用层（过滤器/拦截器）从认证上下文注入，Controller/Service 不信任请求体 `tenantId` | PRD 2.6 多租户、5.4 安全 |
| `solve_task.strategy_id` | 允许 NULL；为空表示使用默认策略 | `V1__init.sql` `solve_task.strategy_id` 可空 |
| 请求缺省 | 求解/甘特请求未传 `sandboxId` 时一律落到 401；显式传入则校验存在且属于当前租户 | 本节 |

补充：

- 所有业务表写入必须带 `tenant_id`；跨租户查询直接 `404`，不泄露存在性。
- **订单是租户级资源，不是沙箱级资源（2026-11-04 冻结）**：`orders` 表（`V1__init.sql#L561-L598`）**没有 `sandbox_id` 列**，只按 `tenant_id` + `deleted=false` 过滤。因此「请求的订单不属于该沙箱」这一维度**不存在**，任何按沙箱校验订单归属的代码都是假校验，不得写入任何流。**沙箱只在任务（`task.sandbox_id`）与计划版本（`plan_version.sandbox_id`）层生效**；`POST /solve-tasks` 的 `sandboxId` 只决定任务写入哪个场景，不约束订单可见范围。
- `sandbox` 的 `BASELINE` 每租户唯一（`uq_sandbox_baseline`）；`WHAT_IF` 从 BASELINE 复制。
- `solve_task.status` 只允许 `PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT`，见 `docs/api/enums.md`。

---

## 8. C6 路径 ID 语义（已确认）

| 资源 | 路径 ID | 类型 | 示例 |
|---|---|---|---|
| 物料 | `code` | `VARCHAR(64)` | `/api/v1/materials/M-001` |
| 订单 | `orderNo` | `VARCHAR(64)` | `/api/v1/orders/SO-2026-001` |
| 资源 | `code` | `VARCHAR(64)` | `/api/v1/resources/R-001/maintenance` |
| BOM | `id` | UUID | `/api/v1/boms/{uuid}/tree` |
| 工艺路线 | `id` | UUID | `/api/v1/routings/{uuid}/graph` |
| 日历 | `id` | UUID | `/api/v1/calendars/{uuid}/available` |
| 排产任务 | `id` | `BIGSERIAL` (`int64`) | `/api/v1/tasks/1001/adjust` |
| 求解任务 | `id` | `BIGSERIAL` (`int64`) | `/api/v1/solve-tasks/9001` |
| 策略/沙箱/计划 | `id` | UUID | `/api/v1/sandboxes/{uuid}/compare` |

修正原则：

1. 路径变量名与语义一致：`{code}` / `{orderNo}` / `{id}`，不再用 `{id}` 承载 code。
2. Controller 的 `@PathVariable` 类型必须与 YAML 一致：UUID→`UUID`，bigint→`Long`，code→`String`。
3. 已实现端点（`OrderController` 的 `{orderNo}`）保持不动；占位端点向它对齐。
4. 本文件、`controller/contract/**`、`docs/api/openapi/*.yaml` 三处必须一致；本轮已修正的文件见第 12 节。
5. 资源对外用 `code` 寻址，但响应体必须带 `id`（UUID），供 `calendar.resourceId` 等引用绑定；`id` 不作为路径参数（见 C14）。
6. **订单路径参数 `{orderNo}` 无沙箱维度**：订单按租户唯一（`uq_orders_tenant_order_no`），不存在「某订单属于某沙箱」的寻址；沙箱参数只出现在任务/计划版本相关端点（见 C5 §7）。

---

## 9. C7 返回体 / 错误码 / 时间格式（已确认）

沿用既有实现，不重复造：

| 项 | 契约 | 实现 |
|---|---|---|
| 成功 | `code=0, message="success", data, traceId` | `aps-common` `Result<T>` |
| 失败 | `code` 为 4xxxx/5xxxx，`data=null`，`message` 不暴露堆栈 | `ErrorCode` + `RestExceptionHandler` |
| 常用错误码 | `40000` 参数、`40400` 不存在、`40900/40901` 冲突、`42200` 业务校验、`42900` 限流、`50000` 内部、`50010` 求解不可用、`50100` 占位未实现、`50300` 依赖不可用 | `ErrorCode` |
| HTTP 映射 | 业务码 → HTTP 状态由 `RestExceptionHandler.STATUS_MAPPING` 统一决定 | `RestExceptionHandler` |
| traceId | 响应头 `X-Trace-Id` 与响应体 `traceId` 一致 | `TraceIdFilter`、`TraceContext` |
| 时间 | 传输层 ISO-8601 UTC（`Z`）；DB `timestamptz`；Java `OffsetDateTime` | `conventions.md` |
| 分页 | `page`（从 1 开始）、`size`、`sort`；出参 `items,total,page,size` | `PageQuery/PageResult` |
| 幂等 | 写接口接受 `Idempotency-Key`（8–128 位） | `conventions.md` |

M1 必须修正的已知偏差：现有 `SolveResult.tasks[].startTime/endTime` 为无偏移本地时间，M1 改为 UTC（`ScheduledTask`/`TaskAssignment` 均用 `OffsetDateTime`）。

---

## 10. C8 订单确认校验清单（已确认）

`PUT /api/v1/orders/{orderNo}/confirm` 执行顺序：

| 序 | 校验 | 规则 | 失败码 |
|---|---|---|---|
| 1 | 订单存在且属于当前租户 | `orders.order_no` 查询；已删除→404 | `40400` |
| 2 | 状态可确认 | 仅 `DRAFT` 可转 `CONFIRMED`；重复确认幂等返回 200 | `40901` |
| 3 | 产品存在 | `orders.product_id -> material.id`，且 `material.deleted=false` | `42200` |
| 4 | BOM 唯一 ACTIVE | `bom.parent_material_id = orders.product_id` 且 `status='ACTIVE'`，恰好 1 条（`uq_bom_one_active_per_parent`） | `42200` |
| 5 | Routing 唯一 ACTIVE | 若 `orders.routing_id` 非空，校验该 routing `ACTIVE`；否则按产品取唯一 `ACTIVE` routing（`uq_routing_one_active_per_product`） | `42200` |
| 6 | 数量/交期 | `qty > 0`、`due_date` 非空（基础校验） | `40000` |
| 7 | 客户黑名单 | **M1 跳过**；`customer_id` 允许为空 | — |

补充：

- 第 4/5 条若命中多条 ACTIVE，按数据不一致处理（`42200`），不得静默取第一条。
- 确认成功后写审计；不自动触发求解。
- 客户黑名单（FR-MD-005）M1 不实现，已由项目负责人确认；后续里程碑再加。

依据：PRD 4.3 订单管理（FR-OM 订单确认）、4.1 基础数据；`V1__init.sql` 的 `orders`、`bom`、`routing` 唯一索引。

---

## 11. C9 枚举字典（已确认）

完整表格与 JSON 见 [enums.md](./enums.md)。约定：

- API 传输值使用 schema CHECK 的大写字符串，前端常量与后端枚举共用同一份字典。
- 唯一例外：求解引擎外部 code 使用小写 `timefold|ortools|rule_based`（兼容现有 `POST /solve-tasks`），落库转大写。
- 新增枚举值必须同时更新 `docs/api/enums.md` 与 schema（需迁移时走第 2 节号段）。

---

## 12. 本轮契约对齐改动（S0）

| 文件 | 改动 |
|---|---|
| `aps-web/.../controller/contract/MaterialsContractController.java` | `/materials/{id}` → `/materials/{code}`；`/resources/{id}/maintenance` → `/resources/{code}`；UUID 路径参数改为 `UUID` |
| `aps-web/.../controller/contract/OrdersContractController.java` | `/orders/{id}/confirm|freeze` → `/orders/{orderNo}`；策略/沙箱 UUID 参数改为 `UUID` |
| `aps-web/.../controller/contract/SolveContractController.java` | `solve-tasks/{id}`、`agent/explain/{taskId}` 改为 `Long` |
| `aps-web/.../controller/contract/GanttContractController.java` | `/tasks/{id}/adjust` 改为 `Long`；计划 UUID 参数改为 `UUID` |
| `docs/api/openapi/materials.yaml` | materials/resources 路径改 `{code}` |
| `docs/api/openapi/orders.yaml` | confirm/freeze 路径改 `{orderNo}` |
| `docs/api/openapi/solve.yaml` | solve-task 路径参数改 `int64`；`SolveSubmitRequest` 增补可选 `sandboxId/strategyId` |
| `docs/api/openapi/gantt.yaml` | 资源甘特标注 `x-owner: solve-flow`；`sandboxId` 改为可选并注明默认值 |

---

## 13. 各流开工前置

| 流 | 开工前必须确认 |
|---|---|
| 主数据流 | 只读本文件 + `enums.md`；不新增迁移；物料/资源路径用 code；UUID 资源路径用 UUID；BOM/日历/工艺的 ACTIVE 唯一规则复用 C8 |
| 工艺订单流 | `task.operation_index = routing_node.seq_no`（C3）；订单确认按 C8；`orders.status` 严格按枚举流转；不改 contract |
| 求解流（S2/S3） | 按 C1 落地中立 DTO 与适配器签名；按 C2 公式计算工时（默认假设）；实现 `GET /gantt/resource`（C4）；默认 sandbox 401（C5） |
| 测试部署流 | 仅使用 V8–V9；测试数据不得写进 V1；Testcontainers 版本由根 pom 管理；不要新增部署文件 |
| 前端 FE1/FE2 | 按 0.3 分工；类型只改各自范围；求解响应新增字段可选兼容；甘特字段以 C4 为准 |

---

## 15. C10 列表筛选参数（增量，已确认）

纯增量：不改已有参数名、不改响应结构；新参数未传时行为与之前一致。

| 端点 | 参数 | 类型 | 语义 |
|---|---|---|---|
| `GET /api/v1/materials` | `code` | string(64) | 模糊匹配（contains，忽略大小写）；与 `name` **OR** |
| `GET /api/v1/materials` | `name` | string(128) | 模糊匹配（contains，忽略大小写）；与 `code` **OR** |
| `GET /api/v1/materials` | `type` | enum `MaterialType` | 精确匹配；与其他条件 **AND**（已有参数） |
| `GET /api/v1/materials` | `categoryId` | UUID | 精确匹配；与其他条件 **AND**（新增） |
| `GET /api/v1/boms` | `parentMaterialCode` | string(64) | 精确匹配 `material.code`；与其他条件 **AND** |
| `GET /api/v1/boms` | `status` | enum `BomStatus` | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/resources` | `code` | string(64) | 模糊匹配（contains，忽略大小写）；与 `name` **OR** |
| `GET /api/v1/resources` | `name` | string(128) | 模糊匹配（contains，忽略大小写）；与 `code` **OR** |
| `GET /api/v1/resources` | `type` | enum `ResourceType` | 精确匹配；与其他条件 **AND**（已有参数） |
| `GET /api/v1/resources` | `groupId` | UUID | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/calendars` | `name` | string(128) | 模糊匹配（contains，忽略大小写） |
| `GET /api/v1/calendars` | `type` | enum `CalendarType` | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/calendars` | `resourceId` | UUID | 精确匹配；与其他条件 **AND** |
| `GET /api/v1/calendars` | `resourceGroupId` | UUID | 精确匹配；与其他条件 **AND** |

**OR/AND 结论**：`code` 与 `name` 之间为 **OR**（前端把同一关键字同时传给两者时命中任一即可）；`code/name` 组合与 `type/categoryId/groupId` 等其他筛选条件之间为 **AND**。BOM 的 `parentMaterialCode` 与 `status` 均为精确匹配且相互 AND。

依据：`V1__init.sql` 的 `material.code/name/type/category_id`、`bom.parent_material_id/status`、`resource.code/name/type/group_id`、`calendar.name/type/resource_id/resource_group_id`。
消费方：FE2 物料/BOM/资源/日历列表页；实现方 BD-02（物料/资源/日历）、BD-03（BOM）。

---

## 16. C11 单条详情接口（增量，已确认）

| 端点 | 路径 ID | 响应 | 归属 | 消费方 |
|---|---|---|---|---|
| `GET /api/v1/boms/{bomId}` | BOM UUID | `ResultOfBom`（BOM 头 + 完整 `items`） | BD-03 | FE2 BOM 详情页 |
| `GET /api/v1/resources/{code}` | `resource.code` | `ResultOfResource` | BD-02 | FE2 资源详情/编辑 |
| `GET /api/v1/calendars/{id}` | calendar UUID | `ResultOfCalendar` | BD-02 | FE2 日历详情/编辑 |

约定：

1. 新端点均为 `x-implementation-status: placeholder`，未实现前返回 501/50100；实现后改为 `implemented`。
2. `GET /boms/{bomId}` 是 BOM `items` 的稳定来源；列表即使后续裁剪 `items`，详情页也不得依赖列表行。
3. 不改变现有 `/boms/{id}/tree`、`/boms/{id}/activate`、`/calendars/{id}/available`。
4. 错误码沿用 C7：不存在 404/40400，未实现 501/50100。

依据：FE-02 联调反馈；`materials.yaml` 现有 `Bom`/`Resource`/`Calendar` schema 已包含所需字段，无需新增响应模型。

---

## 17. C12 求解问题能力补全（M1，契约增量）

> 状态：17.1、17.2 为 M1 **必须**（当前 `solver-api` 缺失）；17.3 为 M1 **不必须**（降级到静态换型）；17.4、17.5 已具备，仅补语义。
> **M2 注记**：DSL 语法（`constraints[]` 数组、单数 `constraint:` 兼容别名、白名单 ruleCode）以 **C19** 为准；本节 `ConstraintSpec` 透传语义与冻结/换型语义不变，不重编号。
> 依据：SE-03 联调反馈、PRD 4.5（FR-SE-001/002）、PRD 4.1（FR-MD-003/004）、PRD 4.2（FR-PR-001），`V1__init.sql` 的 `routing_edge`、`calendar`、`resource`，`docs/db/schema.md` 风险 2。
> 边界：本节只定义契约层类型与语义；`solver-api` 代码由求解流按本节落地，S0 不改代码。

### 17.1 资源可用时段（缺失）

**现状**：`SchedulingResource` 只有 `calendarId`，没有展开后的可用窗口，无法承载班次、节假日、例外日期。**M1 必须。**

建议在 `SchedulingResource` 增加：

```java
List<AvailabilityWindow> availabilityWindows

public record AvailabilityWindow(
        OffsetDateTime start,   // UTC，闭区间
        OffsetDateTime end      // UTC，开区间
) {
}
```

语义：

1. 由服务层在组装问题前按 `resource.calendar_id -> calendar` 展开：工作时段 + 班次 - 节假日 + 例外日期，裁剪到求解时域 `[horizonStart, horizonEnd)`。
2. 列表按 `start` 升序、互不重叠，`end > start`；空列表表示 **7×24 全可用**。
3. 求解器必须保证任务占用块 `[setupStartTime, endTime)` 完整落在**同一个**可用窗口内；M1 不支持跨窗口续排（跨非工作时段）。
4. `calendarId` 为空、日历未配置、展开失败 → 一律按 7×24，并在 `SolveMetrics.extra` 标记 `calendarFallback=true`，不得静默当作有日历。

降级行为：缺少该字段时求解流只能按 7×24 处理，日历硬约束失效；需在结果 `message`/metrics 中显式标注，不能伪装成已满足日历。

### 17.2 工序依赖类型与滞后（缺失）

**现状**：`SchedulingOperation` 没有 `dependType`/`lagMinutes`，问题级也没有依赖边；SE-03 无法表达 SS/FF 与滞后。**M1 必须。**

建议在 `SchedulingOperation` 增加（依赖指向同一订单内的工序）：

```java
List<OperationDependency> predecessors

public record OperationDependency(
        int predecessorOperationIndex,  // 同订单内 SchedulingOperation.operationIndex
        String dependType,              // FS / SS / FF；SF 仅保留，M1 不支持
        int lagMinutes                  // M1 要求 >= 0
) {
}
```

约束式（`P` 为前序，`S` 为当前）：

| 类型 | 约束 |
|---|---|
| `FS` | `S.startTime >= P.endTime + lagMinutes` |
| `SS` | `S.startTime >= P.startTime + lagMinutes` |
| `FF` | `S.endTime >= P.endTime + lagMinutes` |
| `SF` | `S.endTime >= P.startTime + lagMinutes`（**保留**，V1 的 CHECK 不含 SF，M1 不下发） |

语义补充：

1. `V1__init.sql` 的 `chk_routing_edge_depend_type` 只允许 `FS/SS/FF`（第 549 行），`lag_minutes` 已存在（第 538 行）；因此 M1 有效取值只有 FS/SS/FF。
2. 若上游出现 `SF`，M1 必须拒绝该问题（返回 `FAILED` 并说明），不得静默降级成 FS；启用 SF 需要新迁移（M2/M3 号段，M1 零迁移）。
3. `lagMinutes < 0`（重叠/负滞后）M1 不支持；按 42200/FAILED 拒绝。
4. 路由环检测仍由应用层保存时完成；求解前若发现环，返回 `INFEASIBLE`/`FAILED`，不做隐式断环。
5. `predecessors` 为空表示无显式依赖；求解流按 `operationIndex` 升序补默认 FS、`lagMinutes=0`，保证工序串行。

降级行为：缺少该字段时只能做「按 `operationIndex` 串行 FS、零滞后」；SS/FF 与滞后全部失效，必须在结果中标明 `dependencyFallback=true`。

### 17.3 换型矩阵（缺失，M1 不必须）

**现状**：问题级没有 setup matrix；`setup_matrix_entry` 表不在 V1（`docs/db/schema.md` 第 487/494 行写明 M2/M3 迁移），M1 零迁移无法落库。**M1 不必须，降级可接受。**

为 M2/M3 预留问题级字段：

```java
List<SetupMatrixEntry> setupMatrix

public record SetupMatrixEntry(
        UUID resourceId,        // 可空：空表示适用于所有资源
        String keyType,         // OPERATION_CODE / MATERIAL_CODE
        String fromKey,         // 可空：空表示默认行
        String toKey,           // 可空：空表示默认列
        int setupSeconds        // 换型时长（秒），M1 约定整秒
) {
}
```

语义：

1. 仅当同一资源上相邻两个任务命中一条矩阵记录时覆盖 `setupSeconds`；未命中回退 `SchedulingOperation.setupSeconds`（C2 §4）。
2. 匹配优先级：指定 `resourceId` > 空 `resourceId`；`fromKey/toKey` 精确 > 空（通配）；同优先级冲突取较大 `setupSeconds`。
3. 矩阵时长替代静态 `setup_seconds`，仍按 C2 的甘特窗口规则体现 `setupStartTime/setupEndTime`，不并入 `T_run`。
4. M1 不要求该字段；`setupMatrix` 为空即全部使用静态 `setupSeconds`，这是**已确认可接受**的降级。
5. 若 M1 后产品坚持矩阵，需要新迁移（建议 V3 段，由 S0 发号），并同步本契约。

### 17.4 辅助资源（已具备，补语义）

- 字段已存在：`SchedulingOperation.secondaryResourceIds` 与 `TaskAssignment.secondaryResourceIds`（`List<UUID>`，空值归一化为空列表）。
- **M1 语义（冻结）**：辅助资源在加工窗口 `[startTime, endTime)` 内与主资源**同时占用（exclusive）**，同一辅助资源的占用区间不得与其他任务重叠；辅助资源自身的 `availabilityWindows` 同样生效。
- 换型窗口 `[setupStartTime, startTime)` **默认不占用**辅助资源；如某类工装需要在换型期占用，由约束 DSL 单独声明（M1 不内置）。
- 若后续业务改为「仅约束、可共享」，必须走契约变更单，不得由实现方自行改变语义。

### 17.5 冻结任务（已具备，字段一致）

- 字段已存在：`ScheduleProblem.frozenAssignments`，类型为 `List<TaskAssignment>`，与 `TaskAssignment` 字段完全一致。
- 已承载：`resourceId`/`resourceCode`（pinned 资源）、`setupStartTime/setupEndTime/startTime/endTime`（pinned 时间）、`frozen`。
- **M1 限制**：没有独立 `pinned` 字段；`LOCK`（时间窗内可微调）与 `FREEZE`（完全不可移动）统一按不可移动处理，以 `frozen=true` 表达。
- 若 M1 需要区分 LOCK 与 FREEZE，需新增 `lockType`/`pinnedWindow` 契约字段（走变更单）；当前按完全冻结处理是可接受的降级。

### 17.6 降级汇总（给求解流）

| 能力 | M1 是否必须 | 缺少/为空时的降级 | 必须标记 |
|---|---|---|---|
| 资源可用时段 | 是 | 7×24 全可用 | `metrics.extra.calendarFallback=true` |
| 工序依赖 | 是 | 按 `operationIndex` 串行 FS、lag=0 | `metrics.extra.dependencyFallback=true` |
| 换型矩阵 | 否（M1 不必须） | 回退 `SchedulingOperation.setupSeconds` | 无需标记 |
| 辅助资源 | 是（语义已具备） | 空列表 = 无辅助资源 | — |
| 冻结任务 | 是（字段已具备） | 空列表 = 无冻结；LOCK/FREEZE 均不可移动 | — |

---

## 18. C13 订单更新与动作端点（已确认）

> 依据：PRD 4.3（FR-OM-001~004）、`V1__init.sql` 的 `orders` 表（第 561–598 行）、`docs/api/openapi/orders.yaml`、C8。
> 消费方：FE1 订单页；实现方：订单流。S0 只冻结契约，不实现。

### 18.1 端点定稿

| 动作 | 方法与路径 | 请求体 | 成功响应 | 说明 |
|---|---|---|---|---|
| 更新订单 | `PUT /api/v1/orders/{orderNo}` | `OrderUpdateRequest` | `ResultOfOrder` | 仅 `DRAFT` 可改；`version` 乐观锁 |
| 确认订单 | `PUT /api/v1/orders/{orderNo}/confirm` | 无 | `ResultOfOrder` | `DRAFT → CONFIRMED`；幂等 |
| 冻结/解冻 | `PUT /api/v1/orders/{orderNo}/freeze` | `OrderFreezeRequest{freezeStatus,version}` | `ResultOfOrder` | `freezeStatus=UNFROZEN` 即解冻；**不设独立 `/unfreeze`** |

**最终采用**：保留 `PUT /confirm`、`PUT /freeze`（body 表达冻结/解冻），不提供 `POST /confirm`、`POST /freeze`、`POST /unfreeze`。

### 18.2 与任务卡差异及采纳理由

任务卡描述为 `POST /confirm`、`POST /freeze`、`POST /unfreeze`；本契约采纳 `PUT` 两段式：

1. 现有静态契约、占位 Controller 与 C8 均已使用 `PUT /confirm`；改成 POST 属破坏性变更，会让前端/后端同时返工。
2. confirm 与 freeze 是订单资源的目标状态迁移（`DRAFT→CONFIRMED`、`UNFROZEN↔FROZEN`），PUT 的幂等语义更贴合；POST 动作风格需要额外 `/unfreeze` 端点。
3. 解冻是 `freezeStatus=UNFROZEN` 的目标状态，一个端点即可覆盖冻结/解冻，减少一条路径与一套错误码。
4. 若后续必须兼容任务卡的 POST 风格，需走契约变更单，并以别名或 `/api/v2` 提供；**M1 不做别名**。

### 18.3 更新订单契约

请求体 `OrderUpdateRequest`（纯增量，响应结构不变）：

| 字段 | 必填 | 类型 | 规则 |
|---|---|---|---|
| `version` | 是 | integer ≥1 | 必须等于当前 `orders.version`，否则 40901 |
| `materialCode` | 否 | string | `orders.product_id` → `material.code`；物料必须存在且未删除，否则 42200 |
| `qty` | 否 | number > 0 | `qty <= 0` → 42200 |
| `dueDate` | 否 | date | 非法日期 → 40000/42200 |
| `priority` | 否 | enum `OrderPriority` | 非法值 → 40000 |
| `customerId` | 否 | UUID / null | 客户不存在 → 42200（M1 不做黑名单，见 C8） |
| `routingId` | 否 | UUID / null | routing 必须存在且属于当前租户，否则 42200 |
| `remark` | 否 | string / null | — |
| `extAttrs` | 否 | object | 必须是 JSON object，否则 40000 |

规则：

- `orderNo` 不可修改；请求体不提供该字段。
- `status != DRAFT` → 42200；`deleted=true` → 40400。
- 成功时 `version` 原子 +1，返回更新后的订单。
- 乐观锁失败 → `40901`；重复/业务键冲突 → `40900`。
- 本接口不要求 `Idempotency-Key`；`version` 即并发保护，成功后重放会因版本不匹配得到 40901，客户端应刷新后重试。

### 18.4 确认 / 冻结契约

- `PUT /confirm`：无请求体；`DRAFT → CONFIRMED`；重复确认返回 200（目标状态幂等）；非 DRAFT → 42200；产品/BOM/routing 校验按 C8。
- `PUT /freeze`：请求 `freezeStatus` + `version`；`UNFROZEN ↔ FROZEN/LOCKED_*` 迁移；重复请求同一目标状态返回 200；`version` 不一致 → 40901；非法状态值 → 40000。
- **幂等键**：两接口均为**目标状态幂等**，`Idempotency-Key` 可选（传入时按 C7 校验 8–128 位并缓存结果），不强制；重复请求不重复写审计/不重复发事件。
- 响应统一 `ResultOfOrder`，`version` 返回最新值。

### 18.5 状态流转与取消/关闭

| 动作 | M1 契约 |
|---|---|
| `DRAFT → CONFIRMED` | `PUT /confirm`（C8 校验） |
| `CONFIRMED → SCHEDULED → RELEASED → IN_PROGRESS → COMPLETED` | 由求解/执行流推进，不在订单动作端点内 |
| `CANCELLED` | **M1 不涉及**：契约无取消/关闭端点，枚举保留 |
| 冻结轴 `UNFROZEN/FROZEN/LOCKED_*` | 仅 `PUT /freeze` 承载；`LOCKED_*` 的写入口不在 M1 |

结论：M1 订单动作只有「更新（DRAFT）」「确认」「冻结/解冻」三类；取消/关闭接口 M1 不涉及，若后续要加，走契约变更单并复用 `ResultOfOrder`。

---

## 19. C14 资源与日历契约补全（已确认）

> 依据：PRD 4.1（FR-MD-003 资源、FR-MD-004 日历）、`V1__init.sql` 的 `resource`（第 330–377 行）与 `calendar`（第 379–425 行）、C6、C10、C12。
> 消费方：FE-02 资源页、FE-03 日历页；实现方：BD-02（资源）、BD-05（日历）。S0 只改契约。

### 19.1 端点补全

| 端点 | 说明 | 关键错误 |
|---|---|---|
| `GET /api/v1/resources/{code}` | 资源详情，路径用 code，响应带 `id` | 40400 |
| `PUT /api/v1/resources/{code}` | 更新资源，body 必填 `version` | 40901 版本冲突、42200 校验失败 |
| `DELETE /api/v1/resources/{code}` | 停用（`deleted=true` 软删除），不物理删除 | 40400；被日历/任务引用 → 42200 |
| `GET /api/v1/calendars/{id}` | 日历详情，路径用 UUID | 40400 |
| `PUT /api/v1/calendars/{id}` | 更新日历，body 必填 `version` | 40300/403 历史日历只读、40901 版本冲突 |

- 资源 `PUT`：`code` 不可改；成功 `version` 原子 +1；返回 `ResultOfResource`。
- 资源 `DELETE`：幂等，重复调用 200；被 `calendar.resource_id` 或 `task.resource_id` 引用时拒绝（42200）。
- 日历 `PUT`：`calendarVersion` 不可改，新版本走创建接口；`effectiveTo < 今天` 的日历只读，`effectiveTo` 为空视为长期有效、可更新。

### 19.2 四条数据结论

1. **资源响应暴露 `id`**：`Resource` schema 新增只读 `id`（UUID）。资源仍用 `code` 寻址，但前端绑定日历时必须用 `id` 填 `calendar.resourceId`，禁止让人手填 UUID。
2. **`calendarVersion` 以 schema 为准为 INTEGER**：`calendar.calendar_version` 是 `INTEGER`（V1 第 392 行），YAML 原写 `string`（示例 `V1.0`）属错误，已改为 `integer`。递增规则：同 `tenant + name` 新建版本时取当前最大 `calendar_version + 1`；历史版本只读、不原地修改；唯一索引 `uq_calendar_tenant_name_version` 保障。
3. **`resource` 不引入 `status`**：schema 只有 `deleted BOOLEAN`（V1 第 359 行），M1 用 `deleted=true` 表达停用/软删除；资源列表与响应**不新增** `status` 字段、列表**不新增** `status` 参数。若前端已按可选 `status` 做兼容展示，那只是**客户端兼容逻辑，不是契约字段**，不得写回请求或依赖后端返回。
4. **`GET /calendars/{id}/available` M1 不涉及**：端点保留为 `placeholder`，M1 不实现。求解所需可用时段由服务层按 C12 在内部展开（`availabilityWindows`），前端日历详情直接用 `shifts/holidays` 展示，不调用该端点。

### 19.3 筛选参数

| 端点 | 结论 |
|---|---|
| `GET /resources` | `code`/`name` 模糊（OR）、`type`/`groupId` 精确（AND）**已具备**（C10）；**不加 `status`** |
| `GET /calendars` | `name` 模糊、`type` 精确**已具备**（C10 同时已有 `resourceId`/`resourceGroupId`） |

语义沿用 C10：`code/name` OR，与其他条件 AND。

### 19.4 HTTP 与错误码映射

| 业务码 | HTTP | 场景 |
|---|---|---|
| 40300 | 403 | 历史日历（`effectiveTo < 今天`）更新 |
| 40901 | 409 | 资源/日历 `version` 与当前值不一致 |
| 42200 | 422 | 资源被日历/任务引用仍删除、非法产能组合、日历区间非法 |
| 40400 | 404 | code/UUID 不存在或已删除 |

### 19.5 任务卡/前端预留与最终契约差异

| 预留/旧描述 | 最终契约 | 处理 |
|---|---|---|
| `POST /resources/{code}/disable` 或物理删除 | `DELETE /resources/{code}` 软删除 | 采用 DELETE；不提供 `/disable`、不物理删除 |
| `PATCH /resources/{code}` 局部更新 | `PUT /resources/{code}` + `version` | 采用 PUT；前端按 PUT 调整 |
| 资源响应无 `id`，日历 `resourceId` 需手填 | 响应带 `id` | 前端改用 `id` 绑定 |
| `calendarVersion` 为字符串（`V1.0`） | INTEGER（`1`） | 前端类型改 number；旧值需迁移展示 |
| 资源可选 `status` 字段/筛选 | 无 status，只有 `deleted` | 前端兼容展示可保留，但不得作为契约参数 |
| `GET /calendars/{id}/available` 视为 M1 必做 | M1 不涉及，保留占位 | 求解流内部展开，前端不调用 |
| 日历“新建版本”路径 `POST /calendars/{id}/versions` | M1 未定义独立版本端点 | 用 `POST /calendars` 携带递增 `calendarVersion` |

### 19.6 日历详情对象与时间语义（C14 增量，W7 反馈）

`GET /api/v1/calendars/{id}` M1 已实现，但此前 `materials.yaml` 只有路径没有对象结构；现已补齐 `Calendar` schema（`docs/api/openapi/materials.yaml`），字段如下：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | `calendar.id`，路径寻址用 |
| `name` | string | 日历名；`tenant + name + calendarVersion` 唯一 |
| `calendarVersion` | integer | 业务版本号 `calendar.calendar_version`，历史版本只读 |
| `type` | enum | `FACTORY / RESOURCE / SHIFT`（V1 `chk_calendar_type`） |
| `resourceId` / `resourceGroupId` | UUID? | 资源专属 / 资源组级；`FACTORY` 时 `resourceId=null` |
| `effectiveFrom` / `effectiveTo` | date / date? | 生效期；`effectiveTo=null` 表示长期有效 |
| `timezone` | string? | **C14 新增**：IANA 时区名，见下方"时间语义" |
| `shifts[]` | array | `{startTime, endTime, daysOfWeek:[1..7]}`，**工厂墙钟时间**（`HH:mm`） |
| `holidays[]` | array | `{date, name, kind}`，`kind ∈ LEGAL / COMPANY / MAKEUP` |
| `version` | integer | 乐观锁版本；`PUT` 必填，不一致 `40901` |

**时间语义（冻结）**：

1. 班次 `shifts[].startTime/endTime` 与例外日 `holidays[].date` 一律按**日历 `timezone`** 解释；`timezone` 缺省取服务端配置 `aps.calendar.timezone`（默认 `Asia/Shanghai`）。
2. **禁止**按浏览器本地时区解释班次；前端只做时区换算展示，不参与"哪一天/哪一时段"的判定。所有跨日班次（`endTime < startTime`）按该时区归入 `startTime` 当天。
3. M2 **不新增 `calendar.timezone` 列**：`timezone` 为只读投影，来自上述配置；若后续确需多时区日历，再单独发迁移号（不得复用 V2–V6）。

**调休（`kind=MAKEUP`）班次来源规则（冻结）**：

1. `MAKEUP` 例外日若能在 `shifts` 中匹配到当日星期（`daysOfWeek` 覆盖），直接使用匹配的班次。
2. 匹配不到（如原为周末、日历只配了周一至周五）时，按**"调休推定"**处理：沿用该日历**全部班次时段的并集**作为当日可用时段，并在响应/前端标注「调休推定 N 天」（N 为该日历被推定的 `MAKEUP` 日数）。
3. 后端在 M2 **不自动改写 `calendar.shifts`**；推定只发生在读取侧（求解展开与前端展示口径一致）。
4. `LEGAL` / `COMPANY` 例外日为整天不可用（不产生可用时段），不适用推定。

---

## 20. C15 工艺路线图与工序模版（已确认）

> 依据：PRD 4.2（FR-PR-001~004）、`V1__init.sql` 的 `routing`（第 467–496 行）、`routing_node`（第 498–529 行）、`routing_edge`（第 531–553 行）、`operation_template`（第 430–462 行）、`docs/db/schema.md` 5.5。
> 消费方：FE-04 工艺路线编辑器；实现方：BD-07（工艺路线）、SE-04（问题装配）。S0 只改契约。

### 20.1 端点与筛选参数

| 端点 | 说明 | 关键错误 |
|---|---|---|
| `GET /api/v1/routings/{id}/graph` | 读取 DAG（routing UUID） | 40400 |
| `PUT /api/v1/routings/{id}/graph` | 整体替换 nodes/edges，body 必填 `version` | 40901 版本冲突、42200 有环/seqNo 重复/资源不存在 |
| `GET /api/v1/operation-templates` | 工序模版列表，供编辑器选择 | — |

- `GET /routings` 新增筛选：`productCode`（精确，product_id → material.code）、`version`（业务版本号 `routing_version`，字符串）、`status`（`RoutingStatus` DRAFT/ACTIVE/INACTIVE）；条件 AND。
- `GET /operation-templates`：分页；`code`/`name` 模糊 OR；`resourceType` 精确 AND（`primary_resource_id → resource.type`）。
- 注意两个 `version` 语义不同：列表筛选的 `version` 是业务版本 `routing_version`（字符串）；PUT body 的 `version` 是乐观锁整数（`routing.version`）。

### 20.2 node / edge 与真实列映射

| 契约字段 | 真实列 / 持久化位置 | 类型 |
|---|---|---|
| `seqNo` | `routing_node.seq_no` | integer，路线内唯一 |
| `operationTemplateId` | `routing_node.operation_template_id` | UUID，可空 |
| `operationCode` / `operationName` | `routing_node.operation_code/name` | 可空 |
| `standardSeconds` / `setupSeconds` | `routing_node.standard_seconds/setup_seconds` | integer，可空 |
| `primaryResourceId` | **`routing_node.overrides.primaryResourceId`** | UUID |
| `secondaryResourceIds` | **`routing_node.overrides.secondaryResourceIds`** | UUID[] |
| `optional` | `routing_node.is_optional` | boolean |
| `fromSeqNo` / `toSeqNo` | 服务端映射到 `routing_edge.from_node_id/to_node_id` | integer |
| `dependType` | `routing_edge.depend_type` | FS/SS/FF（CHECK 不支持 SF） |
| `lagMinutes` | `routing_edge.lag_minutes` | integer ≥0 |

**重要更正**：V1 的 `routing_node` **没有** `primary_resource_id` 列（该列在 `operation_template`）；节点级资源覆盖按 `schema.md` 5.5 存在 `overrides` JSONB。`routing_edge` 存的是 node UUID，不是 seqNo，`fromSeqNo/toSeqNo` 由 BD-07 在保存时映射。

### 20.3 资源引用最终结论：方案 B（UUID）

**结论一句话**：路由节点对外一律使用 `primaryResourceId`（UUID），前端选择器改用资源响应里的 `id`，不再传 `resource_code`。

理由：

1. 持久化位置 `overrides.primaryResourceId` 本身就是 UUID，直接落库无转换。
2. 避免每个节点一次 code→id 查询与 code 唯一性依赖，保存大图时性能更稳。
3. C14 已要求资源响应带 `id`，日历绑定已采用 UUID；路由沿用同一规则，前端只需一个选择器取值口径。
4. SE-04 问题装配直接消费 UUID，无需再做映射；`SchedulingResource.id` 也是 UUID，链路一致。
5. code 虽唯一但属业务键，改名/换码会波及历史路线；UUID 不受影响。

影响面：

| 方 | 影响 |
|---|---|
| FE-04 | 资源下拉 `value=resource.id`，展示 `code/name`；请求节点改用 `primaryResourceId`；不再发送 `resource_code` |
| BD-07 | 校验 `primaryResourceId` 对应资源存在且未删除；写入 `routing_node.overrides.primaryResourceId`；`secondaryResourceIds` 写 `overrides.secondaryResourceIds`；边按 `seqNo` 映射 node UUID；生成 `node_key` |
| SE-04 | 从 `overrides.primaryResourceId` 读取主资源，未覆盖时回退 `operation_template.primary_resource_id`；无 code→id 映射 |

兼容说明：`RoutingNode.resourceCode` 与 `RoutingEdge.from/to/minLagMinutes` 保留为 **readOnly + deprecated** 的响应兼容字段，请求不再使用；若请求只给 `resourceCode` 而不给 `primaryResourceId`，BD-07 返回 42200。

### 20.3.1 `routing_node.overrides` 白名单与读写口径（2026-11-04 裁定：方案 A）

**裁定**：采用 **A**。不新增列、不提 V7；节点主资源存储于 `routing_node.overrides`，API 层映射为顶层 `primaryResourceId`。V2–V6 已全部应用，方案 B 的 V7 仅在确有列需求时由 S0 发号，且已应用迁移一律禁改。

| 键 | 类型 | 必填 | 三态 | 说明 |
|---|---|---|---|---|
| `primaryResourceId` | `string`（UUID）或 `null` | 否 | 缺键=未覆盖→模板回退；`null`=显式主资源浮动；`"<uuid>"`=固定 | 节点级主资源覆盖；三态**不等价**，见 20.3.1.1 |
| `secondaryResourceIds` | `array<string>`（UUID） | 否 | 缺键=未覆盖→模板回退；`[]`=显式无辅助资源；`["<uuid>"...]`=显式列表 | 节点级辅助资源覆盖，按数组顺序；见 20.3.1.3 |

- `overrides` 必须是 JSON object（V1 `chk_routing_node_overrides_object`），**只允许上表两个键**，禁止出现其他键。
- **请求侧不暴露 `overrides`**：`PUT /routings/{id}/graph` 的节点字段是顶层 `primaryResourceId` / `secondaryResourceIds`；BD-07 保存时只写这两个白名单键（其余键不落库，`RoutingOverrides.build` 是唯一写入口）。
- **响应侧取值来源**：`GET /routings/{id}`、`GET /routings/{id}/graph` 的 `primaryResourceId` / `secondaryResourceIds` **只从 `overrides` 提升**；未覆盖时返回 `null`，**不以 `operation_template` 回填**。
- 模板回退只发生在 SE-04 排产装配（`ScheduleProblemAssembler`）：`overrides.primaryResourceId` → `operation_template.primary_resource_id` → `primary_resource_group_id` 下任一资源 → 场景唯一资源；仍无主资源则 `42200`。
- UUID 一律字符串形式，读取端用 `UUID.fromString` 严格解析；非法字符串不属于合法存储，BD-07 必须在保存前校验（不合法 `42200`）。
- **禁止归一化**：任何读写/校验层都不得把“缺键”与“`null`”混为一谈，也不得把“缺键”与“`[]`”混为一谈。

#### 20.3.1.1 `primaryResourceId` 三态（缺键 ≠ `null`）

| 存储形态 | 名称 | 语义 | 排产装配（SE-04）行为 |
|---|---|---|---|
| 键不存在 | 未覆盖 `INHERIT` | 节点不表达主资源意图 | 回退 `operation_template.primary_resource_id` → `primary_resource_group_id` 下任一资源 → 场景唯一资源 → 仍无则 `42200` |
| `"primaryResourceId": null` | 显式浮动 `FLOAT` | 节点**显式声明**“主资源不固定，交给求解器选” | **跳过**模板与资源组回退，直接 `primaryResourceId = null`；求解器可分配到任意资源 |
| `"primaryResourceId": "<uuid>"` | 固定 `FIXED` | 节点锁定主资源 | 校验资源存在且未删除；求解器只能排在该资源 |

- **缺键与 `null` 不等价**。`ScheduleProblemAssembler.primaryResourceId` 用 `overrides.containsKey("primaryResourceId")` 判定三态；`RoutingOverrides.primaryResourceId()` 用 `get` **无法区分**两者，只能用于展示/校验，**不得用于装配判定**。
- 显式 `null` 是 M2 的正式语义，不是脏数据：V1 的 `chk_routing_node_overrides_object` 只约束 `overrides` 为 object，允许键值为 `null`。
- `FLOAT` 工序是唯一能被 C21 `RESOURCE` 锁收缩资源集合的工序（见 20.3.1.2）。

#### 20.3.1.2 与 C21 `RESOURCE` 锁的叠加：固定工序上锁是空操作（不是 bug）

- 硬约束 `primaryResourceOnly`（`ConstraintPredicates.primaryResourceViolated`）：`primaryResourceId != null && plannedResourceId != primaryResourceId` → 罚 hard。**主资源固定的工序，可行资源只剩一个**。
- 因此 `lockType=RESOURCE` 的 `lockedResourceIds` 对**固定工序是空操作**：白名单写几个资源都一样，能排的只有那一个。这不是锁未生效，而是 `primaryResourceOnly` 与锁叠加后可行域收敛；**验收/排障不得据此判定 `RESOURCE` 锁失效**。
- 要让 `RESOURCE` 锁真正改变求解结果，必须把该工序写成 `overrides.primaryResourceId = null`（`FLOAT`），再由锁把可分配集合收缩到 `lockedResourceIds`；`lockedResourceIds` 单元素时等价固定，多元素时是白名单内择优。
- 排障口径：`lockType=RESOURCE` 但结果与无锁完全一致 → 先查该任务主资源是否为固定值；固定则属预期，改 `FLOAT` 后复测。

#### 20.3.1.3 `secondaryResourceIds` 三态（缺键 ≠ `[]`）

| 存储形态 | 语义 | 排产装配行为 |
|---|---|---|
| 键不存在 | 未覆盖 | 回退 `operation_template.secondary_resource_ids`（模板无值则空数组） |
| `[]` | 显式“无辅助资源” | **不回退模板**，按空数组处理 |
| `["<uuid>"...]` | 显式辅助资源 | 按数组顺序使用，不回退模板 |

- `RoutingOverrides.build` 对 `null` 不写键、对 `[]` 写空数组；`ScheduleProblemAssembler.secondaryResourceIds` 用 `containsKey` 判定，**三态已对齐**。
- 非数组值属非法存储，装配时 `42200`，不静默降级为空数组。
- 本键**不需要额外的模式枚举**：缺键/`null`、`[]`、数组 在 JSON 与 `List` 上天然可区分（见 20.3.1.5）。

#### 20.3.1.4 浮动工序的时长口径（2026-11-04 冻结）

- 时长公式以 C2 为准（`DurationCalculator`）：`UNIT_PER_HOUR` 用 `capacity_value × efficiency`，`BATCH_SECONDS` 用 `capacity_value`/`batch_capacity`/`efficiency`，`SECONDS_PER_UNIT` 用 `standard_seconds`/`efficiency`。**三种模式都显式读取资源字段**——`SECONDS_PER_UNIT` 也非绝对“与资源无关”：`efficiency` 不同则时长不同。
- 求解模型在**建模型时**为每个任务算一次 `runSeconds` 并写死（`TimefoldModelBuilder`），资源归属由 `ResourcePlan` 的 list variable 决定；**换资源不会重算时长**。因此浮动工序必须有确定口径的“标称资源”：
  1. `primaryResourceId` 固定 → 该资源；
  2. 浮动 + `lockType=RESOURCE` 且 `lockedResourceIds` 非空 → `lockedResourceIds` 中 `resource.code` 字典序最小者；
  3. 浮动 + 无资源锁 → 场景内全部启用资源中 `resource.code` 字典序最小者（`ScheduleProblemAssembler.buildResources` 已按 `code` 升序，`resolveResource` 取首个，顺序确定）。
- **前置校验（必须实现）**：浮动工序的候选资源集合内，各资源对该工序算出的 `runSeconds` 必须一致；不一致时装配阶段返回 `42200`（`message` 指明工序与冲突资源），**不得静默取第一个资源代算**。
  - 候选集合：`RESOURCE` 锁生效时为 `lockedResourceIds`，否则为场景内全部启用资源。
  - 等价条件（满足其一即可）：资源间 `capacity_mode`、`capacity_value`、`efficiency`、`batch_capacity` 相同；`SECONDS_PER_UNIT` 场景下只要求 `efficiency` 相同。
- **实现口径（已按本节落地）**：浮动工序按 `lockedResourceIds` 收敛后取 `resource.code` 最小者为标称资源；候选资源时长的等价性在装配阶段前置校验，不一致返回 `42200`。
- 若后续确需“混合产能资源下浮动”，应改为“按实际分配资源重算时长”（`runSeconds` 需成为随资源变化的派生量），属模型改造，另行立条款，不在本条范围。

#### 20.3.1.5 API 表达（三态已实现；前端 / BD-07 按此实现）

- **实现口径**：请求用 `primaryResourceMode` 区分三态；`FLOAT` 保存时写 `"primaryResourceId": null`（键存在、值为 `null`），`GET` 响应回显 `primaryResourceMode`，因此可表达、可回显“显式浮动”。
- **冻结的 API 表达**：节点请求新增 `primaryResourceMode`，取值 `INHERIT | FLOAT | FIXED`：
  - 字段缺省 = `INHERIT`（等价 overrides 无键）；
  - `FLOAT` → `primaryResourceId` 必须缺省或为 `null`（给非空 UUID → `42200`），保存时写 `"primaryResourceId": null`；
  - `FIXED` → 必须给 `primaryResourceId`（UUID），保存时写字符串；缺 `primaryResourceId` → `42200`；
  - 兼容：不带 `primaryResourceMode` 但带 `primaryResourceId` 的旧请求按 `FIXED`；两者都不带按 `INHERIT`。
- `GET /routings/{id}` 与 `GET /routings/{id}/graph` 的节点响应必须同时返回 `primaryResourceMode`（由 `containsKey` + 值判定），否则 `primaryResourceId=null` 无法区分 `INHERIT` 与 `FLOAT`。
- `secondaryResourceIds` 不需要新字段：`null`/缺键=`INHERIT`、`[]`=显式空、数组=显式列表。

### 20.4 FE-04 其他扩展端点

本轮只发现 `GET /operation-templates` 一个新端点；`routings` 的 activate/versions 等未在契约中定义，FE-04 也未提出。M1 如需要，走契约变更单，不私自新增路径。

### 20.5 与任务卡/前端预留差异

| 旧描述/实现 | 最终契约 |
|---|---|
| 节点传 `resource_code` | 改传 `primaryResourceId`（UUID） |
| 节点用 `sequence` | 用 `seqNo`（旧字段 readOnly 兼容） |
| 边用 `from/to/minLagMinutes` | 用 `fromSeqNo/toSeqNo/dependType/lagMinutes`（旧字段 readOnly 兼容） |
| 无工序模版列表端点 | 新增 `GET /operation-templates` |
| 列表无 productCode/version/status | 已补三个筛选参数 |
| 无 routing 图写入端点 | 新增 `PUT /routings/{id}/graph`，带 `version` 乐观锁 |

---

## 21. C16 工序模版寻址与 process 分组（已确认）

> 依据：`V1__init.sql` 的 `operation_template`（第 430–462 行，`template_version` + 唯一索引 `tenant_id, code, template_version`）、`docs/api/conventions.md` 第 9 节、FE-04 联调反馈。
> 消费方：FE-04；实现方：BD-07、`OpenApiConfiguration` 维护者。S0 只改契约。

### 21.1 最终结论

**工序模版详情一律用 UUID**：`GET/PUT/DELETE /api/v1/operation-templates/{id}`，`{id}` 为 `operation_template.id`。**不允许**任何 `{code}` 形式的详情路径。

理由：同一 `code` 在 `operation_template` 中可对应多个 `template_version`（唯一索引是 `tenant_id + code + template_version`），`{code}` 寻址天生歧义，无法确定返回哪个版本。

### 21.2 端点定稿

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/operation-templates` | 列表；`code`/`name` 模糊 OR，`resourceType` 精确 AND（不变） |
| `POST` | `/api/v1/operation-templates` | 创建；`code` 在**请求体**中，路径不含 code；`code + templateVersion` 重复 → 40900 |
| `GET` | `/api/v1/operation-templates/{id}` | 详情（UUID） |
| `PUT` | `/api/v1/operation-templates/{id}` | 更新（UUID）；`version` 乐观锁 → 40901；`code/templateVersion` 不可改 |
| `DELETE` | `/api/v1/operation-templates/{id}` | 软删除（UUID）；被 `routing_node.operation_template_id` 引用 → 42200 |

- `POST` 的 `code` 是**请求体字段**，不是路径参数；列表筛选的 `code` 仍是查询参数。
- 新版本通过 `POST` 创建并递增 `templateVersion`，不通过 `PUT` 原地修改。
- 已有 `GET /routings` 等工艺路线端点路径不变。

### 21.3 历史事实与收敛

仓库里工序模版曾有 `{code}` 与 `{id}` 两套并行实现/描述；现**统一收敛到 `{id}`**。本契约与 `materials.yaml` 为唯一基线，任何旧文档、旧任务卡中的 `{code}` 详情路径均作废，不得据此回写。

### 21.4 `process` 分组规则原文

`docs/api/conventions.md` 已登记 `process` 分组，`OpenApiConfiguration` 实现者照抄：

```java
GroupedOpenApi.builder()
        .group("process")
        .pathsToMatch("/api/v1/operation-templates/**", "/api/v1/routings/**")
        .build();
```

同时**必须**从 `materials` 分组移除 `/api/v1/routings/**`：

```java
GroupedOpenApi.builder()
        .group("materials")
        .pathsToMatch("/api/v1/materials/**", "/api/v1/boms/**", "/api/v1/resources/**",
                "/api/v1/calendars/**", "/api/v1/mrp/**")
        .build();
```

- 分组名固定 `process`；`/v3/api-docs/process` 必须包含 `/operation-templates/**` 与 `/routings/**`。
- 静态契约目前仍在 `materials.yaml`（工序模版与工艺路线同文件），`process` 分组只影响运行时文档；后续如拆分 `process.yaml`，以本规则为准同步。

---

## 22. C17 BOM/日历删除与实现状态登记（已确认）

> 依据：后端实际 Controller（BD-02/BD-03/BD-05 已交付）、`V1__init.sql` 的 `bom/bom_item/calendar`、`docs/api/openapi/README.md` 的状态取值规则。
> 消费方：FE-02/FE-03；实现方：已交付后端。S0 只登记契约。

### 22.1 新增删除端点

| 端点 | 语义 | 响应/错误 |
|---|---|---|
| `DELETE /api/v1/boms/{bomId}` | 逻辑删除 BOM 头**及其自身明细**（items），不物理删除 | 200（`Result` 无 data）；40400/404；被 `orders.product_id` 或其他 BOM 子件行引用 → 42200/422 |
| `DELETE /api/v1/calendars/{id}` | 日历软删除（`calendar.deleted=true`） | 200（`Result` 无 data）；40400/404 |

- BOM **没有**独立“停用”端点，删除即逻辑删除；契约不补 `/disable` 或 `/deactivate`。
- 日历删除**不区分历史**：历史只读限制只作用于 `PUT`（40300/403）；`DELETE` 对历史日历行为与普通日历一致，不额外返回 40300。
- 两者路径参数均为 UUID，与 C6/C14 一致。

### 22.2 `x-implementation-status` 同步规则

按 `docs/api/openapi/README.md`，已落地端点使用 `implemented`，未落地保持 `placeholder`。本轮已改为 `implemented` 的操作：

| 域 | operationId |
|---|---|
| 物料 | `listMaterials`、`createMaterial`、`getMaterial`、`updateMaterial`、`deleteMaterial` |
| BOM | `listBoms`、`createBom`、`getBom`、`bomTree`、`activateBom`、`deleteBom` |
| 资源 | `listResources`、`createResource`、`getResource`、`updateResource`、`deleteResource` |
| 日历 | `listCalendars`、`createCalendar`、`getCalendar`、`updateCalendar`、`deleteCalendar` |
| 工序模版 | `listOperationTemplates`、`createOperationTemplate`、`getOperationTemplate`、`updateOperationTemplate`、`deleteOperationTemplate` |

同时在描述首行把「状态：占位」改为「状态：已实现」。

### 22.3 仍保持 placeholder 的清单

| 端点/操作 | 原因 |
|---|---|
| `POST /materials/import`（`importMaterials`） | 仍返回 501 |
| `POST /resources/{code}/maintenance`（`maintainResource`） | 仍返回 501 |
| `GET /calendars/{id}/available`（`availableWorkingTime`） | M1 不涉及（C14） |
| `GET/POST /routings`、`GET/PUT /routings/{id}/graph` | BD-07 开发中，按指示保持 placeholder |
| `POST /mrp/analysis`（`mrpAnalysis`） | 未落地 |
| `orders/solve/gantt/system` 全部端点 | 本轮未改动，按各自原状态 |

---

## 23. 待产品确认

1. **C2 全部公式**为默认假设：`efficiency` 乘产能 / 除工时的业务口径、整秒向上取整、`batch_capacity` 空按 1、换型时间独立计。
2. 客户黑名单（FR-MD-005）M1 跳过已确认；后续里程碑的过滤口径（按客户/按订单/按产品线）仍待产品定义。
3. 求解引擎对外是否统一为小写 code（当前保留兼容），以及 `UNSUPPORTED` 是否改为 `42200`。
4. `solve_task.strategy_id` 为空时使用哪套默认权重（当前按 PRD 默认策略，具体权重待策略流给出）。

---

## 24. M2 契约冻结（C19–C29）

> 状态：**冻结**（2026-11-04，M2-D0）。写入人：W1 `dev-CONTRACT`。
> 决策来源：用户 2026-11-04 对 M2 七项默认决定的最终拍板，落地口径见 `docs/plan/m2-plan.md` §8。
> 编号规则：C19–C29 为新增编号，**不改 C1–C17 的编号与结论**；C12 顶部已加「DSL 语法以 C19 为准」。
> 通用约定（返回体、HTTP 语义、时间格式、分页）不变，见 `docs/api/conventions.md`。

### 24.1 C19 策略规则与受控 DSL（唯一写者 W2 `dev-SE1`）

> **维度裁定（2026-11-04）**：DSL **以 `code` 为维度，不经 `strategy_id`**；端点为 `/api/v1/constraint-dsls/**`。
> V4 `constraint_dsl` 不含 `strategy_id`，**不补该列**——策略实例与规则集编码解耦，避免出现两套寻址方式。

**策略对象**：`Strategy` 在 M2 增加 `rules` 数组，替代 M1 的 `initialRules`/`optimizationRules` 两个自由 `object`（两字段标记 `deprecated: true`，仅保留读取兼容）。

| 字段 | 类型 | 约束 |
|---|---|---|
| `id` | UUID | 服务端生成 |
| `name` | string | 租户内 `name + strategyVersion` 唯一 |
| `direction` | enum | `FORWARD/BACKWARD/BOTTLENECK` |
| `strategyVersion` | int | 从 1 递增 |
| `effectiveFrom` / `effectiveTo` | date | `effectiveTo` 可空；`to >= from` |
| `applyCondition` | object | `productLines: string[]`、`customers: string[]`，空数组表示全部 |
| `rules` | array | 见下；**M2 规范字段** |
| `version` | int | 乐观锁，更新必须携带 |

**规则条目 `StrategyRule`**：

```json
{ "code": "MIN_TARDINESS", "enabled": true, "weight": 20, "params": {} }
```

- `code` 白名单（8 条核心软约束，PRD FR-SS-002 / 1551）：

| code | 名称 | 默认权重 |
|---|---|---|
| `MIN_TARDINESS` | 最小超期 | 20 |
| `EDD` | 交期优先 | 15 |
| `PRIORITY_RESPECT` | 优先级尊重 | 15 |
| `SPT` | 短任务优先 | 10 |
| `SAME_PRODUCT_CONTINUOUS` | 同品连续生产 | 10 |
| `MIN_SETUP_TIME` | 最小换型时长 | 10 |
| `BALANCE_LOAD` | 均衡负载率 | 10 |
| `MIN_MAKESPAN` | 最小总完工时间 | 10 |

- `weight` 为整数 0–100，`0` 等价 `enabled=false`；**已启用规则权重之和必须等于 100**，否则 `42200`。
- `enabled` 缺省 `true`；`params` 仅允许各规则登记的键，未知键 `42200`。
- 硬约束（`resourceNoOverlap`、`calendarAvailability`、`operationDependency`、`frozenResource`、`frozenStartTime`、`primaryResourceOnly`）**不进入策略数组**，M2 固定启用。

**端点**：

| 方法/路径 | 说明 | 错误 |
|---|---|---|
| `GET /strategies` | 分页；支持 `name`、`direction`、`effectiveOn` | — |
| `POST /strategies` | 创建 | 40901 名称+版本重复、42200 权重和≠100 |
| `GET /strategies/{id}` | 详情（含 `rules`） | 40400 |
| `PUT /strategies/{id}` | 全量更新（`rules` 必填、`version` 必填） | 40901 版本冲突、42200 |
| `POST /strategies/{id}/clone` | 克隆，生成新 `strategyVersion` | 40400 |
| `GET /strategies/{id}/export` | 导出 `{strategy, rules, checksum}`（JSON）；`checksum` 必出现 | 40400 |
| `POST /strategies/import` | `{payload, dryRun}` → `{valid, errors[], strategyId, existing}`；`dryRun=true` 不落库 | 42200、40901 |

**导入导出形状裁定（2026-11-04 冻结；事实核对于同日源码）**：

- **导出 wrapper 保留**：`data = {strategy, rules, checksum}`。`checksum` 是**记录摘要（文件完整性）**：`SHA-256(canonical({strategy, rules}))`，其中 `strategy` 节点**包含** `id` 与乐观锁 `version`（hex 64 位），**必须出现**；缺 `checksum` 的载荷不得视为合格导出。**同一份内容从不同记录导出会得到不同 `checksum`，这是预期行为**（`id`/`version` 参与计算），它只用于回灌前判断文件是否被改动，**不得**当作内容同一性判据。
- **导入分两步，顺序不可颠倒**：
  1. **文件完整性**：用载荷中的原始 `strategy` 节点与 `rules` 重算上述记录摘要，与载荷 `checksum` 比较。`checksum` 缺失 / 格式非法 / 与重算值不一致 → `422/42200`，message 必须能区分这三类（不一致须体现「内容被改动」）。
  2. **内容同一性**：剔除 `strategy.id` 与 `strategy.version` 后再规范化取 SHA-256，与「同 `name` + 同 `strategyVersion`」的既有记录按**同一剔除口径**算出的摘要比对。相同 → `200` 幂等，返回既有 `strategyId` 且 `existing=true`，**不新建、不升版本、不写 rules**；不同 → `409/40901`，message 逐字点名 `name` 与 `version`，**禁止自动升版本**。
- **响应字段**：`{valid, errors[], strategyId, existing}`。`existing` 为 boolean、缺省 `false`，为 `true` 仅表示「本次没有新建、没有升版本、没有写 rules」；它与「200 + 相同 `strategyId`」**不等价**——`strategyId` 相同无法区分「本次新建后返回」与「从未写入直接返回」。
- **试运行**：`dryRun=true` 走与执行**同一套判定**（上述两步校验），只校验不落库；命中既有返回 `existing=true` 且 `strategyId=null`。`dryRun` 缺省 `false`。
- **实现事实**：`StrategyExportPayload`（`aps-service/.../service/strategy/StrategyExportPayload.java#L20-L29`）是 9 字段扁平载荷，**不含任何完整性校验字段**；`StrategyService#export`（#L282-L299）原样返回该扁平对象；`POST /strategies/import`（`StrategyController` #L145-L147）只接收该扁平载荷、**无 `dryRun` 参数**，`StrategyService#importStrategy`（#L307-L327）直接 `create` 落库。
- **处置**：两项均为**实现待补，M2 收口前必须补齐（不是 M3）**，责任人 W2——导出补 `checksum` 并按 wrapper 返回；导入补 `dryRun` 分支与校验报告。补齐前两个端点 `x-implementation-status` 保持 `partial`，不得翻 `implemented`。
- **`checksum` 的技术前置（规范化序列化）**：`checksum` 只有在**规范化 JSON 规则写死**时才有意义——必须固定①字段顺序（按 JSON 键字典序）②`null` 处理（缺省与 `null` 是否等价、是否省略键）③时间格式（`date` = `yyyy-MM-dd`，`date-time` = UTC ISO-8601 带毫秒）④空白与转义（无缩进、无多余空格、UTF-8）。规则未写死时同一份数据两次导出会得到不同 `checksum`，等于没有校验和。实现必须在导出实现处集中定义该规范化函数并对外写明；本节即为其契约引用点（补 `checksum` 时一并补规范化规则，不得只加字段）。
- **形状不向实现妥协**：wrapper 与 `dryRun` 是冻结形状，实现向契约收敛；不得为对称而改前端或后端代码的调用方式。

**DSL 端点（按 `code` 维度，唯一写者 W2；均返回 `Result<...>`）**：

| 方法/路径 | 请求 | 响应 / 错误 |
|---|---|---|
| `POST /constraint-dsls/validate` | `{yamlText}`（必填，≤256KB） | 200 + `DslValidationReport{valid, issues[{code, field, message}]}`；**不落库**，非法 DSL 仍返回 200（报告内 `valid=false`） |
| `POST /constraint-dsls` | `{code, yamlText}` | 200 + `ConstraintDslView`（新 DRAFT 版本，`versionNo` 自增）；校验失败 `42200`（message 附逐条 422xx 明细） |
| `GET /constraint-dsls?code=xxx` | — | 200 + 该 `code` 的全部版本，`versionNo` 倒序 |
| `GET /constraint-dsls/{id}` | — | 200 + `ConstraintDslView`；`40400` |
| `POST /constraint-dsls/{id}/activate` | 无 body | 200 + `ConstraintDslView`（状态 ACTIVE）；同 `code` 仅一条 ACTIVE；失败自动恢复上一 ACTIVE 并 `40901` |
| `POST /constraint-dsls/{id}/rollback` | 无 body | 200 + `ConstraintDslView`；当前 ACTIVE 置 `ROLLBACK`、目标版本置 `ACTIVE` 并记 `rollbackOf`；失败 `40901` |

**`ConstraintDslView` 字段**：`id`、`code`、`versionNo`、`status`（`DRAFT/ACTIVE/ROLLBACK`）、`checksum`（规范化 YAML 的 SHA-256）、`rollbackOf`、`effectiveFrom`、`yamlText`、`compiledSpec`（约束快照数组）、`createdAt`。

**受控 DSL（YAML v1.0）**：

```yaml
version: "1.0"
constraints:                      # 规范：数组
  - id: min_tardiness
    ruleCode: MIN_TARDINESS       # 白名单
    type: SOFT
    weight: 20
    enabled: true
    params: {}
constraint:                       # 兼容别名：单对象，等价 constraints 的单元素数组
  id: edd
  ruleCode: EDD
  weight: 15
```

冻结规则：

1. **`constraints[]` 为唯一序列化输出**；输入接受单数 `constraint:`（对象），解析时转为单元素数组。开关 `aps.dsl.legacy-singular` 默认 `true`，**保留到 M2 结束**，M3 清理。
2. 同时出现 `constraints` 与 `constraint` → `42200`（歧义）。
3. `type` 在 M2 只允许 `SOFT`；`ruleCode` 只允许 8 条软约束；`stream`、`condition` 等任意代码字段**一律拒绝**（`42200`），不做类加载、不做运行时编译。
4. YAML 使用 safe load，单文档 ≤256KB；未知字段、未知 `ruleCode`、权重越界均 `42200`，返回 `{field, code, message}` 列表。
5. `constraint_dsl` 落库字段（V4）：`id/tenant/code/version_no/yaml_text/compiled_spec JSONB/status/checksum/rollback_of/effective_from/created_by/审计字段`；同 `code` 仅一条 `ACTIVE`。
6. 激活失败自动回滚到上一个 `ACTIVE`，接口返回 `40901` 并附原版本号。

**兼容/回滚点**：`aps.dsl.legacy-singular=false` 关闭单数别名；`constraint_dsl` 停用后解析器退回「仅内置 8 条默认规则」，策略表 `strategy_rule` 不受影响。

#### 24.1.1 约束归因（Justification）：字段与端点（M2-SE-05，已实现）

**字段（`ConstraintJustification`；`constraint_justification` 表，V7 迁移新增 `weight`/`deduction`）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `constraintCode` | string | 规则键：软约束必须取自 `RuleCode`（24.1 上表 8 条编码），硬约束取自 `ConstraintCodes` 的 8 条硬约束编码；**禁止用中文标题当键**，中文只允许出现在 `description` |
| `type` | string | `HARD` / `SOFT`（M1 遗留硬约束归因保留） |
| `taskIds` | int64[] | 聚合后的受影响任务主键；求解期新解尚无数据库主键时，由结果写回阶段回填 |
| `description` | string | 面向人的中文说明（唯一允许中文出现的字段） |
| `weight` | integer \| null | 该规则求解时生效权重（`SoftConstraintWeights.active()` 快照）；**硬约束为 `null`** |
| `deduction` | decimal | 正数表示被扣掉的软分；**硬约束为 `0`**（非空，缺省归一为 `BigDecimal.ZERO`） |

- **聚合语义**：`deduction` 是**该规则聚合后的扣分**（按 `constraintCode` 聚合为一行，一次求解最多 8 行），等于 `weight × 违反量` 的汇总；**不是逐条违反事实的明细**。前端不得按“一行 = 一条冲突”渲染；`taskIds` 同理是聚合后的受影响任务集合。
- `deduction` 与求解结果的软分必须同口径：归因重放总额（`sum(deduction)`）应等于 `bestScore` 软分的相反数（`expectedSoft`）。实现会在 `SoftConstraintWeights.disable()` 之前比对，**不一致时不阻断求解，但必须输出结构化 `ERROR` 日志并带稳定标记 `JUSTIFICATION_SCORE_MISMATCH`**，字段至少包含 `solveTaskId`、`sumDeduction`、`expectedSoft`、`delta`（`delta = sumDeduction - expectedSoft`）。**该标记一旦出现即视为缺陷**：说明归因重放与约束口径已分叉，必须修复，不得当作日志噪音忽略、降级或采样。
- **不阻断是刻意选择**：归因只影响可解释性、不改已产出的排产结果；求解已完成、耗时已发生，此处抛出会把“解释口径误差”升级为求解失败。代价必须说清：**分叉期间归因行仍会返回，前端展示的扣分可能与实际软分不一致**；因此这条一致性只能靠上述 `ERROR` 标记被日志告警看见（默认级别下的 `WARN` 不可靠，不作为哨兵），不能靠运行时强制。

**端点**：

| 方法/路径 | 说明 | 错误 |
|---|---|---|
| `GET /solve-tasks/{id}/justifications` | 已实现；返回 `Result<ConstraintJustification[]>` | `40400`（任务不存在） |

- **不分页**：规模上限 8 行/次，`page/size` 参数一律忽略。
- **排序固定且已实现**：`deduction DESC, constraintCode ASC`（稳定序）。服务端仓储直接按 `ORDER BY deduction DESC, constraint_id ASC` 返回，服务层不做二次排序；前端不得自行排序兜底，服务端返回顺序即展示顺序。
- **空结果必须是 `200` + `data: []`**，不是 `404`：任务存在但未启用软约束、权重全为 0、或任务未到 `SOLVED` 都可能为空。
- 任务不存在 → `404`/`40400`，与 `GET /solve-tasks/{id}` 口径一致。
- 归因按 `solve_task_id` 隔离，只对当次求解生成；重跑/增量重排重新采集，不复用旧行。

**实现约束（留给后来人的警示）**：当前归因**不是** `SolutionManager.analyze()`（Timefold 2.8.0 社区版无 `ConstraintMatchTotal` / 该分析能力），而是**复用约束流同一套谓词（`SoftConstraintPredicates`）+ `SoftConstraintWeights.active()` 做同口径重放**（`SoftConstraintJustifications.from`），在求解结束、权重尚未 `disable()` 时采集。
- 因此**修改任一约束谓词、权重默认值或聚合口径时，必须同步修改归因重放**；否则归因与求解目标会悄悄分叉，而且不会报错。
- 这也是 `weight` 必须记录“求解时生效权重”而不是“策略表当前权重”的原因：策略后续修改不得改变历史归因的可解释性。

### 24.2 C20 异步求解生命周期、取消与 STOMP 进度（唯一写者 W3 `dev-SE3`）

**状态机**（对外枚举，`SolveTaskStatus`）：

`PENDING → RUNNING → SOLVED | FAILED | CANCELLED | TIMEOUT`

- **数据库映射（重要）**：`V1__init.sql` 的 `solve_task.status` CHECK 为
  `PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT`。M2 **不改 CHECK、不加迁移**：
  **DB 与 API 统一使用这六个值**，`SUCCESS` 已废弃；C1 中「持久层存 `SUCCESS`、API 暴露
  `SOLVED`」的过渡期表述自本节起作废。状态映射集中在服务层，控制器不再做任何对外改写。
- **单一响应形状**：`POST /solve-tasks` **一律**返回 `SolveTaskDetail`；`ScheduleService.submit`
  的同步/异步只是内部执行方式，不改变响应结构。`application.yml` 中的
  `aps.solve.async-enabled` 配置项与“同步返回 C1 `SolveResult` / 异步返回 `SolveTaskDetail`”
  的双形状分支均已删除；执行模式仅由服务内部实现决定（默认异步），对外不可配置形状。
  异步受理时状态为 `PENDING/RUNNING`，调用方轮询 `GET /solve-tasks/{id}` 到终态。
- **终态任务快照**：`SolveTaskDetail.tasks` 在 `status=SOLVED` 时返回该 `sandboxId` 当前
  `deleted=false` 的 task 快照（`orderNo/resourceCode/startTime/endTime`），其余状态为空数组。
  快照为场景维度（同一 sandbox 可能含其他会话订单的既有任务），不是“本次请求订单”子集。
- 同一 `sandboxId` 同时只允许 1 条 `RUNNING`；超出的提交保持 `PENDING` 排队。

**订单范围与状态过滤（2026-11-04 冻结；`POST /solve-tasks` 受理）**：

1. **订单不受沙箱约束**：`orders` 无 `sandbox_id` 列（见 C5 §7），订单只按 `tenant_id + deleted=false` 读取；`sandboxId` 只决定任务写入哪个场景，**不得**用于校验订单归属，也不存在「订单不在该沙箱」的错误分支。
2. 未显式传 `orderNos` 时，`onlyConfirmed=true` 生效：只取 `status='CONFIRMED'` 的订单。
3. **显式 `orderNos` 覆盖 `onlyConfirmed`**：显式点名代表调用方要排这些单，此时不加状态过滤；`DRAFT/SCHEDULED/RELEASED/IN_PROGRESS/COMPLETED` 照排，不拒绝。
4. **`CANCELLED` 是唯一例外，必须拒绝**：显式 `orderNos` 中出现 `status='CANCELLED'` 的订单时，返回 `400/40000`，message **逐字点名**该订单号（与「请求的订单号不存在」同族错误码）。理由：`CANCELLED` 是「这活不干了」的终态否决，排进去不会报错，只会让产能与交期算歪；显式点名覆盖的是「要排哪些单」，不是「这单已取消」。
5. **订单号不存在的错误码（2026-11-04 冻结）**：`orderNos` 出现在**请求体数组**中（批量语义），不是路径寻址对象；批量里有一个订单号不存在时，**拒绝整个请求**，返回 **`400/40000`**（不是 `40400`），message **逐字点名**不存在的订单号。**与 `40400` 的分界**：路径寻址（如 `GET /orders/{orderNo}`）格式合法但资源不存在 → `404/40400`；请求体内容（数组元素、字段值）校验失败 → `400/40000`。
6. **实现现状（缺口登记）**：`ScheduleRepository#findOrders`（`aps-infrastructure/.../ScheduleRepository.java#L54-L86`）显式 `orderNos` 时 `onlyConfirmed=false`，无 `CANCELLED` 拒绝；`ScheduleProblemAssembler#requireAllRequestedOrdersFound`（#L343-L359）只处理「查不到」且错误码为 `400/40000`（与第 5 条一致）。因此第 4 条（`CANCELLED` 拒绝）为 **M2 必须补（W4：装配器与受理入口校验；`m2-plan.md#L227` 将 `ScheduleProblemAssembler/ScheduleRepository` 归 W4，`orderNos` 存在性与 `pinnedOrderNos` 重叠校验亦由 W4 落地）**，补齐前 `POST /solve-tasks` 的该项子行为标 `partial`，不得计为已实现。

**终态判定：任务覆盖率与「`SOLVED` 即已落库」（2026-11-04 冻结；W2 约束 + W3 生命周期）**：

1. **期望任务数口径（唯一）**：`expectedTaskCount = Σ ScheduleProblem.orders[*].operations.size()`，即本次求解题面中每个订单按生效 ACTIVE 工艺路线逐条展开的工序总数（`operationIndex = routing_node.seq_no`）。装配阶段因缺 ACTIVE 路线、资源不可用等原因**未进入题面**的订单必须显式记录（`metrics.excludedOrders[]`，含 `orderNo` 与原因），且不计入 `expectedTaskCount`；同一 `sandboxId` 内不属于本次请求的既有任务不计入。**禁止**各流自行用“场景订单数 × 工序数”或“库内 `task` 行数”另算一套口径。
2. **未排位任务必须产生硬负分**：求解结果中任何一个期望任务没有获得资源与开始时间（未排位），每个这样的任务至少计 `1hard`；不得出现“部分排入、硬分为 0”的解。`bestScore` 硬分只有在**全部期望任务已排位且无其他硬违约**时才允许为 0。
3. **落库前完整性校验（强制）**：进入 `PERSISTING` 前必须比对 `expectedTaskCount` 与已排位数 `assignedTaskCount`；二者不等（含 `assignedTaskCount < expectedTaskCount`）**不得**返回 `SOLVED`。终态必须是 `FAILED`，`errorCode=FAILED`，`message` 必须同时给出期望数与已排位数。
4. **`SOLVED` 的充要条件（三条同时成立）**：
   - ① 硬分为 0（无硬违约）；
   - ② 覆盖完整：`assignedTaskCount = expectedTaskCount`；
   - ③ 结果**已成功落库**：事务提交成功，任务已写入，`metrics.persistedTaskCount = expectedTaskCount`，且库内 `task` 行数与返回一致。
5. **禁止的状态组合**：`SOLVED` + `persistedTaskCount=0`（或任何 `persistedTaskCount < expectedTaskCount`）**不得存在**；落库失败、事务回滚、写入行数不符 → 终态 `FAILED`（带原因），`phase` 不得为 `DONE`，`resultTaskId` 必须为 `null`。`SOLVED` 时 `phase` 只能是 `DONE`（与本节 `phase` 表一致）。
6. **`status` 与降级标记是两个正交维度（2026-11-04 冻结，替代原“任何降级结果不得包装成 `SOLVED`”）**：
   - `status` 只回答「**这个解能不能用**」：覆盖完整 + 硬分 0 + 已成功落库 → `SOLVED`（第 4 条）；
   - `degraded` / `engineFallback` / `engineFallbackReason` / `engineFallbackDetail` 只回答「**是不是走了替代路径**」；
   - 因此：**降级后若产出完整可行解并落库，`status` 可以为 `SOLVED`，但必须同时带 `degraded=true` 与 `engineFallback*` 标记**；
   - **不得把失败或不完整的结果称为 `SOLVED`；也不得在降级时不留任何标记**。理由：写死成「降级一律不能 `SOLVED`」会让一个合法的完整解被状态机否定，而调用方真正需要同时知道两件事——「这计划能用」与「但它不是原定引擎算的」，缺一不可；
   - `engineFallbackReason/Detail` 与 `status` **不互相推断**：不得从 `status=SOLVED` 推断「没降级」，也不得从 `degraded=true` 推断「失败」；
   - `TIMEOUT`、`FAILED` 仍不得为 `SOLVED`；`degraded=true` 不能替代第 4 条三项条件；`fallbackMode=PARTIAL`（M3 预留）若未来产出部分排位结果，其终态同样不得为 `SOLVED`。
7. **验收口径**：判定“求解成功”必须同时给出 `status=SOLVED`、`bestScore` 硬分 0、`persistedTaskCount = expectedTaskCount` 与库内 `task` 实际行数；仅凭 `status=SOLVED` 或 `0hard/0soft` 不得判定成功（压测已复现 `SOLVED/0hard/0soft/persistedTaskCount=0` 的假成功）。
8. **重叠守卫不得归因为引擎异常**：`ScheduleResultWriter` 落库前的**整表重叠校验**属**数据完整性守卫**——引擎正常返回、由守卫拦下重叠计划时，必须记 `status=FAILED`、`errorCode=FAILED`，`message` 带重叠对明细，**不得**置 `engineFallback*` 或 `degraded=true`（未走降级路径）。`ENGINE_ERROR` / `engineFallbackDetail=ENGINE_EXCEPTION` **只**留给引擎异常与结果口径不一致。

**`metrics` 键清单（2026-11-04 实测补登；`metrics` 仍为开放对象，键集合不冻结）**：

| 键 | 语义 |
|---|---|
| `expectedTaskCount` | `Σ orders[*].operations.size()`，装配剔除的订单不计入 |
| `assignedTaskCount` | 求解结果中已分配任务数 |
| `persistedTaskCount` | 实际落库行数；`TIMEOUT` / `FAILED` 恒为 `0` |
| `unassignedTaskCount` | 未排位任务数（每条 1 hard 惩罚） |
| `discardedBestSolution` | `true` 表示最优部分解被丢弃、未落库 |
| `moveThreadCountRequested` / `moveThreadCountResolved` | 请求值与解析值（缺省或 0 时 `Requested=0`） |
| `moveThreadCountEffective` | `DEFAULT \| NONE \| <引擎可识别值>`，`DEFAULT` 表示未显式设置 |
| `moveThreadCountApplied` | 是否真的写入了 `SolverConfig` |
| `moveThreadCountIgnored` | 请求值未被运行时支持（如社区版请求 7 → `Effective=NONE`、`Ignored=true`） |
| `multiThreadedAvailable` | 当前发行版是否具备多线程能力 |

- 全局 `default-property-inclusion: non_null` **不得**作用于 `metrics` 映射内部；`metrics` 的值若为 `null`，**必须原样输出为 `null`**（不得丢弃、不得替换成默认值）。原因：`null` 表示「确实没有该值」，与「没算/不知道」不同。

**`stepCount` / `unimprovedStepCount` 输出口径（2026-11-04 冻结）**：

- `stepCount` / `unimprovedStepCount` 自 M3 起**不再对外输出**。
- 原因：Timefold 2.8.0 社区版 `SolverJob` 无 `getStepCount()`；实现里三处构造恒写 `0L`（占位未接线），恒 0 会被读成「0 步」，属失真输出；且本键集合按本节本就「不冻结」。
- 若后续要暴露求解进度类指标，**必须为真实计数并新增独立键名**（如 `moveEvaluationCount`，来源 `SolverJob#getMoveEvaluationCount()`），不得复用 `stepCount` 这一名字，也不得用任何估算/推算值。

**已知限制：单进程求解并发度恒为 1（M2，W4 取证；不是 bug）**：

- `SoftConstraintWeights` 是**进程级静态 `AtomicReference`**；互斥由 `TimefoldSolverAdapter` 的 `private static final ReentrantLock SOLVER_LOCK` 保证，临界区覆盖 `activate → create/buildSolver/solve → disable`；权重在 `SolverFactory.create(...)` 时被约束流读取一次并固化。因此**同一进程内同一时刻只能有一个求解会话，并发度恒为 1**。
- 后到请求**排队等锁**；等待上限 `min(5, max(1, config.timeoutSeconds()))` 秒。等不到锁 → 任务 `FAILED`，`message="求解器正被其他求解任务占用，等待 N 秒仍未释放，请稍后重试"`——这是**可见的拒绝**，不是静默降级，也不会让后来的请求带着别人的权重去求解。
- 等锁期间任务已是 `RUNNING`（不会被回落成 `PENDING`）；超时则直接 `FAILED`，不产生结果行。
- 多 JVM 部署时锁与权重注册表**按进程隔离**，不存在跨进程串味；总并发度 = 实例数，单实例仍为 1。
- **验收含义（必须遵守）**：压测与联调用例**不得**把“并发发起求解”当作并行吞吐来度量——此时测到的是排队时间与拒绝率，不是引擎性能。要测吞吐必须多进程/多实例，或串行计时单次求解。
- **单订单子集重排未纳入场景内既有占用（M2 已知限制）**：请求只点部分订单、且场景内已有其他订单的任务、又未传 `pinnedOrderNos` 时，求解题面不含这些既有占用，落库前整表重叠守卫会判失败（这是**正确**拦截，不是 bug）。M2 不做自动收敛；该场景的正式语义归 **M3 增量重排**，M3 需明确“子集重排必须显式声明既有占用（pinned）或整场景重排”。

**端点**：

| 方法/路径 | 说明 |
|---|---|
| `POST /solve-tasks` | 提交；`engine` 缺省 `timefold`。**一律返回 `SolveTaskDetail`**（C20）：状态为六值枚举，`SOLVED` 时 `tasks[]` 为场景任务快照；同步/异步只是内部执行方式，不影响响应结构 |
| `GET /solve-tasks/{id}` | **事实源**；返回 `SolveTaskDetail`，终态含 `tasks[]` |
| `POST /solve-tasks/{id}/cancel` | 仅 `PENDING/RUNNING` 可取消；其他状态 `40901` |
| `POST /solve-tasks/incremental` | 见 C21 |

**`phase` 枚举（与 `status` 分离；唯一口径，见下表）**：

`phase ∈ {QUEUED, ASSEMBLING, SOLVING, PERSISTING, DONE, FAILED, CANCELLED, TIMEOUT}`

| phase | 出现时机 | 对应 `status` |
|---|---|---|
| `QUEUED` | 已受理、尚未开始（`PENDING`）；无进度值时的默认值 | `PENDING` |
| `ASSEMBLING` | 装配求解问题 | `RUNNING` |
| `SOLVING` | 求解中 | `RUNNING` |
| `PERSISTING` | 结果落库 | `RUNNING` |
| `DONE` | 求解成功且结果已落库 | `SOLVED` |
| `FAILED` | 求解失败 / 引擎异常 / 装配校验失败（`42200`） | `FAILED` |
| `CANCELLED` | 用户取消 | `CANCELLED` |
| `TIMEOUT` | 超时（看门狗或引擎超时） | `TIMEOUT` |

- **禁止混用两套值**：`phase` 不得出现 `INITIAL/OPTIMIZATION/FINALIZING`（那是 C19 策略规则的 `phase`，与求解任务无关），也不得出现 `PENDING/RUNNING/SOLVED`（那些是 `status` 值）。`status=SOLVED` 时 `phase` 必须是 `DONE`。
- 实现不得再用“终态名兜底”（`metrics.getOrDefault("phase", status.name())` 直接落 `SOLVED` 就是 W3 实测到的错值）；缺失时按上表映射，`PENDING` 一律归 `QUEUED`。

`SolveTaskDetail` 在 M1 schema 上新增：`phase`（上表枚举）、`resultTaskId`（可空，`SOLVED` 时等于 `taskId`）、`degraded`（boolean，见 C28）、`tasks[]`（`SOLVED` 时的场景任务快照，其余状态为空数组）。

**序列化约定（`GET /solve-tasks/{id}` 与 STOMP 通用）**：

1. **声明的字段一律输出**：`null` 表示“该阶段无此值”，**不是“字段不存在”**。禁止用键存在性判断业务状态（`overrides.primaryResourceId` 的 `INHERIT/FLOAT` 是存储层三态，不适用于进度报文）。
2. 只在特定阶段有值的字段**始终存在**，取不到时为 `null`：`resultTaskId` 仅 `SOLVED` 有值；`bestScore` 求解开始前为 `null`；`errorCode`/`errorMessage` 成功时为 `null`；`finishedAt` 未结束为 `null`。
3. 实现必须对 `SolveTaskDetail`/`SolveProgress` 显式 `@JsonInclude(ALWAYS)`；全局 `spring.jackson.default-property-inclusion: non_null` 不得作用于这两个报文。
4. `metrics` 是开放对象，键集合不冻结，前端不得依赖其键存在性。

**构建制品验收（重要）**：凡涉及进度/推送的验收（`phase`、STOMP 帧、`tasks[]`），必须在**由当前源码构建出的制品**上执行，不得使用正在运行的旧实例（旧 jar 可能仍发 `phase=SOLVED`）。结论“已修复”必须同时给出构建命令、启动的制品路径/命令与真实报文片段；“源码通过单测”不等于“运行中的制品通过”。

**STOMP 进度**：

- 端点：`/ws/solve-tasks/{id}`（STOMP over WebSocket，`{id}` 为 `solve_task` BIGSERIAL）。
- 订阅：`/topic/solve-tasks/{id}`；连接成功后服务端**尝试**立即推送一条当前快照——best-effort，不保证送达（见下）。
- 消息体（`SolveProgress`）：

```json
{
  "taskId": 123,
  "status": "RUNNING",
  "progress": 0.42,
  "phase": "SOLVING",
  "bestScore": "-12hard/-345soft",
  "elapsedMs": 1830,
  "resultTaskId": null,
  "errorCode": null,
  "message": "求解中，已耗时 1830 ms"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `phase` | string | 上表枚举；始终输出 |
| `progress` | number | 0–1；`PENDING=0`，终态=1 |
| `bestScore` | string | Timefold 分数文本；未知为 `null`（键仍存在） |
| `resultTaskId` | number \| null | 仅 `SOLVED` 有值，其余为 `null` |
| `errorCode` | string \| null | 失败/超时原因码（`ENGINE_ERROR`/`TIMEOUT`/`FAILED`/`CANCELLED`/`SOLVER_BUSY`）；成功为 `null`。`SOLVER_BUSY` 仅用于 C28 容量拒绝。**不是业务错误码整数** |
| `message` | string \| null | 面向用户的说明（如“求解中，已耗时 N ms”） |

- 推送节流：同状态变化立即推送，`progress/bestScore` 最多 1 次/秒；终态必推一次且含 `resultTaskId`。
- `degraded` **不在 STOMP 消息中**（实现无该字段），降级信息由 `GET /solve-tasks/{id}` 的 `degraded`/`metrics` 提供。
- **「订阅即推」是 best-effort，不是投递保证（2026-11-04 冻结）**：`StompSubProtocolHandler` 先把 `SUBSCRIBE` 异步投到 `clientInboundChannel`，随后才同步 `publishEvent(SessionSubscribeEvent)`；监听器收到事件即 `convertAndSend`，此时订阅可能尚未注册到 broker，快照会被静默丢弃。实测出现过断言超时 10.44s、而同 traceId 的服务端日志已有「推送求解进度快照」。这是**确定性时序竞态**，不是偶发网络问题；不得把首帧当作既有保证写进任何实现或测试假设。
- **客户端必须对账（强制）**：连接建立后**无条件**补一次 `GET /solve-tasks/{id}`，与推送帧按 `taskId/status/phase` 合并，不得把「首帧快照」当作唯一数据来源；**不得以「消除多余请求」为由删除该兜底**。
- **终态任务的额外风险（兜底存在的根本原因）**：已终态（`SOLVED/FAILED/CANCELLED/TIMEOUT`）的任务不会再有后续帧，首帧一丢，页面就永久空白且没有任何自愈机会；非终态任务至少还有后续状态帧可补。因此兜底不是优化项，是正确性项。
- **与轮询关系**：`GET /solve-tasks/{id}` 是唯一事实源；STOMP 只推送、不替代查询，不重放历史消息。前端断线后回退 2 秒轮询；**重连后先 `GET` 对账，再消费推送帧**。
- 回滚点 `aps.solve.progress-push=stomp|poll`：设为 `poll` 时不注册 WS 端点，前端仅轮询；`50010` 只用于 WS 通道故障，不影响查询。

### 24.3 C21 增量重排与锁定语义（唯一写者 W4 `dev-SE4`）

**端点**：`POST /solve-tasks/incremental`

```json
{
  "baseSolveTaskId": 120,
  "affectedTaskIds": [1001, 1002],
  "sandboxId": "00000000-0000-0000-0000-000000000401",
  "reason": "设备故障重排"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `baseSolveTaskId` | 是 | 作为 `startingSolution` 的基准解 |
| `affectedTaskIds` | 否 | 为空表示该基准解全部非冻结任务 |
| `sandboxId` | 否 | 缺省 BASELINE |
| `reason` | 否 | 写入新版本 `reason` |

冻结规则：

1. 增量求解**写入新 `solve_task` 行**，并在 C24 计划域生成新 `plan_version`（`DRAFT`）；**禁止覆盖已 `PUBLISHED` 版本的 `task` 行**。
2. 已发布且已执行（`IN_PROGRESS/COMPLETED`）的任务不得被增量结果修改，冲突返回 `40903`（与 C22 任务调整、C24 计划回滚同一语义“已执行进度不可倒带”，不得拆码）。**判定粒度为场景级**：`sandbox_id` 内存在任意 `IN_PROGRESS/COMPLETED` 任务即拒绝，不区分其是否属于本次增量范围；`40903` 只用于“回滚/覆盖与在制进度冲突”，发布幂等与版本演进发布一律 `200`。
3. `TaskAssignment` 新增：`lockType`（`NONE/TIME_WINDOW/RESOURCE/FROZEN`）、`lockedFrom/lockedTo`（`lockType=TIME_WINDOW`/`FROZEN` 时的闭区间，等价本文早期称法 `pinnedWindow{from,to}`，落地字段名为 `lockedFrom/lockedTo`）、`lockedResourceIds[]`（`lockType=RESOURCE` 时必填）。
4. 优先级：`FROZEN` > `lockType` > M1 的 `frozen:boolean`（仅为兼容别名，等价 `lockType=FROZEN`）。
5. 求解器必须保证 `TIME_WINDOW` 任务落在 `[from,to]` 内；`RESOURCE` 任务的**主资源必须属于 `lockedResourceIds`**（单元素集合=固定，多元素=白名单内可换，不是“主资源必须原样不变”）。若该任务主资源已被固定（`overrides.primaryResourceId` 非空），`RESOURCE` 锁不改变结果——这是 C15 20.3.1.2 说明的叠加结果，不是锁失效。

**锁定分层与冲突判定（订单冻结 `freezeLock` vs 任务锁，C23 反向引用本节）**：

判定顺序 `FROZEN(task.status) > task.lock_type > 订单 freezeLock` **只是判定先后**，不是"高优先级可以覆盖低优先级"——三层全部是硬约束，任何一层都不得被另一层或人工调整放宽。

1. **订单级与任务级同时生效，取交集**：任务级可以比订单级**更窄**（订单锁窗 `10:00–12:00`、任务锁 `10:30–11:00` 合法），**不得更宽**（任务锁 `08:00–09:00` 而订单锁 `10:00–12:00` 非法）；资源同理，任务锁的资源集合必须是订单锁定资源集合的**子集**。
2. **空交集 = 不可行**：在 `POST /tasks/{id}/adjust` 请求阶段直接拒绝（fail fast），不得留到求解阶段才发现。
3. **错误码 `42201`**（语义与代码见 C27/24.9）：响应 `message` 必须**同时列出**冲突的订单约束（`orderNo` + `freezeLock{from,to,resourceCodes}`）与任务约束（`taskId` + `lockType/lockedResourceIds`），让人知道该改哪一层。
4. **绝不允许静默放宽任何一层**：不允许"订单优先、任务锁作废"，也不允许"手工优先、订单冻结作废"。求解阶段若仍发现冲突，按 C28 走失败/降级标记，不得静默放宽。
5. **`FROZEN` 粒度（2026-11-04 澄清，W4 实现依据）**：
   - **任务级 `task.status='FROZEN'`（及 `task.lock_type<>'NONE'`）**：只钉住**该任务本身**（固定开始/结束/主资源）；**同订单的其他任务仍可重排**，必须作为固定赋值（基准解快照）进入增量题面，结果不得改动其时间与资源。
   - **订单级 `orders.freeze_status`（C23 四态）**：约束该订单的**全部任务**，属订单语义；订单冻结时该订单全部任务按 `freezeLock` 固定/不可重排。
   - **关系**：订单级是外层约束，任务级是内层约束，**取交集、互不替代**；订单解冻不自动解除任务级 `FROZEN`，任务解冻也不改变订单冻结。订单未冻结时，订单内未被任务级锁定的任务正常参与增量重排。
6. **禁止“整单排除”实现**：不得把 `task.lock_type<>'NONE' OR task.status='FROZEN' OR orders.freeze_status<>'UNFROZEN'` 统一映射为“排除整个 `order_id`”。正确判定为按任务区分：任务级命中 → 只固定该任务；仅订单级命中 → 固定该订单全部任务。`affectedTaskIds` 仍表示“可重排任务”，被固定任务不进入可变集合，但**必须保留在基准解快照中**。
7. **`TIME_WINDOW` 为闭区间（权威口径）**：任务时间必须满足 `start >= lockFrom && end <= lockTo`，两端恰好命中均合法（W2 实测 `hardScore=0`）。
   - `V5__task_lock.sql` 中 `locked_to` 的列注释“结束（不含）”为**历史遗留、已过时**，以本条为准；**不得修改 V5 本体**（已应用，改注释即触发 Flyway 校验和不一致）。
   - **零长度窗（`from == to`）** 被 `CHECK (locked_to > locked_from)` 拒绝，属**预期行为**：调用方应在请求阶段返回 **400 + `40000`**，不得变成 500/内部错误；若需修注释，只能新增迁移 `COMMENT ON COLUMN task.locked_to IS ...`。
8. **基准解范围与历史数据（2026-11-05 收紧决定）**：`solve_task.config.orderNos` 在提交求解时固化该次求解的订单范围；缺省 `affectedTaskIds` 的“该基准解全部非冻结任务”以**该范围**为准，不得越出到同场景其他客户/其他批次的订单；显式 `affectedTaskIds` 越界返回 `42200`。**基准求解任务缺少 `orderNos` 键（或为空数组）时，默认路径与显式路径一律返回 `42200`**，`message` 必须带 `solveTaskId=<id>` 并提示“请先对该场景执行一次全量求解”——**不再回退“场景全量”**。
   - 2026-11-05 收紧原因：无范围基准下回退场景全量，会静默把同场景其他客户/批次的订单纳入重排，属事后几乎无法辨认的数据污染；拒绝只是让用户补做一次全量求解。本项目无生产历史包袱，演示种子数据已确认全部带 `orderNos`（`missing_orderNos=0`），该收紧不误伤既有演示数据。
   - 不采用“给历史行补 `orderNos`”兜底：历史 `config` 不含订单范围，只能按场景全量补，等于未修复。

**兼容/回滚点**：`lockType` 缺省 `NONE`，旧客户端只传 `frozen` 时行为与 M1 一致；`aps.schedule.lock-enabled=false` 时退化为布尔冻结。

#### 24.3.1 `POST /solve-tasks/incremental` 响应结构（2026-11-04 冻结）

**结论：沿用项目统一外壳，`data` 为 `IncrementalSolveResult`，不是裸对象。**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "planId": "00000000-0000-0000-0000-0000000009a1",
    "solveTaskId": 121,
    "baseSolveTaskId": 120,
    "sandboxId": "00000000-0000-0000-0000-000000000401",
    "affectedOrderCount": 2,
    "orderNos": ["SO-001", "SO-002"],
    "skippedLockedTaskCount": 0,
    "solveTask": { "…": "C20 SolveTaskDetail，形状同 GET /solve-tasks/{id}" }
  },
  "traceId": "..."
}
```

| 字段（`data` 内） | 类型 | 说明 |
|---|---|---|
| `planId` | string（UUID） | 新建 `DRAFT` 的 `plan_version.id`（对外 `planId`） |
| `solveTaskId` | integer（int64） | 新 `solve_task` 主键 |
| `baseSolveTaskId` | integer（int64） | 基准求解任务主键，原样回显 |
| `sandboxId` | string（UUID） | 场景标识 |
| `affectedOrderCount` | integer | 参与本次重排的订单数 |
| `orderNos` | string[] | 参与本次重排的订单号 |
| `skippedLockedTaskCount` | integer | 因锁定/冻结被跳过、未参与重排的任务数 |
| `solveTask` | object | C20 `SolveTaskDetail`，与 `GET /solve-tasks/{id}` 同形状 |

- **是否为特例**：不是。`code/message/data/traceId` 是本项目全部同步端点的统一外壳（`aps-common` 的 `Result<T>` 记录），增量重排与 `POST /solve-tasks`、`GET /solve-tasks/{id}`、取消、DSL 等一致。核对源码：`SolveTaskController#incremental` 的返回类型为 `Result<IncrementalSolveService.IncrementalSolveResult>`，`Result` 为 `record Result<T>(int code, String message, T data, String traceId)`。
  - 真正的“裸对象”特例只有 M3 异步导入任务端点（`GET/POST /{entity}/import/jobs/{jobId}[/cancel]` 返回裸 `ImportJobView`，2026-11-04 已按实际登记），与本端点无关。
- **为什么不改成裸对象**：裸对象会与其余 60+ 端点分叉，前端拦截器/错误处理需要为此单开分支；且 `traceId` 是排障链路的一部分，裸对象无法携带。若未来确实要取消外壳，应作为全项目统一变更单，不得只对增量端点特殊化。
- **与 `ResultOfSolveTaskDetail` 的关系**：`data` 不是 `SolveTaskDetail`，而是 `IncrementalSolveResult`；`solveTask` 字段内部才是 `SolveTaskDetail`。yaml 已改为 `ResultOfIncrementalSolveResult`（`docs/api/openapi/solve.yaml`）。

### 24.4 C22 甘特人工调整与撤销重做（唯一写者 W4 `dev-SE4`）

**端点**：

| 方法/路径 | 说明 |
|---|---|
| `POST /tasks/{id}/adjust` | 人工调整；`dryRun=true` 只校验不落库 |
| `POST /tasks/adjustments/{adjustmentId}/undo` | 撤销最近一次（按任务栈） |
| `POST /tasks/adjustments/{adjustmentId}/redo` | 重做 |
| `GET /tasks/{id}/adjustments` | 调整历史（分页） |

**`TaskAdjustRequest`**：

```json
{
  "operation": "MOVE",
  "resourceCode": "R-001",
  "secondaryResourceCodes": [],
  "startTime": "2026-11-05T08:00:00Z",
  "lockFrom": null,
  "lockTo": null,
  "lockedResourceCodes": [],
  "reason": "插单",
  "version": 3,
  "dryRun": false
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `operation` | 是 | `MOVE/LOCK/FREEZE/UNFREEZE`；`SPLIT/MERGE` M2 冻结枚举但返回 `50100`（**已被 §25.4 C36 取代**：SPLIT/MERGE 已冻结并实现，此结论仅保留历史追溯） |
| `version` | 是 | 与 `task.version` 比对，过期 `40901` |
| `dryRun` | 否 | 默认 `false` |
| `lockFrom/lockTo` | `LOCK` 时 | 时间窗；`lockTo > lockFrom` |
| `lockedResourceCodes` | `LOCK` 时可选 | 为空表示仅锁时间 |

- 响应 `TaskAdjustResult{adjustmentId, applied, dryRun, task, conflicts[]}`；`conflicts[]` 每项 `{type, message, relatedTaskIds}`。
- `FROZEN` 任务拒绝 `MOVE`（`42201`）；`FREEZE` 必须带 `reason`。
- 错误码：`40901` 版本冲突、`42201` 不可行（日历/依赖/资源冲突，以及**任务锁与订单 `freezeLock` 冲突**——判定与取交集规则见 C21/24.3，请求阶段 fail fast，message 同时列出订单约束与任务约束）、`40300` 无调整权限（M2 为占位，默认放行，M3 接 RBAC）、`40903` 已执行进度不可倒带（**目标任务是 `IN_PROGRESS/COMPLETED` 时拒绝，任务级判定：只判目标任务，不扩大到场景**）。
- `40903` 是**同一语义的多入口**，不是多个错误码：任务调整（本节，任务级）、增量覆盖（C21，场景级）、计划回滚（C24，场景级）返回同一个 `40903`，调用方可走同一条“提示用户不可回退”路径；禁止拆码。
- 调整记录写 `task_adjustment`（V1 已建表）；撤销/重做栈每任务最多 50 步。

### 24.5 C23 订单优先级、四态冻结与释放/开工（唯一写者 W5 `dev-BD5`）

| 方法/路径 | Body | 说明 |
|---|---|---|
| `PUT /orders/{orderNo}/freeze` | `{freezeStatus, lockFrom?, lockTo?, lockedResourceCodes?, version}` | 四态冻结；`UNFROZEN` 即解冻，无独立 `/unfreeze` |
| `PUT /orders/{orderNo}/priority` | `{priority, version}` | `URGENT/HIGH/NORMAL/LOW` |
| `PUT /orders/{orderNo}/release` | `{reason?, version?}`（body 可空） | **M2 纳入**：`DRAFT/CONFIRMED → RELEASED`，同时自动冻结该订单全部任务 |
| `PUT /orders/{orderNo}/start` | `{version}` | `RELEASED → IN_PROGRESS`，同样自动冻结 |

冻结校验（按 W5 BD-02 实际实现定稿）：

1. `LOCKED_TIME_WINDOW` 必须给 `lockFrom/lockTo` 且 `to > from`；**缺任一或 `from == to` → HTTP 400 + `40000`**（不是 422）。时间窗为**闭区间 `[from,to]`**（权威口径见 C21 §24.3 第 7 条；V5 的列注释已过时，不得改 V5）。
2. `LOCKED_RESOURCE` 必须给 `lockedResourceCodes[]`，逐项 `trim`、丢弃空串并去重后非空；仍为空 → `42200`。
   - **存在性校验（2026-11-04 增补，叠加在“非空”前置之上）**：去重后的每个编码必须能在当前租户的 `resource` 表中查到（`deleted=false`）；任一编码不存在 → **HTTP 422 / `42200`**，message 逐字点名缺失编码，形如 `锁定资源不存在: [RES-NOPE]`。实测：`lockedResourceCodes:["RES-NOPE"]` → `422 / 42200 "锁定资源不存在: [RES-NOPE]"`。原“非空”要求不删除，仍作为前置条件先执行。
3. `UNFROZEN/FROZEN` 不得携带锁字段；切换时**自动清空锁信息**（幂等，不报错）。
4. 锁信息不新增列，统一落 `orders.ext_attrs.freezeLock`（结构 `{from,to,resourceCodes}`）；`OrderView` 新增只读字段 `freezeLock`，空值时为 `null`。
5. 自动冻结写入 `task.lock_type='FROZEN'`（V5），范围=该订单当前全部任务；解冻必须人工显式调用 `/freeze {freezeStatus:UNFROZEN}`。
6. 最小审计：每次状态变更写 `audit_log(action, target_type='ORDER', target_id, operator, before_value, after_value, occurred_at)`；M2 不做全量审计中心。
7. 乐观锁：`version` 与 `orders.version` 不一致返回 `40901`；非法状态流转（如 `COMPLETED → RELEASED`）返回 `42200`。
8. `/release` 幂等：重复释放返回 200，`version` **不变**，不重复写审计；状态不允许释放 → `422/42200`。
9. 优先级只影响软约束权重（`PRIORITY_RESPECT`），不改硬约束；`URGENT` 不提供抢占/插队语义。
10. 订单级 `freezeLock` 与任务级锁同时生效、取交集；空交集 `42201`，判定规则见 C21/24.3，**不得以订单优先级覆盖任务锁，也不得反过来**。

### 24.6 C24 计划版本发布/回滚与快照（唯一写者 W5 `dev-BD5`）

| 方法/路径 | Body | 说明 |
|---|---|---|
| `GET /plans?sandboxId=&page=&size=` | — | 场景内计划版本列表（分页，已实现，W5） |
| `POST /plans/{id}/publish` | `{reason, approver}` | `id` = `plan_version.id`（UUID） |
| `POST /plans/{id}/rollback` | `{targetVersionNo, reason}` | 生成**新版本**，不物理回退 |
| `GET /plans/{id}` | — | 版本详情与任务数 |

冻结：

1. `plan_version` 字段：`id/tenant/sandbox_id/plan_no/version_no/status/snapshot JSONB/published_at/published_by/approver/rollback_of/reason/task_count/version/审计字段`；`plan_no` 形如 `PLAN-YYYYMMDD-NNN`，租户内唯一；`version_no` 从 1 递增。
   - **`approver` 为契约字段（V3 建列）**：可选 `varchar(64)`；`POST /plans/{id}/publish` **未传或空白（`trim` 后为空）→ 存 `NULL`，不回退到 `published_by`**——审批人必须是真人，禁止用机器标识冒充审批人。回滚生成的新版本沿用请求操作人，不继承原版本 `approver`。
   - **响应与列表的 `approver` 如实为 `null`**：前端**不得**用 `published_by` 顶替显示审批人；需要展示“操作人”时另用 `published_by` 字段，不得混用。
2. `status` 枚举 `DRAFT/REVIEW/PUBLISHED/ARCHIVED`（V3 CHECK）。
3. 发布：`snapshot` 固化当次全部任务（不可变），并回填 `task.plan_version_id`；**重复发布同一 `plan_version` 幂等返回 200**（`affectedTaskCount=0`，不报错、不返回 `40903`）。场景内已有 `PUBLISHED` 版本时，再发布另一个 `DRAFT/REVIEW` 版本**允许**，返回 `200` 与该版本自身的 `planId`：版本演进是正常操作，`40903` 不用于发布路径。
   - **`task.plan_version_id` 与两套任务口径（2026-11-05 澄清，实现事实记录，不改端点/字段）**：该列是“任务最近一次被发布回填的版本指针”，**可空**；`V3__plan_version.sql` 列注释明确 `NULL 表示尚未发布`。增量入口生成的 `DRAFT` 用 `snapshot.scopeOrderNos` 记录本次求解范围，发布只把**该范围内**的任务回填到本版本（`PlanVersionRepository#backfillTasks`），范围外任务保持原有绑定或 `NULL`——这是设计语义（尚未被任何发布覆盖的当前工作集），**不是残留脏数据**，不应按“未落版本”告警。因此 `plan_version.task_count` 与 `GET /plans/{id}` 的 `tasks[]` 是**该版本快照范围内的任务数**；甘特（`/gantt/**`）按 `sandbox_id` 直接读 `task` 表、不按 `plan_version_id` 过滤，展示的是场景当前工作集。两者口径不同，**不得互相校验为应当相等**。
4. 回滚：新建 `plan_version`（`status=PUBLISHED`、`rollback_of=原版本`），把任务指向新版本；**场景内（`sandbox_id`）存在已执行任务（`IN_PROGRESS/COMPLETED`）时拒绝，返回 `40903`**（与 C21 增量、C22 任务调整同一语义“已执行进度不可倒带”，不得拆码；判定粒度为场景级，不是目标版本内计数；见 C27）。回滚**不校验**“当前版本是否已发布”，只要求 `targetVersionNo` 指向该场景内一个 `PUBLISHED` 历史版本（不存在则 `40400`）。
5. MES 下发仅占位（`50100`），M2 不做外部系统推送。（**已被 §25.5 C33 取代**：计划下发/状态查询已冻结并实现，此结论仅保留历史追溯；C33 仍明确不做真实外部推送，仅落 outbox。）
6. `planId` 一律使用 `plan_version.id`，**不再用 sandboxId 充当计划 id**。
7. **占位退役（非契约内容，实施备注）**：`aps-web/.../controller/contract/GanttContractController` 的 `/plans/{id}/publish`、`/plans/{id}/rollback` 两个 501 占位已授权 W5 在 BD-03 落地时删除，避免与真实 Controller 产生 Ambiguous mapping。删除后本文件的端点定义仍以此处为准，OpenAPI 的 `x-implementation-status` 由 W5 同步更新。
8. **计划版本列表 `GET /plans`（C24 新增，2026-11-04 冻结；实现归 W5）**：端点状态=**已实现**（W5，2026-11-04 真实 HTTP 验收）。已验证行为：空沙箱 `200 + items:[] + totalPages:0`；越界页（`page=9&size=2`）如实回边界（`total=5/totalPages=3`，不报错）；缺 `sandboxId` → `400/40000`；排序实测 `v5→v4→v3→v2→v1`（`published_at` 递减）。本节只记录已验证事实，不含性能/压测结论。
   - 请求：`sandboxId`（必填，UUID）、`page`（默认 1，`≥1`）、`size`（默认 20，上限 200，超限按 200 处理，不报错）。**不提供 `sort`**：排序固定 `published_at DESC, id DESC`（同一时间戳用主键倒序兜底，保证翻页稳定）。按 PostgreSQL 默认语义 `DESC` 即 `NULLS FIRST`，因此 `published_at` 为 `NULL` 的 `DRAFT/REVIEW` 版本排在最前，其后才是按发布时间倒序的已发布版本。
   - 参数校验：缺 `sandboxId` 或非 UUID → `40000`；`sandboxId` 对应场景不存在 → `40400`。租户由上下文注入，**不接受请求参数**。
   - 过滤：`tenant_id = 当前租户`、`sandbox_id = sandboxId`、`deleted = false`。
   - 响应 `data` 使用统一分页结构（`conventions.md` §4，分页口径同 C27 §24.9）：`{items[], page, size, total, totalPages}`；空结果返回 `200` + `items: []`。
   - `items[]` 字段（**只有以下字段，不含任务明细、不含 `snapshot`**）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `planId` | string（UUID） | `plan_version.id`，对外 `planId` |
| `planNo` | string | `PLAN-YYYYMMDD-NNN` |
| `version` | integer | 业务版本号（`version_no`，同场景从 1 递增）；**不是乐观锁列 `plan_version.version`** |
| `status` | string | `DRAFT/REVIEW/PUBLISHED/ARCHIVED` |
| `publishedAt` | string（date-time）\| null | 未发布版本为 `null` |
| `publishedBy` | string（UUID）\| null | 发布操作人 |
| `approver` | string \| null | 审批人；未填时同发布规则（见第 1 条） |
| `taskCount` | integer | 快照任务数 |
| `rollbackOf` | string（UUID）\| null | 回滚来源版本主键；非回滚版本为 `null` |

   - 任务明细只能走 `GET /plans/{id}`；列表端点**不得**内联 `tasks[]` 或 `snapshot`，避免列表拖慢。
   - 只读端点，不要求 `Idempotency-Key`。

### 24.7 C25 库存落表与 MRP 齐套（唯一写者 W6 `dev-MRP`）

**V2 表**（`db/migration/V2__inventory.sql`）：

| 表 | 关键列 | 约束 |
|---|---|---|
| `inventory` | `id/tenant_id/material_id/warehouse_code/on_hand_qty/safety_stock/snapshot_at/version/审计字段` | `(tenant_id, material_id, warehouse_code) WHERE deleted=false` 唯一；`on_hand_qty >= 0`；`safety_stock >= 0` |
| `inventory_transit` | `id/tenant_id/material_id/qty/expected_date/source_type/status/审计字段` | `qty > 0`；`(tenant_id, material_id, expected_date)` 索引；`status IN (OPEN,ARRIVED,CANCELLED)` |

**端点**：

| 方法/路径 | 说明 |
|---|---|
| `PUT /inventory` | 批量 upsert；body `{items:[{materialCode,warehouseCode,onHandQty,safetyStock,snapshotAt}]}`；返回 `{accepted, rejected}` |

- `PUT /inventory` 按 `materialCode + warehouseCode` upsert，`snapshotAt` 缺省当前 UTC；物料不存在 `40400`、数量为负 `42200`、并发版本冲突 `40901`。
- **M2 不做库存维护页**（归 M3）；该端点供 Excel/脚本灌数，W9 的 `POST /{entity}/import` 可调用同一服务。
- **`POST /mrp/analysis` 请求增量**：`blocking`（boolean，默认 `true`）——`true` 时齐套未通过阻止排产提交（`42200` + 缺料明细）。
- **`MrpAnalysis` 响应增量**：

```json
{
  "sandboxId": "...",
  "analyzedAt": "2026-11-05T02:00:00Z",
  "status": "FULL|PARTIAL|SHORTAGE",
  "blocking": true,
  "orders": [
    { "orderNo": "SO-001", "status": "SHORTAGE", "shortageCount": 2 }  // 已被 §25.16 取代：实际输出 kitted/shortages[]
  ],
  "shortages": [
    {
      "materialCode": "M-001",
      "requiredQty": 120,
      "onHandQty": 40,
      "transitQty": 30,
      "safetyStock": 10,
      "availableQty": 60,
      "shortageQty": 60,
      "earliestRequiredAt": "2026-11-06T00:00:00Z",
      "affectedOrderNos": ["SO-001"]
    }
  ]
}
```

- `availableQty = onHandQty + transitQty - safetyStock`，下限 0；`transitQty` 只计 `status=OPEN` 且 `expected_date` 在需求时点前。
- **历史标注（2026-11-04）**：本示例 `orders[]` 的 `status` / `shortageCount` 已被 §25.16 取代——实际响应使用 `kitted` / `shortages[]`，映射为 `status ← kitted`、`shortageCount ← shortages.length`；旧字段不再返回，上述 JSON 仅保留历史追溯。
- **迁移注释冲突（2026-11-04 登记）**：`db/migration/V2__inventory.sql:71` 的 `COMMENT ON COLUMN inventory_transit.expected_date` 写“M2 按契约计入全部非 CANCELLED 在途，不按到期日裁剪”，与本条“只计 `status=OPEN` 且在需求时点前”冲突。迁移已应用、不得修改；**一律以本节 §24.7 为准**，迁移注释仅作历史陈述。实现按本节裁剪（修复批次已验证）。
- `FULL` 表示全部需求满足；`PARTIAL` 部分缺料；`SHORTAGE` 存在关键缺料。只要 `status != FULL` 且 `blocking=true`，排产提交返回 `42200`。
- MRP 需求来自订单生效工艺路线的 BOM 多级展开，按 `requiredAt` 倒排；递归展开需检测环（`42200`）。

**兼容/回滚点 R2**：`aps.mrp.inventory-enabled=false` 时 MRP 退回请求体快照模式；新增表可整体 `DROP`，不改既有表。

### 24.8 C26 Excel 导入导出（唯一写者 W9 `dev-QA2`）

| 方法/路径 | 说明 |
|---|---|
| `GET /{entity}/import/template` | 下载模板（xlsx），`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `POST /{entity}/import?dryRun=true` | 预览校验，返回逐行结果，不落库（**已被 §25.10 C39 取代**：改用 `mode=preview`，此结论仅保留历史追溯） |
| `POST /{entity}/import` | 执行导入；`Idempotency-Key` 必填（**已被 §25.10 C39 取代**：改用 `mode=execute` + `X-Import-Token`，此结论仅保留历史追溯） |
| `GET /{entity}/export` | 导出当前筛选结果 |

- 错误行结构：`{row, field, code, message}`；整体返回 `Result<ImportResult{total, success, failed, errors[]}>`。
- 上限：文件 ≤5MB、数据行 ≤5000；超限 `42200`。
- **M2 冻结名单（2026-11-04 冻结，以实现交付与 Excel 端到端证据为准），共 9 类**：
  `materials`、`boms`、`resources`、`calendars`、`operationTemplates`、`routings`、`orders`、`strategies`、`suppliers`。
  - 依据：9 类均有模板生成、preview、execute 落库、export 的实测证据（`docs/testing/m2-bd-05-import-export.md#L18-L38/#L141`、`docs/plan/m2-todo.md#L11/#L13`）；枚举以 `ImportEntity`（`aps-service/.../importexport/ImportEntity.java#L11-L120`）为准。
  - **来源说明（一句话）**：`customers` 未交付、**不在 M2**；`suppliers` 已交付并有端到端证据，故纳入——「没有 `customers`」是交付范围，不是漏项。
  - `customers` 未实现，**不在 M2 冻结面**；客户/供应商的扩展（含新增 `customers`）在 **M3 单开卡片**，M2 不再挂待定。
  - `换型矩阵`不在 M2（C12 降级），故不进清单；策略导入导出走 C19。
- 导入复用各域现有 Service 校验，不绕过业务规则；`dryRun` 与执行共用同一校验器。

**参数取代关系（2026-11-04 冻结）**：§25.10 C39 的 `mode`（`preview|execute|async`）与 `X-Import-Token` **取代**本节 C26 的 `dryRun` 查询参数与 `Idempotency-Key` 头。C26 的校验语义（预览只校验、执行共用校验器、错误行结构 `{row,field,code,message}`、≤5MB/≤5000 行上限）继续有效，仅**触发方式**与**幂等载体**变更；`preview/execute` 的 `Result<ImportResult{total,success,failed,errors[]}>` 形状不变。验收与实现一律以 §25.10 C39 为准，不得再按 `dryRun`/`Idempotency-Key` 实现或验收。C26 原文保留，仅作历史追溯。

### 24.9 C27 REST 认证分级、限流、幂等与错误码（唯一写者 W1 `dev-CONTRACT`）

**认证分级（按 profile）**：

| 模式 | 触发 | 规则 |
|---|---|---|
| `prod` | `spring.profiles.active=prod`（或显式 `mode=prod`） | **全模块强制 JWT**（`Authorization: Bearer`，HS256）；缺失/过期 `40100`，签名有效但缺少 `APS_USER` 角色 `40300` |
| `dev` | **显式开启**：`APS_SECURITY_MODE=dev`（或 `--aps.security.mode=dev`） | **仅写端点**（`POST/PUT/PATCH/DELETE`）强制认证；`X-API-Key` 直通（写密钥 200，读密钥 `40300`，缺失/错误 `40100`）；GET 放行 |
| `off` | **默认**（非 prod 且未显式配置）或 `APS_SECURITY_MODE=off` | 全放行，保证本地 demo/回归脚本（不带任何凭证）可整链跑通 |

- **默认值（冻结）**：不配置 `APS_SECURITY_MODE` 时，`prod` profile → `prod`；**其余（dev/test/无 profile）一律 `off`**。即"默认不挡人，prod 必挡人"。
- 覆盖开关：`APS_SECURITY_MODE=prod|dev|off`，优先级高于 profile 推断；本地要验证认证行为须显式开启 `dev`。
- 校验顺序：`TraceId → 认证 → 限流 → 幂等 → Controller`；认证失败不再消耗限流额度。
- 登录页/令牌签发属 **M3-FE-02**，M2 不提供 `/auth/login` 实现（保持 `50100`）；QA 联调用 API Key。
- API Key/JWT 密钥只从环境变量读取，禁止入库、入日志、入审计。

**限流**：

- 阈值：100 req/min，按 `tenantId + 调用方标识`（JWT `sub` 或 API Key 指纹）；`off` 模式默认关闭。
- 响应头：`X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset`（UTC 秒）。
- 超限：HTTP 429 + `code=42900`。
- M2 实现为单实例内存令牌桶；多实例需换 Redis（接口不变）。

**幂等**：沿用 `docs/api/conventions.md` §5（`Idempotency-Key` 8–128 位，重复 `40900`，失败自动释放）。M2 新增 V6 数据库唯一索引作为多实例兜底：`(tenant_id, sandbox_id, idempotency_key) WHERE idempotency_key IS NOT NULL`。

**错误码新增**：

| code | HTTP | 默认 message | 场景 |
|---|---|---|---|
| `40903` | 409 | 已发布的计划/任务不可回滚或覆盖 | **仅**用于“已执行进度不可倒带”，同一语义、多入口、不得拆码：增量覆盖（C21，场景级）、任务调整（C22，任务级）、计划回滚（C24，场景级）。发布幂等、版本演进发布不得返回本码 |
| `42201` | 422 | 调整不可行 | 甘特调整触发日历/依赖/资源冲突（C22）；**任务锁与订单 `freezeLock` 空交集冲突**（判定与取交集规则见 C21/24.3）；message 必须同时列出冲突的订单约束与任务约束 |

> `42201` 已在 `aps-common` 落地：`ErrorCode.ADJUSTMENT_INFEASIBLE`（`aps-common/src/main/java/com/hengce/aps/common/exception/ErrorCode.java`），HTTP 映射见 `RestExceptionHandler`（`UNPROCESSABLE_ENTITY`）；枚举名沿用，不改名以兼容 W4 已写入的引用。

其余沿用：`40100/40300/40400/40900/40901/42200/42900/50000/50010/50100`。

- **`400/40000` 与 `404/40400` 的分界（2026-11-04 冻结，T2/T3 裁定）**：非法 UUID、非法时间/枚举/类型、请求体必填缺失或值不合法 → **`400/40000`**；格式合法但**路径**指向的资源不存在 → **`404/40400`**；请求体批量数组（如 `orderNos[]`）中任一元素不存在 → **`400/40000`** 整批拒绝并逐字点名（见 §24.2 第 5 条）。**非法 UUID 一律 `40000`，不得用 `40400`**——参数格式错误不是资源不存在。

**分页**：`page ≥1`、`size` 默认 20 上限 200，超限按 200；`sort=field,asc|desc`，未知字段 `40000`。

- `GET /resources` 同样受 `size ≤ 200` 约束；服务端**不会**为超过 200 的 `size` 放大返回。
- 需要超过 200 条资源时的取数口径（按优先级）：
  1. **甘特/排产场景不枚举资源**：直接用 C4 `GET /gantt/resource` 行上的 `calendarId/calendarBound`（见 C4 增量），避免 N+1 与分页截断。
  2. **按 code 批量**：`GET /resources?codes=A,B,C`（逗号分隔，单次 ≤200 个 code，忽略 `page/size`），返回命中的完整资源，`total` = 实际命中数；超过 200 个 code 需分批。
  3. 无 `codes` 时按 `page` 递增翻页直到累计到 `total`（游标式取全量），不得依赖 `size > 200`。
- `codes` 与 `code` 同时出现时 `codes` 优先；两者都不传才走分页全量。

### 24.10 C28 引擎路由与降级标记（唯一写者 W3 `dev-SE3`）

- **引擎选择（唯一策略级键位）**：解析顺序 `request.engine` → 策略 `optimizationRules.engine`（缺失回退 `initialRules.engine`）→ 默认 `timefold`；策略读取失败按“未声明”处理。`engineSource ∈ {REQUEST, STRATEGY, DEFAULT}`，`engineSpecified = (engineSource != DEFAULT)`，`DEFAULT` 不得伪装成显式指定。未知引擎名返回 `400/40000`。**`optimizationRules.engine`（回退 `initialRules.engine`）是唯一策略级键位**：禁止在策略 JSON 另开 `engineName`/`solver` 等键；策略级配置落在开放 JSON，不改表、不占迁移号段。
- **`ortools` 未启用时不得宣称可用**：M2 的 `ortools` 是占位实现。请求 `ortools` 但未启用必须**降级到 Timefold**，并在响应/metrics 显式标记 `degraded=true`、`engineFallback=true`、`engineFallbackReason=SOLVER_UNAVAILABLE`、`engineFallbackDetail=ORTOOLS_NOT_ENABLED`；禁止把降级结果报成 `ortools` 成功，也不得因此让整个请求失败。降级后若产出完整可行解并成功落库，`status` 可为 `SOLVED`，但必须保留 `degraded=true` 与全部 `engineFallback*` 标记（与 `status` 正交，见 §24.2 第 6 条）；展示层仍应按降级态（黄色）呈现，不得显示为绿色成功。
- **`engineFallbackDetail` 枚举冻结（成因，给运维）**：

| 值 | 含义 |
|---|---|
| `ORTOOLS_NOT_ENABLED` | 请求 ortools 但未启用（配置） |
| `LOCK_CONTENTION` | 等待求解锁超时（容量） |
| `SOLVER_NOT_ASSEMBLED` | 引擎未装配（不可用） |
| `SOLVER_TIMEOUT` | 求解超时 |
| `ENGINE_EXCEPTION` | 引擎执行异常 |

`engineFallbackReason` 闭集为 **`TIMEOUT/ENGINE_ERROR/SOLVER_UNAVAILABLE/SOLVER_BUSY`**。语义差异：`TIMEOUT/ENGINE_ERROR/SOLVER_UNAVAILABLE` 属环境/引擎侧失败，**可以重试**；`SOLVER_BUSY` 是求解槽位等待超时导致的**容量拒绝，明确不可重试**，应由调用方稍后重投，不得进入 30s 退避重试。该字段是给策略看的语义；`engineFallbackDetail` 是给运维看的成因，两者不得混用；detail 仅在发生降级时出现。

- **不新建规则引擎**。降级只覆盖：请求 `ortools` 但未启用、求解锁等待超时、Timefold 抛异常、超时（终止后无可行解）、求解器不可用。
- `fallbackMode` 枚举**冻结为 `HARD_ONLY | PARTIAL`**；**M2 只产出 `HARD_ONLY`**，`PARTIAL` 预留给 M3（避免二次改契约）。
- 降级标记落 `solve_task.metrics`（JSONB）固定键：

| key | 类型 | 值 |
|---|---|---|
| `engineFallback` | boolean | `true` |
| `engineFallbackReason` | string | `TIMEOUT/ENGINE_ERROR/SOLVER_UNAVAILABLE/SOLVER_BUSY`；仅前三类可重试，`SOLVER_BUSY` 为容量拒绝/不可重试 |
| `engineFallbackDetail` | string | 见上表枚举；仅降级时出现 |
| `fallbackMode` | string | `HARD_ONLY`（M2 唯一取值） |
| `fallbackAt` | string | UTC ISO-8601 |
| `terminationReason` | string | 与 `SolveMetrics` 一致 |

路由决策 key（无论是否降级都写）：`requestedEngine`、`engineSource`、`engineSpecified`、`selectedEngine`、`effectiveEngine`；调用方按 `engineSource` 解释“按谁的意愿选了哪个引擎”。

- 接口透传：`GET /solve-tasks/{id}` 的 `metrics` 原样返回，并置 `degraded=true`；列表可返回摘要 `degraded`。
- DSL 校验失败**不降级**，返回 `42200`（PRD BR-SE-005-04）。
- 无任何可行解且引擎不可用：`status=FAILED`，`errorCode=50010`，仍写降级标记。
- 前端展示：进度/结果页显示黄色「降级」徽标 + tooltip 原因；**不得显示为绿色成功**；已发布计划保留 `degraded` 只读字段。
- 回滚点 `aps.solve.fallback-enabled=false`：异常/超时直接 `FAILED`，不产生降级标记；历史 `metrics` 键保留。
- **性能验收线不在本契约**：以 `docs/plan/m2-plan.md#L52`（原文“M2 只取其中 **500 订单 <60s**、Docker ≤15min 两项”）与 `#L205` 为准；本契约不复制 SLA 数值。“落库覆盖完整、无降级”属观测补充（见 §24.2 终态判定），不替代计划验收线。

### 24.11 C29 订单甘特读接口（唯一写者 W4 `dev-SE4`）

`GET /gantt/order?sandboxId&from&to&orderNos`

| 参数 | 必填 | 说明 |
|---|---|---|
| `sandboxId` | 否 | 缺省 BASELINE `...0401` |
| `from` / `to` | 是 | ISO-8601 UTC，闭开区间 |
| `orderNos` | 否 | 逗号分隔；空=全部 |

响应 `data`：

```json
{
  "sandboxId": "...",
  "from": "2026-11-05T00:00:00Z",
  "to": "2026-11-06T00:00:00Z",
  "orders": [
    {
      "orderId": "6f0c2e1a-0000-4000-8000-000000000101",
      "orderNo": "SO-001",
      "materialCode": "M-001",
      "productFamily": "PF-01",
      "dueDate": "2026-11-06",
      "dueDateLine": "2026-11-06T23:59:59Z",
      "priority": "URGENT",
      "freezeStatus": "UNFROZEN",
      "orderStatus": "CONFIRMED",
      "late": true,
      "lateMinutes": 120,
      "lateTaskCount": 1,
      "tasks": [
        { "id": 1001, "operationIndex": 10, "operationName": "SMT", "resourceCode": "R-001",
          "startTime": "...", "endTime": "...", "status": "PLANNED", "version": 1,
          "lockType": "NONE", "late": false }
      ]
    }
  ]
}
```

- 订单行字段全集（与实现响应一致）：`orderId`、`orderNo`、`materialCode`、`productFamily`、`dueDate`、`dueDateLine`、`priority`、`freezeStatus`、`orderStatus`、`late`、`lateMinutes`、`lateTaskCount`、`tasks`。`orderId` 是订单主键（任务跳转/钻取锚点）；`productFamily`、`dueDateLine` 可空；`orderStatus` 取 `orders.status`；`lateTaskCount` 为晚交任务数。
- `late = 任务 endTime > 订单 dueDate 当日结束（UTC 23:59:59）`；订单级 `lateMinutes` 为整数分钟，**仅 `late=true` 时给出**，`late=false` 时省略或 `null`，不得填 0 冒充。
- 任务复用 `GanttTask`（C4）；新增 `lockType`、`late` 两个可选字段，旧客户端忽略。
  - `lockType` 推导：M2 只由 `task.status` 推导 `FROZEN | NONE`（`FROZEN` 任务 → `FROZEN`，其余 → `NONE`）；V5 生效后改为回读 `task.lock_type`，可取值扩展为 `TIME_WINDOW/RESOURCE`，字段名与语义不变，不新增字段。
- 非分页结构；排序**固定为 `orderNo, operationIndex`**（稳定排序），**不按交期/开工时间排序**，`from/to` 只做窗口过滤不改排序。

### 24.12 本文件仍未冻结的 M2 项（如实登记）

| 项 | 状态 | 归属 |
|---|---|---|
| 真实换型矩阵表 `setup_matrix_entry` | 明确不进 M2（静态矩阵 + `setupMatrixFallback=true`） | 用户已拍板 |
| `SPLIT/MERGE` 调整语义 | 枚举已冻结，M2 返回 `50100` | W4 / M3 |
| `PARTIAL` 降级模式 | 枚举已冻结，M2 不产出 | W3 / M3 |

### 24.13 不在 M2 冻结面的端点（点名，不补写正文）

下列端点存在于 `docs/api/openapi/*.yaml`，但**不属于 M2 冻结面**；为避免“看到 yaml 就以为契约已覆盖”，在此逐条点名。yaml 对应 path 以 `x-milestone` 标注：`OPS` = 运维端点，`M1` = 平台管理基线，`M3` = 后续里程碑。

| 端点 | 里程碑 | 归类理由 |
|---|---|---|
| `GET /health` | `OPS` | 存活探针，不访问数据库；运维端点，不纳入业务契约 |
| `GET/POST /users` | `M1` | 平台账号管理基线（RBAC），非 M2 排产行为 |
| `GET /audit-logs` | `M1` | 平台审计基线，非 M2 排产行为 |
| `GET/PUT /configs` | `M1` | 平台配置基线，非 M2 排产行为 |
| `POST /auth/login` | `M3` | 登录页与 JWT 签发留 M3（现状 `50100`），M2 不做认证实现 |
| `POST /agent/events` | `M3` | Agent 事件/决策属 M3（见 §2.1：`agent_event`/`agent_decision` 不占 M2 号段） |
| `POST /agent/nl-to-dsl` | `M3` | 自然语言转 DSL 属 M3 Agent 能力 |
| `GET /agent/explain/{taskId}` | `M3` | 归因解释属 M3 Agent 能力；M2 只提供 C19 结构化 justification |
| `POST /agent/whatif` | `M3` | 对话式 what-if 属 M3；M2 只做计划版本与甘特 |
| `GET/POST /sandboxes` | `M3` | 场景/WHAT_IF 管理；M2 只消费固定 `sandboxId`（C5），不提供 CRUD |
| `POST /sandboxes/{id}/apply` | `M3` | WHAT_IF→BASELINE 发布；M2 的发布路径是 `POST /plans/{id}/publish`（C24） |
| `GET /sandboxes/{id}/compare` | `M3` | 场景差异对比属 M3；契约仅在 C6 路径参数表以 `{id}` 举例，无端点正文 |
| `POST /orders/atp` | `M3` | ATP 交期预估在 M2 计划里明确后移（`m2-plan.md` 范围表 ATP → M3-QA） |
| `GET /mrp/suggestions` | `M3` | 采购建议优化属 M3/v0.5（`m2-plan.md` 范围表）；M2 只做齐套状态（C25） |

边界：本节只点名，不冻结请求/响应形状；实现这些端点前须走 §0.1 契约变更流程。

---

## 25. M3 契约冻结（D0，2026-11-04）

> 状态：**冻结**（C30–C42、V8–V13 号段）。
> 唯一写者：W1（`dev-CONTRACT`）。本节只冻结形状与语义，**不实现业务逻辑**。
> 事实来源优先级：本节 > §24 > `docs/api/openapi/*.yaml`（yaml 仍为占位的地方，以本节为准；yaml 同步由 S0 后续单独提交）。
> 编号纪律：**只增不改**。C1–C29 正文一字不动；本节与旧条目冲突时，以本节为准并在旧条目处仅做「以 C30+ 为准」补注（D0 未做任何旧文改写）。

### 25.0 编号/范围核对结论（先读）

| 项 | 派工单/提案写法 | 实际核对结果 | D0 处理 |
|---|---|---|---|
| `C33` | 派工单把 **SPLIT/MERGE** 标为 C33 | 本文件 §1.1 中 **C33=计划下发与通知**，**C36=SPLIT/MERGE** | **不重编号**：SPLIT/MERGE 按 **C36** 冻结（本批放行）；C33 形状也在本节冻结，但**放行顺序排第二批**（等 W5 的 Sandbox 主链）。W4 以 C36 开工 |
| E 条目数 | §24.13 标「M3=10」 | 实点 10 条（E1 + E2–E5 + E6–E8 + E9 + E10） | 一致，无冲突 |
| C30 权限点 | 提案 `MASTERDATA_WRITE/AUDIT_READ` | V1 已落地 `MD_WRITE/SYS_AUDIT` 等 18 个 `permission.code` | **以 V1 为准**，不新造权限码 |
| C34 建议日期 | 提案 `suggestedOrderDate` | 现有 `MrpSuggestion`/`MrpShortage` 为 `suggestedPurchaseDate` + `suggestedQty` | **用现有字段名**；`suggestedOrderDate` 不启用 |
| C38 实体 slug | 提案 `customer` | `ImportEntity` 现有 slug 全为复数（materials/boms/...） | 冻结 **`customers`**（与现有约定一致），`customer` 别名待拍板 |
| 迁移号段 | V8=RBAC、V9=审计、V10=Sandbox、V12=customers | **V1 已含** users/role/permission/role_permission/user_role 及种子、audit_log+3 索引、sandbox（BASELINE 种子 + 唯一索引）、customer 表 | V8/V9/V10/V13 **不建文件**（详见 25.13），仅 V11/V12 需 DDL |
| 错误码 | 提案 40301/40302、40904、42202 | `ErrorCode` 现只有 40300/40901/42200 等，无上述码 | **D0 不新增错误码**，一律用既有码 + 具体 message；是否细分见 25.14 |

### 25.1 C30 RBAC / 认证（W1，唯一写者）

**现状**：RBAC 表与种子已在 V1 全部落地（5 角色、18 权限点、admin 账号）；`ApiSecurityFilter` 已支持 off/dev/prod 三模式、API Key 与 HS256 JWT 校验（`requiredRole` 默认 `APS_USER`）；**尚无** 登录、用户/角色管理、权限校验实现。`ErrorCode.FORBIDDEN=40300`、`UNAUTHORIZED=40100` 可直接复用。

#### 25.1.1 权限码（即 `PermissionCode`，闭集）

取值**只能**是 V1 `permission.code` 的 18 个值，不得新增/改名：

`MD_READ`、`MD_WRITE`、`PR_READ`、`PR_WRITE`、`OM_READ`、`OM_WRITE`、`OM_ATP`、`ORDER_FREEZE`、`SS_READ`、`SS_WRITE`、`SE_SOLVE`、`SCHEDULE_ADJUST`、`SCHEDULE_PUBLISH`、`SYS_USER`、`SYS_ROLE`、`SYS_AUDIT`、`AGENT_EVENT`、`AGENT_CONFIRM`。

角色码闭集（V1 种子，不可删除、不可改码）：`ADMIN`、`PLANNER`、`PROCESS_ENGINEER`、`SALES`、`VIEWER`。

#### 25.1.2 端点与权限映射

| 端点 | 方法 | 权限 | 状态 |
|---|---|---|---|
| `/api/v1/auth/login` | POST | 公开（仅限流/锁定） | 待实现 |
| `/api/v1/me` | GET | 已认证 | 待实现（yaml 待补） |
| `/api/v1/users` | GET | `SYS_USER` | 待实现 |
| `/api/v1/users` | POST | `SYS_USER` | 待实现 |
| `/api/v1/users/{userId}` | PATCH | `SYS_USER` | 待实现（yaml 待补） |
| `/api/v1/roles` | GET | `SYS_USER` | 待实现（yaml 待补） |
| `/api/v1/roles` | POST | `SYS_ROLE` | 待实现（yaml 待补） |
| `/api/v1/roles/{roleId}/permissions` | PUT | `SYS_ROLE` | 待实现（yaml 待补） |
| `/api/v1/permissions` | GET | 已认证 | 待实现（yaml 待补） |

- 判定顺序**固定**：先认证（失败 `401/40100`），再授权（失败 `403/40300`）。不得把未认证报成 403。
- 认证分支保持现状：`prod` **只认 `Authorization: Bearer <JWT>`**（`X-API-Key` 在 prod 被忽略）；`dev` 先看 `X-API-Key`（读写密钥），再无 key 时看 Bearer；两者同时存在时以 API Key 为准（C27）。M3 不给 API Key 分配权限码。
- `dev` 模式的 API Key 视为服务凭证，**等价 ADMIN 权限**；审计 `user_id=null`（不伪造操作人）。这是 M3 明确让步，见 25.14。
- `prod` 模式必须把 JWT `sub` 写入 `AuditContext`（当前留空，M3 必补）；`off` 模式行为不变（本地 demo/回归零改造）；`dev` 模式保留 `X-User-Id` 注入审计操作人。

#### 25.1.3 登录

`POST /api/v1/auth/login`，请求 `LoginRequest{username, password}`（两字段必填，`password` 不回显）。

响应 `LoginResult`（沿用 yaml，不新增字段）：

```json
{ "accessToken": "<jwt>", "tokenType": "Bearer", "expiresIn": 28800, "userId": "..." }
```

- JWT：HS256，`sub=userId`、`tenantId`、`roles[]`、`iat`、`exp`。**`roles` 必须包含 `APS_USER`**（满足现有 `requiredRole` 默认值），业务角色码一并写入。
- TTL：`aps.security.jwt.ttl-seconds`，默认 `28800`（8h）。**M3 不签发 refresh token**；过期后前端重新登录（`expiresIn` 即刷新/重登窗口，见 25.14）。
- 失败统一 `401/40100`，message 固定「用户名或密码错误」（**不得**区分“用户不存在/密码错误”）。
- 锁定：同一 `tenant+username` 连续 5 次失败 → `users.status=LOCKED`、`locked_until=now+15min`；锁定期内即使密码正确也 `401/40100`；成功后清零计数。计数落 `users.ext_attrs`（不新增表/列）。
- 限流沿用 `RateLimitFilter`（默认 100 req/min/租户+调用方），超限 `429/42900`。
- `ApiSecurityFilter` 必须把 `POST /api/v1/auth/login` 加入放行白名单（`prod` 下当前会先被 401 拦住，M3 必改；仅该路径与 `OPTIONS`）。
- `Idempotency-Key` 对该端点**可选且无业务意义**（不因缺失报错）。

#### 25.1.4 用户/角色

- `User` 响应字段沿用 yaml：`id, username, displayName, email, phone, status, lastLoginAt, version`，**新增非空 `roleCodes[]`**；**永不返回 `password_hash`**。
- `POST /users`：`UserCreateRequest{username*, displayName, email, phone, password*, roleCodes[]}`；`password` 在 M3 **必填**（本地 bcrypt；LDAP/SSO 不在 M3，DB 列可空是为后续接入预留）；`username` 租户内唯一，重复 → `42200`（不用 40903，40903 专门表示“已发布不可覆盖”）；`roleCodes` 含未知角色 → `42200`；密码 bcrypt（cost ≥ 10）落库。
- `PATCH /users/{userId}`：body `{displayName?, email?, phone?, status?, roleCodes?, password?, version*}`；`version` 缺省或≠当前 → `40901`；`roleCodes` 为**全量替换**；禁止把最后一个 `ADMIN` 置为 `DISABLED/LOCKED`（`42200`）；禁止操作人停用自己（`40300`）。
- `GET /roles`：`{items:[{id,code,name,description,permissionCodes[],version}]}`；`POST /roles`：`{code*,name*,description,permissionCodes[]}`，`code` 正则 `^[A-Z][A-Z0-9_]{1,31}$`、租户内唯一（`42200`）。
- `PUT /roles/{roleId}/permissions`：body `{permissionCodes*[]}`，**全量替换**、空数组合法；未知权限码 → `42200`；`roleId` 不存在 → `40400`。
- `GET /me`：复用 `User` + `permissions[]`（当前用户的权限码集合），供前端菜单/按钮控制；权限来源为用户所有角色的并集。
- 权限变更生效上限 **60s**（进程内缓存 TTL）；测试需等待或重启后断言。

### 25.2 C31 审计事件（W1）

**现状**：`audit_log`（BIGSERIAL，追加只读，无 `updated_at/deleted`）与 3 个索引已在 V1；`AuditLogRepository.insert` 已存在；仅订单冻结/优先级/下发 3 个动作写审计，计划发布/回滚/调整**未写**，无查询端点。

#### 25.2.1 事件类型（`AuditEventType`，闭集）

| action | 触发点 | resourceType | 必填 before/after |
|---|---|---|---|
| `ORDER_FROZEN` | 已有（不得改名） | `ORDER` | after |
| `ORDER_PRIORITY_CHANGED` | 已有（不得改名） | `ORDER` | before+after |
| `ORDER_RELEASED` | 已有（不得改名） | `ORDER` | after |
| `AUTH_LOGIN` | 登录成功 | `USER` | — |
| `AUTH_LOGIN_FAILED` | 登录失败（独立事务） | `USER` | — |
| `USER_CREATED` | 创建用户 | `USER` | after |
| `USER_UPDATED` | 修改用户/角色分配 | `USER` | before+after |
| `ROLE_CREATED` | 创建角色 | `ROLE` | after |
| `ROLE_PERMISSION_CHANGED` | 角色权限替换 | `ROLE` | before+after |
| `PLAN_PUBLISHED` | `POST /plans/{id}/publish` | `PLAN` | after |
| `PLAN_ROLLBACK` | `POST /plans/{id}/rollback` | `PLAN` | before+after |
| `PLAN_DISPATCHED` | `POST /plans/{id}/dispatch` | `PLAN` | after |
| `SANDBOX_CREATED` | 创建 WHAT_IF | `SANDBOX` | after |
| `SANDBOX_APPLIED` | apply 成功 | `SANDBOX` | after |
| `IMPORT_EXECUTED` | `mode=execute` 落库成功 | `IMPORT` | after（含统计） |
| `TASK_ADJUSTED` | 调整（含 SPLIT/MERGE） | `TASK` | before+after |

- 新增动作**只对 M3 新行为**启用；不追溯补写 M1/M2 历史动作。
- `resourceType` 闭集：`USER/ROLE/ORDER/PLAN/SANDBOX/TASK/IMPORT`。`resourceId` 为业务键字符串（task 用 BIGSERIAL 十进制、plan 用 planId UUID）。

#### 25.2.2 写入约束

- 写入点是**服务层**（事务内、业务写成功之后），不是 Controller 切面；审计写失败必须回滚业务事务（不得静默吞掉）。`AUTH_LOGIN_FAILED` 例外：独立事务，不能影响 401 响应。
- 必填：`tenant_id`、`action`、`resource_type`、`created_at`；`user_id` 允许空（API Key / off 模式），**不得伪造**默认用户。
- `before_value/after_value` 必须是 JSON object；敏感键（`password/passwordHash/token/accessToken/secret/apiKey`）写入前剔除或置 `"[REDACTED]"`。
- 只允许 INSERT；不提供 UPDATE/DELETE 端点，不做物理清理（M3 不做归档，见 25.14）。

#### 25.2.3 查询端点

`GET /api/v1/audit-logs`（权限 `SYS_AUDIT`）

| 参数 | 必填 | 说明 |
|---|---|---|
| `page` / `size` | 否 | 默认 1 / 20，`size` 上限 200 |
| `action` | 否 | 精确匹配 |
| `resourceType` | 否 | 精确匹配 |
| `resourceId` | 否 | 精确匹配（yaml 待补） |
| `userId` | 否 | UUID 精确匹配（yaml 待补） |
| `from` / `to` | 否 | ISO-8601 UTC，闭开区间 `[from, to)` |
| `sort` | 否 | 仅 `createdAt,asc|desc`，默认 `desc`；其他字段 `40000` |

响应项 `AuditLog`（**字段名沿用 yaml，但 `id` 类型修正**）：

```json
{
  "id": 1024,
  "traceId": "...",
  "userId": "...",
  "action": "PLAN_PUBLISHED",
  "resourceType": "PLAN",
  "resourceId": "...",
  "beforeValue": null,
  "afterValue": {"status":"PUBLISHED"},
  "clientIp": "10.0.0.1",
  "createdAt": "2026-11-04T10:00:00Z"
}
```

- `id` 为 **int64**（对齐 `audit_log.id BIGSERIAL`）；yaml 现有 `uuid` 定义属占位错误，yaml 待 S0 修正。
- 字段映射（DB → API）：`audit_log.ip → clientIp`、`trace_id → traceId`；`user_agent` 不进 API（M3 不展示）。现有 `AuditLogRepository.record` 只写 7 列，`ip/trace_id` 为空是允许的（M3 不强制补齐，见 25.14）。
- 租户过滤隐式（来自认证上下文），请求不接受 `tenantId`。

### 25.3 C32 Sandbox 生命周期（W5）

**现状**：`sandbox` 表（`type` CHECK `BASELINE|WHAT_IF|ARCHIVED`、`parent_sandbox_id`、`snapshot`、`expires_at`、`version`、软删）与「每租户唯一 BASELINE」唯一索引已在 V1；BASELINE 种子 `...0401`（C5）。**无**任何 Sandbox 实体/Controller/Service。**本契约不新增列、不新增迁移**。

#### 25.3.1 列表/创建

`GET /api/v1/sandboxes?type=&page=&size=&sort=`（权限 `SS_READ`）

- 默认按 `createdAt DESC`；`sort` 仅允许 `createdAt`。
- 响应 `SandboxSummary`（**列表不返回 `snapshot`**，避免大对象；yaml 的 `Sandbox.snapshot` 不得出现在列表）：`{id,name,type,parentSandboxId,expiresAt,version,createdAt}`。
- `ARCHIVED` 默认**不返回**；`type=ARCHIVED` 显式查询才返回。

`POST /api/v1/sandboxes`（权限 `SS_WRITE`，`Idempotency-Key` 可选）

```json
{ "name": "11月产能方案", "type": "WHAT_IF", "parentSandboxId": "...0401", "expiresAt": null }
```

- `name` 1–128 必填；`type` **只允许 `WHAT_IF`**（缺省即 `WHAT_IF`）；传 `BASELINE`/`ARCHIVED` → `42200`（每租户唯一 BASELINE，由 apply 产生，不允许直接建）。
- `parentSandboxId` 缺省为当前 BASELINE；父场景不存在或已 `ARCHIVED` → `42200`。
- 创建即深拷贝父场景 `snapshot`，`parentSandboxId` 固化，`version=1`；响应 `SandboxSummary`。
- 无名称唯一约束（V1 未建），重名允许。

#### 25.3.2 应用 `POST /sandboxes/{id}/apply`

权限 `SCHEDULE_PUBLISH`；`Idempotency-Key` **必填**（缺失 `40000`）。

请求体可为空，或 `{ "reason": "可选说明" }`。响应 `SandboxApplyResult`：

```json
{ "sandbox": { "id": "...", "name": "...", "type": "BASELINE", "parentSandboxId": "...", "expiresAt": null, "version": 2, "createdAt": "..." },
  "plan": { "planId": "...", "planNo": "PLAN-20261104-001", "version": 12, "targetVersionNo": null,
            "status": "PUBLISHED", "publishedAt": "...", "affectedTaskCount": 120,
            "sandboxId": "...", "taskCount": 120, "approver": null, "reason": null, "rollbackOf": null } }
```

`plan` 复用 C24 `PlanActionView`（字段名一字不改）。

**原子步骤（单事务，顺序固定）**：

1. 取 `id` 场景 FOR UPDATE；不存在 → `40400`；`type != WHAT_IF` → `40901`；
2. 该场景已经是 `BASELINE`（即已应用过）→ `40901`，message 含「已应用」；`ARCHIVED` → `40901`；
3. 场景 `snapshot`/`task` 无任务（`taskCount=0`）→ `42200`；
4. 生成新的 `plan_version`（`version = 该 sandbox 现有最大版本+1`，先 `DRAFT`）并**在同一事务内**按 C24 `publish` 固化快照、回填 `task.plan_version_id`；等价于「发布 + 提升场景」一次完成；
5. 旧 BASELINE → `ARCHIVED`（不删除其任务、不改其历史 `plan_version.sandbox_id`）；
6. 目标 WHAT_IF → `BASELINE`（**沿用同一 id**，`version+1`）；
7. 写审计 `SANDBOX_APPLIED`，`resourceId=目标场景 id`，`afterValue` 含新 planId/版本。

**与 `plan_version` 的关系（冻结）**：

- `sandbox 1 : N plan_version`，`plan_version.sandbox_id` 只指向产生它的场景；apply **不**改历史版本的 `sandbox_id`。
- 一个场景可有多条 `PUBLISHED` 版本（多次 apply 会产生）；「当前主计划」= **当前 BASELINE 场景上的最新 `PUBLISHED` 版本**，不是全局最新版本。
- apply 归档旧 BASELINE 时，**旧场景上的历史 `PUBLISHED` 版本保持 PUBLISHED 只读**（不改状态为 ARCHIVED，避免与 C24 回滚语义混淆）；查询列表按 `sandbox_id` 过滤即可。
- 回退：对旧 `ARCHIVED` 场景的版本执行 C24 `POST /plans/{id}/rollback` **允许**（回滚只重指向 `task.plan_version_id`，不改场景快照/类型），但仍受 C24 的 `40903` 场景级在制检查；回滚**不会**把场景类型改回 BASELINE（BASELINE 唯一性只由 apply 变更）。

#### 25.3.3 对比 `GET /sandboxes/{id}/compare?targetId=`

权限 `SS_READ`。

- `id` 为**被比较方**（通常是 WHAT_IF），`targetId` 为**基线方**（通常是 BASELINE）；`targetId` 必填，缺失/非法 UUID → `40000`；任一不存在 → `40400`；`id == targetId` → `40000`。
- 差异方向：`XxxDelta = id 的值 − targetId 的值`（正=被比较方更高/更晚/更多）。
- 响应以 yaml `SandboxCompare` 为底，**新增 3 个计数字段与 `changeType`（D0 授权的新增，yaml 待同步）**：

```json
{
  "sandboxId": "...", "targetId": "...",
  "dueDateDeltaMinutes": -120,
  "utilizationDelta": 0.03,
  "setupCountDelta": 4,
  "addedTaskCount": 2, "removedTaskCount": 1, "movedTaskCount": 7,
  "changedTasks": [
    { "taskId": 1001, "orderNo": "SO-001", "resourceCode": "R-001", "changeType": "MOVED" }
  ]
}
```

- `changeType` 闭集 `ADDED|REMOVED|MOVED`：以 `(orderNo, operationIndex)` 为业务键匹配两侧任务；业务键只在一侧存在 → ADDED/REMOVED；两侧都在但 `startTime/endTime/resourceCode` 任一不同 → MOVED。`taskId` 为**被比较方**任务 id（ADDED 为被比较方新任务，REMOVED 为基线方旧任务）。
- `changedTasks` 上限 200，超出截断并在 `truncated=true`（新增布尔字段）标记；计数字段**始终为全量真实值**。
- `dueDateDeltaMinutes`（单位**分钟**，方向 `id − targetId`，正=被比较方更晚）：
  - 逐任务 `late = endTime − cutoff(dueDate)`，`cutoff = dueDate 次日的 00:00`，按**业务时区**（配置 `aps.business.time-zone`，默认 `Asia/Shanghai`）计算；只统计 `deleted=false` 且属于该场景的任务。
  - 一侧取所有合格任务的 `maxLate`，无合格任务记 `0`；差值 = `id 侧 maxLate − target 侧 maxLate`。
  - `endTime` 或 `dueDate` 为 `null` 的整条**跳过**；恰在截止点完成记 `0`；跨日按实际分钟累计。
  - **不得**用「UTC 偏移贴本地日期零点」的写法（会导致 `Asia/Shanghai` 下截止点漂到本地次日 08:00）。
- `utilizationDelta`（0–1 小数，保留 3 位，方向同上 `id − targetId`）：
  - 窗口 = `[min(startTime), max(endTime)]`（仅统计 `startTime/endTime` 非空的行），`windowMinutes = 窗口分钟数`。
  - 每资源 `busy_r = Σ(task 占用分钟)`（`resourceCode` 为 `null` 的行不计），`u_r = min(1.0, busy_r / windowMinutes)`——**封顶只发生在单资源比率**。
  - 一侧 = 有 busy 记录资源的 `u_r` 均值；`busy` 为空或 `windowMinutes <= 0` 记 `0.0`；差值按 **3 位四舍五入**。
- `setupCountDelta`：换型次数差。

### 25.4 C36 `SPLIT/MERGE` 调整语义（W4，本批冻结）

**现状**：`POST /tasks/{id}/adjust` 已实现 MOVE/LOCK/FREEZE/UNFREEZE（C22），`SPLIT/MERGE` 返回 `50100`；`task_adjustment.adjust_type` CHECK 已含 `SPLIT/MERGE`，`before/after` 为 JSONB，**无需新列**。

#### 25.4.1 请求扩展（`TaskAdjustRequest` 追加可选字段）

| 字段 | 类型 | 适用 | 约束 |
|---|---|---|---|
| `splitQuantities` | number[] | SPLIT | 2–10 项，每项 > 0，和 = 源 `task.qty`（容差 1e-4）；否则 `42200` |
| `mergeTaskIds` | int64[] | MERGE | 2–10 项，含路径 `id` 自身；同 `tenant/sandbox/order/operation_index`，否则 `42201` |
| `reason` | string | SPLIT/MERGE | **必填**，≤512；缺失 `40000`（FREEZE 既有规则不变） |

- `operation` 仍取 `SPLIT|MERGE`；`version` 仍为乐观锁（≠当前 `task.version` → `40901`）。
- `task.qty` 在 V1 允许 NULL：**SPLIT 要求源任务 `qty` 非空且 > 0**；**MERGE 要求所有参与任务 `qty` 非空且 > 0**；否则 `42200`（不得把 NULL 当 0）。
- `dryRun=true`：只计算返回，不落库、不写审计、不产生 adjustmentId。

#### 25.4.2 语义

**SPLIT**（路径 `id` 为源任务）：

- 源任务 `qty` 拆为 N 个子任务，**同一 `order_id/operation_index/sandbox_id`**，资源默认继承源任务（可用 `resourceCode` 覆盖全部子任务）。
- 子任务时间：按 `splitQuantities` 比例切分源任务 `[start,end]`，保持连续、不重叠；`setup` 只在第一个子任务保留（`setup_start/end` 置空其余）。
- 源任务：`qty` 置为第一段？**否** —— 源任务保留为第 1 段，其余 N−1 段为**新建任务**（`status=PLANNED`，`sequence_in_resource` 顺延）；这样撤销只需删除新建任务并恢复源 `qty`。
- 父子关系**不新增列**：以 `task_adjustment.after_value` 记录 `createdTaskIds[]` 与 `parentTaskId`，审计可追溯；查询父子关系走调整历史（`GET /tasks/{id}/adjustments`）。
- 子任务 `version=1`，`lockType` 不继承（新任务默认无锁）。

**MERGE**（路径 `id` + `mergeTaskIds`）：

- 前置：全部任务同租户、同 sandbox、同 order、同 `operation_index`、同 `resource_id`；在 `sequence_in_resource` 上**连续相邻**；均非 `FROZEN`（`42201`），`IN_PROGRESS/COMPLETED` 按 `40903`（见 25.4.4）；否则 `42201`。
- 合并后保留 `id`（路径任务）为目标，`qty=Σ qty`，`start=min(start)`、`end=max(end)`，资源取目标任务；其余任务**软删**（`deleted=true`），不物理删除。
- MERGE 后 `setup` 取最早任务；`version+1`。

#### 25.4.3 响应

沿用 `TaskAdjustResult`，追加两个可选数组（`dryRun=true` 时为空）：

```json
{ "adjustmentId": 2048, "applied": true, "dryRun": false,
  "task": { "...": "GanttTask" }, "conflicts": [],
  "createdTaskIds": [1002, 1003], "removedTaskIds": [1004, 1005] }
```

- `adjustmentId` 为 **int64**（对齐 `task_adjustment.id BIGSERIAL` 与现有 `TaskAdjustResult(Long)`）；yaml 的 `uuid` 为占位错误（见 25.14）。
- `task` 为操作后的**主任务**（SPLIT=源任务第 1 段，MERGE=目标任务）。

#### 25.4.4 守卫/幂等/审计

- 源或目标任务 `IN_PROGRESS/COMPLETED` → `409/40903`（沿用 C22 任务级语义）；`FROZEN` 源 → `42201`。
- `Idempotency-Key` **必填**（缺失 `40000`）；同键重复 → `409/40900`（沿用拦截器）。
- 撤销走既有 `POST /tasks/adjustments/{adjustmentId}/undo`：SPLIT 撤销删除 `createdTaskIds` 并恢复源 qty；MERGE 撤销恢复 `removedTaskIds` 软删标记与 qty；仍受「每任务最多 50 步」限制。
- 审计 `TASK_ADJUSTED`：`beforeValue={qty,taskIds}`、`afterValue={qty,taskIds,operation}`。

### 25.5 C33 计划下发与通知（W5，第二批）

**现状**：无任何 dispatch/notification 类或表；不接真实 MES/邮件/短信网关。M3 只做 **outbox 落库 + 通道抽象**。

#### 25.5.1 下发 `POST /plans/{id}/dispatch`

权限 `SCHEDULE_PUBLISH`；`Idempotency-Key` **必填**（缺失 `40000`）。

```json
{ "targets": [
    { "channel": "EMAIL", "address": "planner@example.com" },
    { "channel": "FEISHU", "address": "ou_xxx" }
  ],
  "message": "可选附加说明" }
```

- `targets` 1–50 项，同一 `(channel,address)` 不得重复（`42200`）；`channel` 闭集 `EMAIL|SMS|FEISHU`；`address` 非空 ≤256。
- 计划必须存在（`40400`）且为 `PUBLISHED` 状态（否则 `40901`）。
- 响应 `200`：

```json
{ "dispatchId": "...uuid...", "planId": "...", "status": "QUEUED",
  "targetCount": 2, "queuedAt": "2026-11-04T10:00:00Z" }
```

- 语义：**只入 outbox**，不在此请求内真实发送；`status=QUEUED` 恒定。重复 `Idempotency-Key` 返回首次的 `dispatchId` 与原状态（不重复入队）。
- 写审计 `PLAN_DISPATCHED`；`afterValue={dispatchId,channels,targetCount}`。

#### 25.5.2 通知状态

`GET /plans/{id}/dispatches?page&size`（权限 `SCHEDULE_PUBLISH`，yaml 待补）：

```json
{ "items": [ { "dispatchId": "...", "planId": "...", "status": "SENT",
               "targetCount": 2, "sentCount": 2, "failedCount": 0,
               "createdAt": "..." } ] }
```

- **分页口径**：示例只给 `{items:[...]}`，未规定分页外壳；实际返回 `PageResult{items,page,size,total,totalPages}` 属示例的**超集**，**不构成冲突**；`items` 元素形状必须与示例一致（不得增删字段）。
- 聚合状态闭集：`QUEUED|SENT|PARTIAL|FAILED`（有失败且无成功=FAILED，部分成功=PARTIAL）。
- **不使用 `GET /plans/{id}` 承载通知状态**：`PlanDetailView` 冻结为 `{action,tasks}`，加字段即破坏 C24；改为上述专用端点（与 m3-plan 原文的偏差，见 25.14）。
- 单通道状态可在后续 `GET /dispatches/{dispatchId}/targets` 提供；**M3 不冻结**该端点。

#### 25.5.3 重试与 outbox

- 每目标最多 **2 次尝试**（首次 + 1 次重试），失败退避 **30s**，仍失败 → `FAILED` 终态（`EXHAUSTED` 仅作内部计数，不对外暴露）。
- 幂等键：DB 唯一 `(tenant_id, dispatch_id, channel, address)`，重试不得产生重复行。
- 不做真实短信/邮件发送；通道实现留给 `service/notification` 的 SPI，M3 提供 `NOOP`/`FILE` 两种落盘实现用于验收。

### 25.6 C34 采购建议（W6，第二批）

`GET /api/v1/mrp/suggestions`（权限 `MD_READ`；现状 `50100`）

| 参数 | 必填 | 说明 |
|---|---|---|
| `sandboxId` | **是** | 场景 UUID；缺省不兜底 BASELINE（避免误读），缺失 `40000` |
| `orderNos` | 否 | 逗号分隔；空=该场景全部可排产订单 |
| `horizonDays` | 否 | 1–365，默认 30；只保留 `earliestRequiredAt <= now+horizonDays` 的建议（新增参数，实现待补） |
| `page` / `size` | 否 | 默认 1 / 20，上限 200 |

响应项 `MrpSuggestionView`（**字段与现有 `MrpAnalysisResponse.MrpShortage` 完全一致**，不新增库存明细字段）：

```json
{ "materialCode": "RM-0001", "materialName": "铝板",
  "requiredQty": 120, "availableQty": -30, "shortageQty": 120,
  "earliestRequiredAt": "...", "affectedOrderNos": ["SO-001"],
  "suggestedPurchaseDate": "...", "suggestedQty": 120, "supplierCode": null }
```

- **字段名以现有实现为准**：`suggestedPurchaseDate`/`suggestedQty`（不是 `suggestedOrderDate`）；`materialName` 实现已有，yaml 待补。
- 口径（对齐 `MrpAnalysisService#buildShortages`）：`availableQty = onHand + transit − safetyStock`（**可为负**，负值表示低于安全库存）；`shortageQty = requiredQty − max(availableQty, 0)`，`<= 0` 的物料不输出建议。
- `suggestedPurchaseDate = earliestRequiredAt − material.leadTimeDays`（`leadTimeDays` 缺省 0）；`suggestedQty = shortageQty`；`supplierCode` 固定 `null`（M3 未接供应商主数据）。
- 排序固定 **`materialCode ASC`**（与现有实现一致）；`sort` 参数不开放（传任何值 `40000`）。
- 空结果 `200` + `items: []`（不是 404）。
- `horizonDays` 仅过滤 `earliestRequiredAt`，不影响 `shortageQty` 计算。
- 行业特例（SMT 飞达/储能分选）**不在 M3**。

### 25.7 C35 多策略择优（W2，第二批）

**现状**：`/strategies` 有列表/CRUD；`POST /solve-tasks` 单策略；无批量入口、无对比视图。

#### 25.7.1 批量求解（不新增批量端点）

- 前端/编排层对 N 个策略**串行**调用既有 `POST /solve-tasks`（每策略一个 `solveTaskId`），**不**新增 `/solve-tasks/batch`。
- N 上限 **3**（M3）；超限由编排层拒绝（`42200`）。`W3 提供批量入口` 的表述降级为「客户端编排」，避免新增契约面。
- 每策略可使用相同 `sandboxId` 与不同 `strategyId`；`randomSeed` 必须固定，保证可复现。

#### 25.7.2 对比 `POST /plans/compare`（yaml 已补：`gantt.yaml`，2026-11-04）

权限 `SS_READ`；body：

```json
{ "planIds": ["...", "..."] }
```

- `planIds` 2–3 个 UUID，去重；任一不存在或其版本无快照 → `42200`；缺失 `40000`。

响应：

```json
{ "items": [
    { "planId": "...", "planNo": "PLAN-20261104-001", "version": 12,
      "scoreHard": -40, "scoreSoft": 120, "taskCount": 120,
      "metrics": { "...": "solve_task.metrics 原样透传" } }
  ],
  "recommendedPlanId": "...",
  "tieBreak": "SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC" }
```

- 选优规则（**确定性、可复现**）：`scoreHard` 降序（越大越优，0 为无硬违约）→ `scoreSoft` 降序 → `planId` 字符串升序。并列时 `recommendedPlanId` 取上述排序第一，**不得**“随机选”或“取最新”。
- `metrics` 为 `solve_task.metrics` **原样 map**（不重命名、不裁剪键）；不新增 `StrategyCompareView` 专用指标，避免与 C28 指标清单二次维护。
- 不落库、不产生新计划版本；纯读。

#### 25.7.3 评分来源（`plan_version` 无评分列）

`plan_version` **没有评分列**；对比项的 `scoreHard` / `scoreSoft` / `metrics` 不是计划版本自身字段，而是**关联求解记录**的投影。该口径属于契约，**不得只写在实现类注释里**：

- 关联规则：取「**同租户 + 同 `sandbox_id`（取该计划版本自身的场景）+ `published_at` 之前（含）最后一次 `SOLVED`**」的 `solve_task`。
- 唯一定位：`status = 'SOLVED'`、`finished_at IS NOT NULL`、`deleted = false`，按 `finished_at DESC, id DESC` 取第一条；`id` 为主键，保证并列时仍有确定性裁决。
- **必须可复现**：同一计划版本在同一数据状态下多次比对，必须命中同一条求解记录并返回同一 `scoreHard/scoreSoft/metrics`；不得依赖数据库返回顺序、缓存或并发时序。
- 找不到唯一命中（发布时刻前无 `SOLVED` 记录，或并列时无法给出确定性裁决）→ **`422 / 42200`**；**不得**猜测、**不得**退化为「取最新」或「取第一条」。
- 本契约优先于实现注释：二者冲突时以本节为准。

**与实现的偏差（如实登记，2026-11-04）**：

1. `metrics` 非逐键原样：实现先按 §24.2 剔除 `stepCount` / `unimprovedStepCount`，其余键不重命名、不裁剪。属已冻结口径，不计为对比逻辑裁剪。
2. `published_at` 为空时，实现把上界取为「当前时刻（UTC）」再按同一排序取记录。本节只定义「`published_at` 之前」；空值属未定义边缘，按实现执行并在此登记（正常发布版本恒有 `published_at`）。
3. `scoreHard` / `scoreSoft` 可空；实现排序把空值置于最后（`nullsLast`）。本节未定义空分排序，按实现登记。
4. `planIds` 去重后不足 2 个或超过 3 个：**契约规定 `400 / 40000`**；实现曾偏差为 `42200`，已于 2026-11-04 修回，以本节为准。

### 25.8 C37 `PARTIAL` 降级与重试（W3，第二批）

**现状**：`SolveTaskStatus` 闭集 `PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT`；`metrics.fallbackMode` 已冻结 `HARD_ONLY|PARTIAL`（C28），M2 只产出 `HARD_ONLY`。**不新增状态值**。

#### 25.8.1 重试编排

- 触发：`ENGINE_ERROR` / `TIMEOUT` / `SOLVER_UNAVAILABLE`（`engineFallbackReason` 三类）。
- **不触发**：DSL/参数校验失败（`42200`）、重叠守卫、覆盖不完整（属业务结果，不重试）、**`SOLVER_BUSY`**（容量拒绝；不进入 30s 退避重试，应由调用方稍后重投）。
- 次数：**最多 2 次尝试**（首次 + 1 次重试）；间隔固定 **30s**。
- 每次尝试写 `metrics.attemptCount`、`metrics.retryReasons[]`（字符串数组）、`metrics.retryAt[]`（ISO-8601 UTC）。
- 重试仍失败 → 终态 `FAILED`（若最后一次为超时则 `TIMEOUT`），`errorCode ∈ {ENGINE_ERROR, TIMEOUT, SOLVER_UNAVAILABLE}`（沿用现有取值，**不用 HTTP 码当 errorCode**），并保留 `engineFallback*`。

#### 25.8.2 PARTIAL

- `fallbackMode=PARTIAL` **仅当**引擎产出了部分任务（`metrics.partialAssignedTaskCount > 0` 且 `< expectedTaskCount`）时写；无任何任务 → `HARD_ONLY`。
- `expectedTaskCount`、`partialAssignedTaskCount`、`unassignedTaskCount` 为 **D0 新增 metrics 键**（yaml/C28 指标清单待补注）；不改 C28 已有键名。
- 终态**必须** `status=FAILED`（不得 `SOLVED`，C28 §24.2 第 6/7 条）；`metrics.discardedBestSolution=true`、`persistedTaskCount=0`、`partialAssignedTaskCount=<已排位数>`、`unassignedTaskCount=<未排位数>`。
- `GET /solve-tasks/{id}` 的 `tasks` 仍为 `[]`（C20：非 SOLVED 空数组）；未排任务清单只在 `metrics` 与 `errorMessage` 中给出，**不新增顶层字段**。
- `degraded=true`、`engineFallback*` 与 `fallbackMode=PARTIAL` 可同时存在；`status` 与降级标记正交（C28）。
- 不新增 `solve_task.status` 取值，**V13 不因此建 CHECK 迁移**。

### 25.9 C38 `customers` 导入导出（W9，第二批）

**现状**：`customer` 表（`code/name/priority/lead_time_days/payment_terms/blacklisted/ext_attrs`，唯一 `(tenant_id,code)`）已在 V1；`ImportEntity` 现有 9 类**不含客户**；通用端点 `/{entity}/import|export|import/template` 已实现。

- `ImportEntity` 增加 **`CUSTOMERS("customers","客户", ...)`**（slug **复数**，与既有约定一致）。
- 模板列（顺序即模板列顺序，列键为 camelCase）：

| 列键 | 表头 | 必填 | 规则 |
|---|---|---|---|
| `code` | 客户编码 | 是 | ≤64，租户内唯一（存在则更新） |
| `name` | 客户名称 | 是 | ≤256 |
| `priority` | 优先级 | 否 | `VIP/NORMAL/LOW`，默认 `NORMAL` |
| `leadTimeDays` | 交付提前期(天) | 否 | 非负整数 |
| `paymentTerms` | 付款条件 | 否 | ≤128 |
| `blacklisted` | 是否黑名单 | 否 | `true/false`，默认 `false` |

- 导入语义沿用 C26：`preview` 只校验、`execute` 全量通过才落库；按 `code` upsert；`blacklisted` 不阻断订单确认（C8 仍跳过黑名单校验）。
- 导出列同模板；客户作为 BOM/订单的引用目标**不新增外键约束**（`orders.customer_id` 既有可空引用）。
- **无需迁移**（表已在 V1）。

### 25.10 C39 异步大文件导入（W9，第二批）

**现状**：`POST /{entity}/import?mode=preview|execute&token=`（同步、multipart xlsx）；`ImportResult` 已含 `mode/committed/replayed/errors/preview`；`ImportLimits` = 10MB / 5000 行 / 200 错误 / 20 预览。

#### 25.10.1 异步提交

`POST /{entity}/import?mode=async&token=`（multipart `file`；`token` 为既有幂等令牌）

- **成功受理** `202`：

```json
{ "jobId": "...uuid...", "entity": "customers", "mode": "async", "status": "PENDING",
  "totalRows": 0, "processedRows": 0, "inserted": 0, "updated": 0, "skipped": 0,
  "invalidRows": 0, "errors": [], "committed": false,
  "contentHash": "sha256:...", "createdAt": "...", "startedAt": null, "finishedAt": null }
```

- `mode` 取值扩展为 `preview|execute|async`（**不修改**既有两值语义）。
- 校验在受理时同步完成：实体未知 `40400`；文件缺失/非 xlsx `42200`；**超过 C26 上限（≤5MB、≤5000 行）→ `42200`**。
- 幂等：`token` 与 `contentHash(tenant+entity+文件字节)` 双键；同键存在非终态 → 返回**同一** `jobId`（`202`），不重复建任务。

#### 25.10.2 状态/取消

`GET /{entity}/import/jobs/{jobId}`（yaml 待补）

- 返回同一 `ImportJobView`；跨 entity 查询 → `40400`；终态保留 **24h**（超时清理，见 25.14）。
- `status` 闭集 `PENDING|RUNNING|SUCCEEDED|FAILED|CANCELLED`；`SUCCEEDED` 时 `committed=true`、`processedRows=totalRows`；`FAILED` 时 `errors` ≤200、`committed=false`（**全量回滚，不留半批**）。
- `POST /{entity}/import/jobs/{jobId}/cancel`：仅 `PENDING|RUNNING` 可取消（`202` → `CANCELLED`），其他状态 `40901`。
- 异步任务失败重试**不做**；用户重新提交（新 token）即可，避免重复落库。

#### 25.10.3 与同步模式的边界

- `preview`/`execute` 的行为、响应 `ImportResult` **一字不改**；`mode=async` 是**新增分支**，不复用 `ImportResult` 的 200 响应。
- 异步执行线程并发上限 **2**（单实例）；超出排队（`PENDING`），不做拒绝。
- 文件字节上限的**存量偏差**：C26 冻结 `≤5MB`，当前实现 `ImportLimits.MAX_FILE_BYTES=10MB`。D0 **不改契约**，异步按 C26 的 5MB 校验；实现收紧与存量放宽的处置见 25.14。

### 25.11 C40 Agent 四端点（W10，条件冻结）

**门禁未过 → 不冻结形状、不开卡**。仅登记端点与硬边界：

| 端点 | 方法 | 现状 |
|---|---|---|
| `/agent/events` | POST | `50100` |
| `/agent/nl-to-dsl` | POST | `50100` |
| `/agent/explain/{taskId}` | GET | `50100` |
| `/agent/whatif` | POST | `50100` |

- 启动条件（全部满足）：用户确认把 PRD v0.5 的 Agent 能力前移到 M3；LLM 服务可用性与数据出境合规确认；C19 DSL 白名单可复用。
- 已冻结的硬边界：事件 `eventId` 幂等（同 id 重复上报不得重复处理）；NL→DSL **只能**生成 C19 白名单内的结构，校验不过 `42200`；`explain` 必须引用真实 `constraint_justification`，不得编造；`whatif` 不得写入 `PUBLISHED` 计划。
- 请求/响应 DTO **延期冻结**；未冻结前任何流不得按“猜测形状”写前端。

### 25.12 C41 ATP（W6，条件冻结）

**现状**：`POST /api/v1/orders/atp` 为 yaml 占位（`50100`），无实现。

- 沿用 yaml 形状**冻结**（不再扩展）：

```json
// 请求
{ "materialCode": "FG-0001", "qty": 100, "requestedDate": "2026-11-20", "customerId": null }
// 响应
{ "feasible": true, "earliestDate": "2026-11-22", "leadTimeDays": 3, "bottleneckResourceCode": "R-001" }
```

- `feasible=false` 时 `earliestDate` 仍给出**最早可承诺日**；`bottleneckResourceCode` 可空；`leadTimeDays` 为整数日历天。
- 不引入「置信度/概率」字段（m3-plan 原文提到置信度，但 yaml/实现均无，且条件项，不新造）。
- 条件门禁同 C40：用户确认 M3 承接后才开工；否则保持 `50100`。

### 25.13 迁移号段台账 V8–V13（D0 发号，只分配不写 DDL）

| 号段 | 归属流 | 表/字段意图 | 约束边界 | D0 结论 |
|---|---|---|---|---|
| **V8** | W1 | RBAC：`users/role/permission/role_permission/user_role` + 18 权限点/5 角色/admin 种子 | 不改 V1 语义；权限码不新增 | **空号，不建文件**：V1 已全量覆盖（`V1__init.sql#L51-L962`） |
| **V9** | W1 | `audit_log` 事件类型/索引 `(tenant_id, created_at, action)` | 追加只读；不删旧列 | **空号，不建文件**：V1 已建表 + 3 索引（`#L836-L853`） |
| **V10** | W5 | Sandbox 生命周期：`parent_sandbox_id/type/expires_at/version` + 「每租户唯一 BASELINE」 | 不新增 `applied_at/applied_by`（由 `plan_version.published_at/published_by` 与审计回溯） | **空号，不建文件**：V1 已建（`#L603-L627`），BASELINE 种子 `...0401` |
| **V11** | W5 | 新表 `notification_outbox`：`id BIGSERIAL, tenant_id, dispatch_id UUID, plan_id UUID, channel, address, message, status, attempts, next_attempt_at, last_error, created_at, updated_at` | 唯一 `(tenant_id, dispatch_id, channel, address)`；索引 `(tenant_id, status, next_attempt_at)`；CHECK status/channel；不碰 `plan_version` | **建 V11**（D1 后由 W5 提交，唯一写者） |
| **V12** | W9 | 新表 `import_job`：`id UUID, tenant_id, entity, status, content_hash, file_name, file_bytes, total_rows, processed_rows, inserted, updated, skipped, invalid_rows, errors JSONB, committed, created_by, created_at, started_at, finished_at` | 唯一 `(tenant_id, entity, content_hash) WHERE status IN ('PENDING','RUNNING')`（防重复在途；终态后允许同内容重提）；CHECK status；错误 ≤200 行 | **建 V12**（D1 后由 W9 提交） |
| **V13** | W3/W4 | `task_adjustment`（SPLIT/MERGE）与 `solve_task.status`（PARTIAL）扩展 | 仅在现有 CHECK 不足时启用；C36/C37 已证不新增列/状态 | **空号，不建文件**：V1 `adjust_type` CHECK 已含 SPLIT/MERGE（`#L807-L826`）；C37 不新增 `status` 值 |

**发号硬规则**：V8–V13 每段唯一写者；**空号不得被后续流挪用**（如 V13 条件流 Agent 另起 V14，需 D0 追加发号）；已应用 V1–V7 连注释都不许改。

### 25.14 待拍板 / D0 明确不做

| # | 事项 | 现状/风险 | 建议 |
|---|---|---|---|
| 1 | 派工单把 `SPLIT/MERGE` 标为 C33 | 本文件 C33=下发、C36=SPLIT/MERGE | 保持 C36；如坚持 C33，需整体重编号（不推荐） |
| 2 | `suggestedOrderDate` vs `suggestedPurchaseDate` | 计划与实现字段名不同 | 用 `suggestedPurchaseDate`（已冻结），计划文字不改 |
| 3 | `customer` vs `customers` slug | 计划用单数，实现约定用复数 | 用 `customers`；若要单数需新增别名分支 |
| 4 | 错误码细分 40301/40302、40904、42202 | 会扩大 4xxxx 面并需登记 `ErrorCode` | M3 不新增；一律 40300/40901/42200 + message |
| 5 | `dev` 模式 API Key 等价 ADMIN、且审计无操作人 | 违背最小权限 | M3 让步；M4 建议按 key 绑角色 |
| 6 | 无 refresh token | 前端“令牌刷新”落空 | 8h 重登；若必须刷新需新增 `/auth/refresh` + `refreshToken` 字段（破坏 LoginResult） |
| 7 | 文件上限 5MB（C26）vs 实现 10MB | 存量不一致 | 二选一：收紧实现到 5MB，或走变更单改 C26；D0 不静默改 |
| 8 | `audit_log.id` yaml=uuid vs DB=BIGSERIAL | yaml 占位错误 | 契约取 int64；yaml 由 S0 修正 |
| 9 | `TaskAdjustResult.adjustmentId` yaml=uuid vs DB=BIGSERIAL | C22 存量不一致 | 不在 D0 修正，单开缺陷卡 |
| 10 | 下发状态查询端点 | 计划原文说走 `GET /plans/{id}` | 改为 `GET /plans/{id}/dispatches`（避免破坏 `PlanDetailView`） |
| 11 | 审计保留期/归档 | M3 无清理任务，表会持续增长 | M3 不清理；M4 加归档/分区 |
| 12 | Agent/ATP 条件门禁 | PRD 归 v0.5 | 未确认不开卡；C40 形状延期冻结 |
| 13 | 22 卡落库 | 看板 API 只读（POST 404），无法建卡 | 见 `docs/plan/m3-d0-cards.json`，需看板写权限后导入 |
| 14 | C37 未排任务清单放哪 | W3 验收要“响应含未排任务清单”，但 C20/C28 冻结 `FAILED` 时 `tasks=[]` 且无顶层清单字段 | M3 放在 `metrics.unassignedTaskCount` + `errorMessage`；若要顶层数组需变更单（会破坏 C20） |

### 25.15 与 yaml / 实现偏差登记（不改 yaml，待 S0 后续同步）

| 偏差 | 位置 | 处置 |
|---|---|---|
| `audit_log.id` uuid → int64 | `system.yaml#L378` | yaml 待改类型 |
| `/users`、`/audit-logs` 标 M1 但未实现 | `system.yaml` | M3 落地；`x-milestone` 待改 `M3` |
| 新增 `/me`、`PATCH /users/{id}`、`/roles`、`/roles/{id}/permissions`、`/permissions` | 无 yaml path | yaml 待补 |
| `UserCreateRequest.password` 可选 | `system.yaml#L357` | M3 只有本地账号，创建时 `password` **必填**（LDAP/SSO 不在 M3） |
| `Sandbox` 列表含 `snapshot` | `orders.yaml#L1134` | 列表改 `SandboxSummary` |
| `SandboxCreateRequest.type` 允许 BASELINE 且 `required:[name,type]` | `orders.yaml#L1159` | 只允许 WHAT_IF；`type` 改可选、默认 WHAT_IF |
| `SandboxCompare` 缺计数字段 | `orders.yaml#L1177` | 追加 counts + `changeType` + `truncated` |
| `apply` 返回 `ResultOfSandbox` | `orders.yaml#L589` | 改 `SandboxApplyResult` |
| `TaskAdjustRequest` 无 SPLIT/MERGE 字段 | `gantt.yaml#L584` | 追加 3 字段 + 响应 2 数组 |
| `MrpSuggestion` 含 `onHandQty/transitQty/safetyStock`、缺 `materialName/suggestedQty` | `materials.yaml#L2315` | 按实现改为 `MrpShortage` 9 字段（去掉 3 个库存字段，补 2 个）——**该行已被下方“S0 第二轮”说明取代**：分析缺料行保留 3 个库存字段（13 字段），仅建议视图去掉 |
| `mrp/suggestions` 无业务参数 | `materials.yaml#L1225` | 追加 `sandboxId/orderNos/horizonDays` |
| 无 `/plans/compare`、`/plans/{id}/dispatch(es)` | `gantt.yaml`/无 | yaml 待补 |
| `/agent/*` 条件端点 | `solve.yaml#L318-L400` | 维持 50100 |
| `POST /orders/atp` 条件端点 | `orders.yaml#L311` | 维持 50100 |

**S0 本轮已完成（2026-11-04）**：`UserCreateRequest.password` 必填（`system.yaml`）、`MrpSuggestion → MrpShortage`（`materials.yaml`）、`/mrp/suggestions` 业务参数（`materials.yaml`）、`/materials/import` 的 `mode`/`X-Import-Token` 与响应外壳（`materials.yaml`）已同步；`POST /solve-tasks/incremental` 的响应结构另见 §24.3.1。其余行仍待后续。

**S0 第二轮（2026-11-04 修复批次收口）**：原来共用的 `MrpSuggestion` 已按实现拆为 `MrpShortage`（`/mrp/analysis`，含 `onHandQty/transitQty/safetyStock`）与 `MrpSuggestionView`（`/mrp/suggestions`，不含库存明细）；`orders[]` 改用 `kitted/shortages`，冻结见 §25.16。上一轮“去掉 3 个库存字段”的登记仅适用于建议视图，不适用于分析缺料行。

**落地纪律**：以上 yaml 修正由 S0 在 D1 统一提交；业务流**不得**先改 yaml 或先写与本节不一致的 DTO。

### 25.16 C42 `/mrp/analysis` 的 `orders[]` 字段冻结（W6，2026-11-04 修复批次收口）

**结论**：`orders[]` 采用实现口径 `kitted` + `shortages`，不再按 C25 §24.7 的 `status` / `shortageCount` 验收或实现。

| 实现字段 | 类型 | 语义 | 与 C25 旧字段的映射 |
|---|---|---|---|
| `orderNo` | string | 订单号 | 同 C25 |
| `productCode` | string | 产品物料编码 | C25 未列，本次登记 |
| `qty` | number | 订单数量 | C25 未列，本次登记 |
| `dueDate` | string（date） | 订单交期 | C25 未列，本次登记 |
| `kitted` | boolean | 该订单是否齐套 | `status ← kitted`：`true → FULL`，`false → 非 FULL`（不细分 `SHORTAGE`/`PARTIAL`，见 §25.17） |
| `shortages` | array | 该订单缺料明细 `{materialCode,materialName,requiredQty,allocatedQty,shortageQty}` | `shortageCount ← shortages.length` |

- `status ← kitted` 的精确含义：`kitted=true` 映射 `FULL`；`kitted=false` 只映射“非 FULL”，**不在契约层细分 `SHORTAGE`/`PARTIAL`**（该细分未定，见 §25.17）。`shortageCount ← shortages.length`。
- 旧字段 `status` / `shortageCount` **不再出现在响应中**；前端如仍依赖，按上表在本地投影，不做兼容双写。
- yaml：`materials.yaml` 的 `MrpAnalysis.orders.items` 已改为 `MrpOrderResult`（含 `MrpOrderShortage`）。
- 依据：`MrpAnalysisResponse.MrpOrderResult` / `MrpOrderShortage`（[MrpAnalysisResponse.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/mrp/MrpAnalysisResponse.java#L46-L61)）；验收证据见 `materials.yaml` 的 `/mrp/analysis` 描述。

### 25.17 已知限制 / 待定语义（2026-11-04 登记）

| # | 事项 | 当前口径 | 状态与后续 |
|---|---|---|---|
| 1 | `/mrp/analysis` 订单级 `status` 细分：`kitted=false` 时到底算 `SHORTAGE` 还是 `PARTIAL` | §25.16 只冻结 `kitted=false → 非 FULL`；**不**把 `false` 硬编成 `SHORTAGE` 或 `PARTIAL` | **未定，不是遗漏**：订单级齐套分级需要“关键缺料”的定义与整体分级语义，M3 尚未定义。后续以独立契约增量（新 C 号）冻结；在此之前实现与前端均不得自行二分，只可按 `kitted` 布尔值判断齐套与否。 |

### 25.18 C44 换型矩阵（V05-08，2026-11-05）

> 号段说明：C43 已由 V05-01「Agent 基础框架」占用（其 `V13__agent_framework.sql` 头注明 C43）；
> 迁移号 V13 亦被该文件占用，故本卡顺延为 **C44 / V14**。V8–V10 仍为空号，后续流需 D0 另行发号。

**数据模型**：新建表 `setup_matrix_entry`（`db/migration/V14__setup_matrix.sql`）。

| 字段 | 类型 | 语义 |
|---|---|---|
| `tenant_id` | UUID | 租户 |
| `key_type` | VARCHAR(32) | `MATERIAL_CODE`（FROM/TO 产品物料编码）/ `OPERATION_CODE`（FROM/TO 程序/工序编码） |
| `from_key` / `to_key` | VARCHAR(64) | FROM/TO 维度键；**两者都空 = 通配默认**，非空 = 特例 |
| `scope_type` | VARCHAR(16) | `TENANT` / `RESOURCE_GROUP` / `RESOURCE`，由 `resource_id` / `resource_group_id` 推导且与 CHECK 约束一致 |
| `resource_id` / `resource_group_id` | UUID | 生效范围；两者互斥，均空即租户级 |
| `setup_seconds` | INTEGER | 默认换型秒数，必填、≥0 |
| `same_series_setup_seconds` | INTEGER | 同系列特例，空则回退 `setup_seconds` |
| `cross_series_setup_seconds` | INTEGER | 跨系列特例，空则回退 `setup_seconds` |

**唯一业务键**：`(tenant_id, key_type, from_key, to_key, scope_type, resource_id, resource_group_id)`，V14 以 `NULLS NOT DISTINCT` 唯一索引兜底；重复返回 409/40901。

**优先级（冲突取舍）**：

1. `默认值 < 特例`：FROM/TO 精确命中（特例）优先于单边命中，单边优先于两者皆空（默认）。
2. 同键精度下：`RESOURCE > RESOURCE_GROUP > TENANT`。
3. 完全相同精度时取较大 `setupSeconds`（沿用 C12 的安全兜底，避免低估换型）。
4. 胜出条目内再按系列取值：产品族相同且非空 → `same_series_setup_seconds`；否则 → `cross_series_setup_seconds`；特例为空回退 `setup_seconds`。
5. `跨系列 > 同系列` 的实现口径：一对相邻任务只能是同系列或跨系列，不存在两者同时命中；当产品族缺失（任一侧为空）无法证明同系列时，按**跨系列**取值，避免使用更短的同系列工时低估换型。此即本实现对“跨系列优先”的判定。

**接口**（`materials.yaml`，全部真实 HTTP 验收后标 `implemented`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/setup-matrix` | 分页列表；支持 `keyType/scopeType/resourceId/resourceGroupId/fromKey/toKey` 精确过滤 |
| GET | `/api/v1/setup-matrix/{id}` | 详情 |
| POST | `/api/v1/setup-matrix` | 创建；`resourceId` 与 `resourceGroupId` 互斥；资源/资源组不存在 42200 |
| PUT | `/api/v1/setup-matrix/{id}` | 更新；`version` 必填，冲突 40901 |
| DELETE | `/api/v1/setup-matrix/{id}` | 软删除 |

`keyType` 同时接受 `PRODUCT/PROGRAM`（对外）与 `MATERIAL_CODE/OPERATION_CODE`（落库规范值）；返回体统一为规范值。

**Excel 导入**：沿用 C26 三态管线 `GET /api/v1/{entity}/import/template`、`POST /api/v1/{entity}/import?mode=preview|execute|async`，实体 slug 为 `setupMatrices`。列：`keyType/fromKey/toKey/scopeType/resourceCode/resourceGroupCode/setupSeconds/sameSeriesSeconds/crossSeriesSeconds/remark`；资源/资源组按编码解析；业务键已存在则更新，值相同则跳过。

**求解接入**：`ScheduleProblemAssembler` 装配时装载全部生效条目进 `ScheduleProblem.setupMatrix`，`TaskPlan.getSetupSeconds()` 调用 `SetupMatrixResolver`（C12 §17.3）并传入资源组与前后产品族；未命中矩阵时才回退 `SchedulingOperation.staticSetupSeconds`。即换型矩阵**真实进入 Timefold 求解**，不是只做 CRUD。

**已知限制（不是遗漏）**：

- 不校验 `from_key/to_key` 是否存在于物料/工序主数据，允许提前维护未来产品/程序。
- 产品族取自订单 `productFamily`；缺失时按跨系列处理（见优先级第 5 条）。
- 范围精度只在**同一键精度内**比较：租户级特例（FROM/TO 精确）会压过资源级默认（FROM/TO 皆空），这是“特例优先于默认”的取舍；若业务上要求“资源默认压过租户特例”，需在后续 C 号中调整优先级。

### 25.19 C45 Agent 确定性能力（V05-02/04/05 降级形态，2026-11-05）

> 号段说明：C43 已被 V05-01「Agent 基础框架与 Prompt 管理」占用，C44 为换型矩阵，本卡顺延为 **C45**。
> 本卡冻结**不依赖模型**的确定性能力；LLM 客户端、Prompt 版本、置信度展示由 V05-01 负责。
> **2026-11-05 补充（不新开号）**：V05-01 已将 `LlmGateway` 接入 `explain`，并按环境变量开关模型。本卡同步冻结此时**对外可见**的 `source` / `llm` 字段与降级语义（见 §1、§2 的补充条目）；`agent_run` 内部审计字段不在本卡范围。

**端点状态**（`solve.yaml`，均真实 HTTP 验收后翻牌）：

| 端点 | 状态 | 形态 |
|---|---|---|
| `GET /api/v1/agent/explain/{taskId}` | `implemented` | `source` 取 `EVIDENCE` 或 `LLM`；`llm` 字段输出配置与降级状态（默认未配置模型时为 `EVIDENCE`） |
| `POST /api/v1/agent/events` | `implemented` | 复用 `audit_log` 的内部事件汇 |
| `POST /api/v1/agent/whatif` | `implemented` | 复用沙箱 compare 的方案对比 |
| `POST /api/v1/agent/nl-to-dsl` | `placeholder`（501/50100） | 阻塞于 DSL→求解接线 |

**1. explain 归属映射与无数据语义**

- 契约只给 `taskId`（`task.id`），归因按 `solve_task` 持久化：服务端用 `constraint_justification.implicated_facts` 反查，**不做任何猜测**。
- 0 条命中：`200` + `available=false` + `noDataReason=NO_JUSTIFICATION_RECORD`。
- 命中多个 `solve_task`：`200` + `available=false` + `noDataReason=AMBIGUOUS_SOLVE_TASK` + `sourceSolveTaskCandidates[]`；`sourceSolveTaskId` 为 null。
- 选择 `200 + available=false` 而非 `404` 的理由：任务存在但归因缺失属于“证据不足”，不是“资源不存在”；用 404 会与真实缺失任务混淆，且前端需同时处理两种空态。`404/40400` 仅保留给“映射唯一但 `solve_task` 行不存在”。
- 证据字段必须可回溯：`sourceSolveTaskId`、`justificationIds[]`、`causes[].evidence.{justificationId,sourceSolveTaskId,constraintId,scoreImpact,weight,deduction,affectedTaskIds,createdAt}`、`planVersions[]`。
- `source` 枚举 `EVIDENCE | LLM`。未配置模型（缺省）时为 `EVIDENCE`，`llm` 输出 `configured=false / provider=none / fallbackReason=NO_PROVIDER_CONFIGURED / fallbackSource=EVIDENCE`；模型调用成功且输出通过校验时为 `LLM`，`llm.configured=true` 且 `fallbackReason` 为空。
- `llm` 字段（对外可见，字段只增不改）：`configured`、`provider`、`fallbackReason`、`fallbackSource`、`latencyMillis`、`promptTokens`、`completionTokens`。`narrative` 与 `llm` 同源：`source=LLM` 时是模型改写文本，`source=EVIDENCE` 时是降级文案。
- `fallbackReason` 枚举（本版冻结）：`NO_PROVIDER_CONFIGURED`（未配置 / 配置不全 / provider=none）、`PROVIDER_ERROR`（上游非 200）、`PROVIDER_UNAVAILABLE`（网络不可达）、`TIMEOUT`（超过超时）、`INVALID_OUTPUT`（输出不是合法 JSON 对象或缺少 narrative）、`EMPTY_RESPONSE`（空输出）。以上任何情形**响应恒为 HTTP 200**，证据区不变，不新增错误码。
- **不向上游透传原文**：上游错误正文（含 401/403 说明）不进入响应、日志与异常信息；服务端只保留 HTTP 状态码（如 `HTTP 401`）。
- **密钥不出现**：`APS_AGENT_LLM_API_KEY` 不进入响应、日志、异常信息与 `agent_run` 行；`agent_run` 仅落 `provider` / `degradation_reason` / `variables.{taskId,solveTaskId,model,latencyMillis,promptTokens,completionTokens}`。
- **不展示置信度百分比**：任何 `confidence` 字段不进入响应。

**2. 降级文案（产品定稿，服务端原样输出，客户端不得改写）**

| 场景 | 文案 |
|---|---|
| 无模型 / 配置不全 / provider=none | `AI 解读未启用（管理员未配置模型服务）。下方为求解结果中的原始归因，可正常使用。` |
| 上游返回错误（含 401/403/429）或网络不可达 | `AI 解读暂不可用（模型服务调用失败）。下方为求解结果中的原始归因，可正常使用。` |
| 输出无法解析（INVALID_OUTPUT / EMPTY_RESPONSE） | `AI 解读暂不可用（模型返回内容无法解析）。下方为求解结果中的原始归因，可正常使用。` |
| 超时（超过 `APS_AGENT_LLM_TIMEOUT_SECONDS`，缺省 10 秒） | `AI 解读超时（超过配置的超时时间）。原始归因不受影响，可重试。` |
| 无归因 | `该求解任务没有归因记录。归因由求解结果写入，早期求解可能没有记录。` |
| 推演失败 | `推演失败：求解引擎不可用。假设未产生结论，正式计划未受影响。` |

- 模型生成的段落必须逐项比对数字（任务数、延迟分钟、扣分、订单号）；任一项与证据不一致即**整段丢弃**，回退证据原文，不做部分保留。（超时/模型形态由 V05-01 实现，本卡只冻结规则。）

**3. events 审计与幂等**

- 不新建平行事件表、不引入 Redis Stream：事件落既有 `audit_log`。
- 动作/资源类型扩展：`action=AGENT_EVENT_REPORTED`，`resource_type=AGENT_EVENT`（`AuditEventType` 封闭集新增该值）。
- 事件类型封闭集：`ORDER_STATUS_CHANGED`、`SOLVE_TASK_FINISHED`、`PLAN_PUBLISHED`、`TASK_ADJUSTED`。
- 幂等：`Idempotency-Key` 必填（8–128 位 `[A-Za-z0-9._:-]`），作为事件标识写入 `audit_log.resource_id`；同租户 + 同键在同一事务内先取 `pg_advisory_xact_lock` 再“先查后写”，重复上报返回既有行（`duplicate=true`），**不新增行**；不新增唯一索引。
- 身份：租户取 `TenantContext`，操作人取 `AuditContext`；请求体携带 `userId`/`tenantId` 一律 `400/40000`。
- `occurredAt` 必须是带时区 ISO-8601；`payload` 落库前按既有审计脱敏（`AuditValues.redact`）。

**4. whatif 参数边界与对比结构**

- 仅允许 `WHAT_IF` 场景；`BASELINE`/`ARCHIVED` 返回 `422/42200`，不修改主计划。
- 支持参数：`orderNos`、`strategyId`、`engine`、`timeoutSeconds`、`moveThreadCount`、`acceptor`、`randomSeed`、`maxSteps`、`unimprovedSecondsSpentLimit`、`direction`（`FORWARD|BACKWARD|BOTTLENECK`）、`useFrozenAssignments`。未知键 `422/42200` 并逐一点名；校验全部在服务端执行。
- 求解复用 `SolveTaskLifecycleService.submitSynchronous`（同一受理/执行/落库路径）；对比复用 `SandboxService.compare`（C32），不写平行对比逻辑。
- 响应为**方案对比**：`comparison`（C32 同结构：`dueDateDeltaMinutes/utilizationDelta/setupCountDelta/added/removed/moved/changedTasks/truncated`）、`impactedOrders[]`、`impactedOrdersComplete`、`bottleneckChange`（前后瓶颈资源与逐资源利用率差，口径复用 `SandboxComparisonCalculator.resourceUtilization`）。
- 基线选择（**没有 `baselineSandboxId` 入参**）：服务端固定取当前租户唯一的 `type='BASELINE'` 场景（`SandboxRepository.findBaseline`，唯一索引 `uq_sandbox_baseline`），不是“最新主计划版本/当前主计划”，也**不存在兜底空沙箱**；租户无 BASELINE 时 `409/40901`。
- 基线可比性必须显式标注，不得只返回裸差值：响应新增 `baselineTaskCount` / `afterTaskCount` / `baselineEmpty` / `comparisonWarnings[]`。警告编码：`BASELINE_EMPTY`（基线无任务，全部差值不可解释）、`BASELINE_NOT_SCHEDULED`（基线有任务但无可用排程时间，利用率差不可解释）、`BASELINE_SPARSE`（对比侧任务数 ≥ 基线 3 倍，差值主要由任务量差异驱动）。`answer` 同步输出警告原文。
- `bottleneckChange.changed` 只在两侧都能算出瓶颈时才有判定意义：新增 `comparable` 与 `notComparableReason`（`BASELINE_NO_RESOURCE_UTILIZATION` / `AFTER_NO_RESOURCE_UTILIZATION`）；`comparable=false` 时不得把 `changed=false` 解读为“瓶颈没有变化”。
- 求解非 `SOLVED`：`200` + `available=false` + `unavailableReason=SOLVE_<errorCode|status>`，`answer` 使用上表“推演失败”原文，不返回伪造对比。
- 本版**不做多轮对话、不做自然语言外壳**；`question` 仅回显与审计，不参与语义解析。

**5. nl-to-dsl 仍保持 501**

- 事实变化（如实记录）：裁定作出时 `ScheduleProblemAssembler` 主路径传空 `constraints`；本工作区随后由并行车道开始接线，主路径已传入 `constraints`，ATP 只读路径（`ScheduleProblemAssembler` ATP 两处）仍为 `List.of()`。接线不是本卡交付范围。
- 保持 501 的原因：NL→DSL 尚无可验收的强制审核闭环——生成 spec 必须先经 `ConstraintDslService` 校验（失败 `42200` + `42201–42207`），通过后**只能落 DRAFT**，不得自动 ACTIVE，且必须人工审核 + 沙箱验证后生效；该工作流与翻译语义未实现前开放端点即产出未经审核的 DSL。
- 实现前置：上述审核闭环 + 翻译子集的语义验收（不只“能过校验”），两者齐备后再翻牌 `implemented`。

**错误码**：模型不可用 `50300`，求解引擎不可用 `50010`，不合规 DSL `42200` + `42201–42207`；不新增码。

### 25.20 C43 Agent 基础框架与 Prompt 管理（V05-01，2026-11-05）

> 号段说明：C43 已由 V05-01 预留（见 §25.18 号段说明），因 C44/C45 先落档，本节按落档顺序排 25.20。
> 迁移号：§25.13 台账中 V13 原为 W3/W4 条件流空号，C36/C37 已证**无需任何 DDL**，故该空号按 D0 追加发号改派 V05-01
> （`db/migration/V13__agent_framework.sql`，文件头已注明 C43）；V8–V10 仍为空号，后续流须另行发号。
> **已应用 V1–V12 一个字未改**，本卡只新增 V13。

**范围**：只交付框架与抽象，不实现 `explain / nl-to-dsl / events / whatif` 四端点，
四端点实现与 `solve.yaml` 状态由另一车道负责，本卡零改动。新增模块 `aps-agent`。

**1. LLM SPI 与可插拔**

| 项 | 冻结口径 |
|---|---|
| 接口 | `LlmProvider#name()` / `#available()` / `#complete(LlmRequest) -> LlmResponse`；只做「提示词进、文本出」，不承载业务语义 |
| 实现位 | `deepseek`、`qwen`（OpenAI 兼容 `/chat/completions`）、`ollama`（原生 `/api/chat`，`think=false`）、`none`（显式关闭，拒绝调用） |
| 真实性 | 四个实现位均为真实 HTTP 代码；`none` 不返回任何伪造输出。测试用假 provider **只存在于 `src/test`**，生产 classpath 不含 |
| 切换 | `aps.agent.llm.provider` 决定当前 provider；`LlmGateway#call(name, request)` 支持同 Prompt 指定 provider，用于双 provider 对照 |

**2. 降级契约（LLM 不可用时如何通知上层）**

门面 `LlmGateway` **永不抛出**，统一返回 `LlmResult`：

| 字段 | 成功 | 降级 |
|---|---|---|
| `available` | `true` | `false` |
| `output` | 模型文本 | `null` |
| `degradationReason` | `null` | 见下表 |
| `degradationDetail` | `null` | 已脱敏说明（不含 key、不含 URL，≤160 字符） |
| `latencyMillis` / `promptTokens` / `completionTokens` | 有值 | 尽量保留（超时时可能只有耗时） |

| `degradationReason` | 触发条件 |
|---|---|
| `PROVIDER_DISABLED` | 保留枚举兼容；`LlmGateway` 已不为 `provider=none` 产出该值（归入 `NO_PROVIDER_CONFIGURED`） |
| `NO_PROVIDER_CONFIGURED` | provider 名为空/未知/`none`，或配置不全（`detail` 点名缺失项，如 `missing: APS_AGENT_LLM_BASE_URL`） |
| `PROVIDER_UNAVAILABLE` | 网络不可达 / 连接失败 / IO 异常 |
| `PROVIDER_ERROR` | 上游非 200 状态码（错误正文不透传，只记 `HTTP <code>`） |
| `TIMEOUT` | 调用超时 |
| `EMPTY_RESPONSE` | HTTP 200 但正文为空 |
| `INVALID_OUTPUT` | 编排的结构化校验未通过（非 JSON 对象），`output` **保留原文** |

- 日志：`event=agent.llm.degraded provider={} reason={} latencyMs={} detail={}`（WARN）。
- 埋点建议字段：`agent.llm.provider`、`agent.llm.available`、`agent.llm.degradation_reason`、
  `agent.llm.latency_ms`、`agent.llm.prompt_tokens`、`agent.llm.completion_tokens`。
- 端点层不因模型不可用返回 5xx。`explain` 的 `llm.fallbackReason` 直接使用上表枚举值
  （`NO_PROVIDER_CONFIGURED` / `PROVIDER_ERROR` / `PROVIDER_UNAVAILABLE` / `TIMEOUT` / `INVALID_OUTPUT` / `EMPTY_RESPONSE`），
  降级文案见 C45 §2；早期建议名 `LLM_NOT_CONFIGURED` / `LLM_TIMEOUT` / `LLM_UNAVAILABLE` / `LLM_INVALID_OUTPUT` **不再使用**。
  枚举集合由 C45 冻结，本卡不新增。

**3. Prompt 版本管理（表 `agent_prompt_version`）**

- 版本号在同一 `(tenant_id, prompt_key)` 下由 `MAX(version)+1` 生成；唯一索引 `(tenant_id, prompt_key, version)` 兜底并发。
- 部分唯一索引 `(tenant_id, prompt_key) WHERE active` 保证**同一键最多一个生效版本**。
- `register` 只新增、不激活；`activate` 在一个事务内「取消旧 active + 激活目标版本」。
- `rollback` = 激活「版本号小于当前 active 的最大版本」；无 active 或无更早版本时返回空，不抛异常。
- 首次使用时按配置模板惰性写入版本 1 并激活（`ensureActive` 幂等）；此后模板**全部来自库**，不是只读常量。
- 选型理由：与运行记录同库同租户、可审计、可回滚，单表 + 部分唯一索引即可，不引入外部配置中心。

**4. 编排骨架与检查点（表 `agent_run` / `agent_checkpoint`）**

步骤（步骤号连续，从 1 开始）：

| # | 步骤名 | 行为 |
|---|---|---|
| 1 | `load-context` | 通过 `ContextSource` 取业务上下文（默认空实现，业务可注入覆盖） |
| 2 | `compose-prompt` | 取当前生效版本，替换 `{{var}}` 占位符 |
| 3 | `call-llm` | 调门面；失败写入降级原因后**继续** |
| 4 | `validate-output` | 必须是 JSON 对象（允许 ` ```json ` 围栏）；失败置 `INVALID_OUTPUT` 并保留原文 |

- 每步成功后把整份运行状态序列化为 JSONB 快照落 `agent_checkpoint`（唯一 `(run_id, step_no)`，`ON CONFLICT` 幂等覆盖）。
- 运行摘要落 `agent_run`：状态 `RUNNING / COMPLETED / DEGRADED / FAILED`、`provider`、`prompt_version`、
  `degradation_reason`、`last_completed_step`、`error_message`、`variables`。
- 续跑：`resume(runId)` 取最大 `step_no` 的检查点恢复状态，只执行步骤号大于它的步骤；`FAILED` 运行可续跑。
- LLM 不可用 → `DEGRADED`（不抛异常）；步骤/持久化基础设施异常 → `FAILED`（带原因，可续跑）。
- `aps.agent.checkpoint.enabled=false` 时仍可跑完链路，但无检查点、不可续跑。

**5. 配置项（只列变量名，值一律不入库）**

| 配置键 | 环境变量 | 默认 |
|---|---|---|
| `aps.agent.enabled` | `APS_AGENT_ENABLED` | `true` |
| `aps.agent.llm.provider` | `LLM_PROVIDER` | `none` |
| `aps.agent.llm.timeout-millis` | `APS_AGENT_LLM_TIMEOUT_MILLIS` | `10000` |
| `aps.agent.llm.max-tokens` | `APS_AGENT_LLM_MAX_TOKENS` | `512` |
| `aps.agent.llm.deepseek.base-url` | `DS_URL` | 空 |
| `aps.agent.llm.deepseek.api-key` | `DS_KEY` | 空 |
| `aps.agent.llm.deepseek.model` | `DS_MODEL`（回退 `DS_MODEL_L1`） | `deepseek-chat` |
| `aps.agent.llm.qwen.base-url` | `QW_URL` | 空 |
| `aps.agent.llm.qwen.api-key` | `QW_KEY` | 空 |
| `aps.agent.llm.qwen.model` | `QW_MODEL` | `qwen-plus` |
| `aps.agent.llm.ollama.base-url` | `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` |
| `aps.agent.llm.ollama.model` | `OLLAMA_MODEL` | `qwen3.5:9b` |
| `aps.agent.checkpoint.enabled` | `APS_AGENT_CHECKPOINT_ENABLED` | `true` |
| `aps.agent.prompt.default-key` | `APS_AGENT_PROMPT_DEFAULT_KEY` | `agent.default` |

- 变量命名对齐同一机器上的通用约定（`DS_*` / `QW_*` / `OLLAMA_*` / `LLM_PROVIDER`），避免两套命名各配一半。
- 启动阶段不发起任何模型调用；未配置或不可用时 fail-soft，不影响既有 7 个模块启动与构建。

**6. 真实验证记录（2026-11-05，仅作为当时环境证据，不是契约保证）**

- DeepSeek：真实调用 200，同 Prompt 输出 `{"answer":"pong"}`。
- 通义：真实调用 403（服务端返回免费额度耗尽），标记为不可用候选。
- Ollama（`qwen3.5:9b`）：真实调用 200，同 Prompt 输出 `{"answer":"pong"}`。
- 失败路径：错误 key → 401 映射 `PROVIDER_ERROR`；不可达地址 → 网络错误映射 `PROVIDER_ERROR`；均无抛出。

### 25.21 C46 ATP 交期预估（V05-09，2026-11-05）

> 号段说明：C43（Agent 框架）、C44（换型矩阵）、C45（Agent 确定性能力）均已落档，本卡按“被占用则顺延”取 **C46**。
> 迁移号：本卡**零 DDL**（不新增表、不改已应用迁移），V8–V10 仍为空号。
> 本卡**取代 §24 C41** 中“ATP 响应形状不再扩展”的旧结论：C41 的 `feasible/earliestDate/leadTimeDays/bottleneckResourceCode`
> 继续保留（`earliestDate` 作为 `earliestDeliveryDate` 的兼容别名），新增字段如下。C41 仅保留历史追溯。

**1. 端点与请求**

`POST /api/v1/orders/atp`（`OM_ATP` 权限）请求体全部字段可空，由服务端统一校验：

| 字段 | 类型 | 约束 | 违反 |
|---|---|---|---|
| `materialCode` | string | 必填，≤64，必须存在该物料编码 | 空/超长 → `42200`；物料不存在 → `40400` |
| `qty` | number | 必填，`0 < qty ≤ 1e9` | `42200` |
| `requestedDate` | date | 必填（交期偏好） | `42200` |
| `customerId` | uuid | 可选 | 格式非法 → `400 / 40000`（Jackson 反序列化失败） |
| `orderNo` | string | 可选，≤64；存在时必须 `CONFIRMED/SCHEDULED/RELEASED/IN_PROGRESS` 且物料一致 | 不存在 → `40400`；状态/物料不一致 → `42200` |
| `mode` | string | `FAST`（默认）/`ACCURATE` | 其他 → `42200` |
| `timeoutSeconds` | int | `ACCURATE` 可选，`1..30`；缺省取 `aps.atp.accurate-timeout-seconds` | 越界 → `42200` |
| `allowSplitBatch` | boolean | 可选，默认 `false`；当前实现**不拆批**，仅作为建议依据 | 非布尔 → `400 / 40000`（Jackson 反序列化失败） |

**2. 两档语义（准确性口径必须显式返回）**

| 档位 | `accuracy` | 算法 | 日期含义 |
|---|---|---|---|
| `FAST` | 恒为 `ESTIMATE` | 构造启发式：按工序顺序 FS/0 串行，取主资源（无主资源取编码最小的组/全部资源），时长 = `setup + run`，在资源可用窗口内做最早可行插入，避让已排 `task` 占用 | `earliestDeliveryDate` 是**估算**的按工作日历最早完工日；未做求解器验证 |
| `ACCURATE` | 完整可行解 → `VERIFIED`；否则 `UNVERIFIED` | Timefold **限时求解**（`timeoutSeconds`），题面 = CONFIRMED 订单 + FROZEN 任务 + 追加假设订单；软约束权重 `MIN_MAKESPAN=20`，其余 7 条保持默认比例（合计 100） | 仅当 `solverStatus=SOLVED` 且硬分 0、未分配 0、假设订单工序全部已分配时给出日期；`TIMEOUT/INFEASIBLE/FAILED` **必须不给出日期**（字段缺省），不得伪造精确日期 |

- `FEASIBLE` 判定：`feasible = earliestDeliveryDate ≤ requestedDate`；无日期时为 `null`（TIMEOUT）或 `false`（不可行/失败）。
- `FAST` 的假设必须随响应返回：空可用窗口按 7×24 处理；不考虑换型序列、并行资源与拆批；`planTo` 为估算完工时刻。
- `ACCURATE` 的 horizon 固定 7 天（超出窗口的需求表现为未分配/不可行，不给日期）。

**3. 只读保证（硬约束）**

- 响应必须带 `mainPlanWritten=false`；`capacityLockSuggestion` 只是**建议性标识**：
  `advisoryOnly=true`、`required`、`reason`、`resourceCodes[]`、`from/to` 均为“如需承接需要锁定哪些资源/时段”的建议。
- **禁止**任何写入：`plan_version`、`task`、`task_adjustment`、`orders`（含冻结字段）、`solve_task` 均不得新增/更新/删除。
- 需要演示“假设结果”时，只允许走 `/sandboxes` 创建 + 求解 + `compare`，不复用本端点。

**4. 响应字段（增量）**

`mode / accuracy / status / feasible / earliestDeliveryDate / earliestDate / leadTimeDays /
bottleneckResourceCode / bottlenecks[] / suggestions[] / capacityLockSuggestion / solve / assumptions /
mainPlanWritten / message`

- `status`：`OK / INFEASIBLE / TIMEOUT / FAILED`；`bottlenecks[].type` 取值
  `RESOURCE_QUEUE / CALENDAR_GAP / OPERATION_DURATION / INFEASIBLE_HORIZON / NO_RESOURCE / CRITICAL_OPERATION /
  DUE_DATE_MISS / SOLVE_TIMEOUT / INFEASIBLE`；`suggestions[].action` 取值
  `SHIFT_TO_ALTERNATE_RESOURCE / ADD_OVERTIME / ADD_SHIFT / ADJUST_PROMISED_DATE / SPLIT_BATCH / LOCK_CAPACITY /
  MAINTAIN_RESOURCE / EXTEND_TIMEOUT`。
- `solve`：`engine / timeoutSeconds / elapsedMs / timedOut / solverStatus / scoreHard / scoreSoft /
  assignedTaskCount / unassignedTaskCount / message`。

**5. 错误码（不新增码）**

| 场景 | HTTP / code |
|---|---|
| 订单不存在、物料不存在 | `404 / 40400` |
| 参数非法、订单状态不允许、物料与订单不一致、无 ACTIVE 工艺路线 | `422 / 42200` |
| 求解器不可用（`FAILED` 或适配器异常） | `503 / 50010` |

**6. 配置项**

| 配置键 | 环境变量 | 默认 |
|---|---|---|
| `aps.atp.accurate-timeout-seconds` | `APS_ATP_ACCURATE_TIMEOUT_SECONDS` | `10` |
| `aps.atp.fast-horizon-hours` | `APS_ATP_FAST_HORIZON_HOURS` | `1080` |
| `aps.atp.accurate-horizon-hours` | `APS_ATP_ACCURATE_HORIZON_HOURS` | `172` |

**7. 验收口径**

- `FAST` P95 < 2s（构造启发式，不启动求解器）。
- `ACCURATE` 超时必须返回 `UNVERIFIED` 且**无日期**；只有完整可行解才 `VERIFIED`。
- 调用前后 `plan_version` 与 `task` 行数、`max(updated_at)` 不变（只读自证）。

### 25.22 C47 约束 DSL 接入求解装配（V05-10，2026-11-05）

> 号段说明：C43（Agent 框架）、C44（换型矩阵）、C45（Agent 确定性能力）、C46（ATP）均已落档，本卡按“被占用则顺延”取 **C47**。
> 迁移号：本条**零 DDL / 无新增迁移**。V13 已被 `V13__agent_framework.sql`、V14 已被 `V14__setup_matrix.sql` 占用；V8–V10 仍为空号，不回填。
> 本卡只新增语义与可观测字段，**不改 C1–C46 正文**。

**1. 接线语义（不能再是空列表）**

- `ScheduleProblemAssembler` 必须读取当前租户**唯一** `status=ACTIVE` 的 `constraint_dsl` 行，把 `compiled_spec` 映射为 `ScheduleProblem.constraints`（现有 `ConstraintSpec` DTO），并把 8 条权重写入 `SolveConfig.parameters.softWeights`（既有 `SoftConstraintWeights` 入口）。
- **不新建并行的约束体系**：规则码与引擎约束名一一对应，复用 `ApsConstraintProvider` 的 8 条软约束。
- 优先级：`ACTIVE` DSL 权重 > `strategyId` 的历史软权重；请求 `parameters.softWeights` 亦被 DSL 覆盖时，metrics 必须写 `softWeightsOverridden=true` 并输出 WARN。
- 无 `ACTIVE` DSL 时行为保持原样：`constraints=[]`、不启用软约束（硬约束排产基线）。

**2. 支持矩阵（规则级 8/8 可映射；参数级按值判定）**

| 规则码 | 引擎约束 | 支持的参数 | 不支持的参数（拒绝） |
|---|---|---|---|
| `MIN_TARDINESS` | `MIN_TARDINESS`（超期分钟加权） | `unit` 缺省/`MINUTE` | `unit=SECOND/HOUR` |
| `EDD` | `EDD`（相邻任务交期逆序惩罚） | 无 | 任意参数 |
| `SPT` | `SPT`（相邻任务加工时长逆序惩罚） | 无 | 任意参数 |
| `SAME_PRODUCT_CONTINUOUS` | `SAME_PRODUCT_CONTINUOUS`（相邻产品族不同惩罚） | `groupBy` 缺省/`PRODUCT_FAMILY` | `groupBy=MATERIAL` |
| `MIN_SETUP_TIME` | `MIN_SETUP_TIME`（换型分钟加权，向上取整） | `unit` 缺省/`MINUTE` | `unit=SECOND/HOUR` |
| `BALANCE_LOAD` | `BALANCE_LOAD`（资源加工秒数平方和） | `metric` 缺省/`LOAD_SECONDS` | `metric=TASK_COUNT` |
| `PRIORITY_RESPECT` | `PRIORITY_RESPECT`（相邻优先级档位逆序惩罚） | 无（引擎无阈值裁剪） | 任意 `maxPriority` |
| `MIN_MAKESPAN` | `MIN_MAKESPAN`（最晚完工-最早开始分钟加权） | `unit` 缺省/`MINUTE` | `unit=SECOND/HOUR` |

**3. 拒绝语义（禁止静默忽略）**

- 激活 `POST /constraint-dsls/{id}/activate` 与回滚 `POST /constraint-dsls/{id}/rollback`：目标 YAML 编译后若含上表“不支持”的参数，返回 `422 / 42200`，message 必须点名**每条规则及其参数值**；不得落库为 `ACTIVE`。
- 求解装配时对 `ACTIVE` 行再做一次防御校验：`compiled_spec` 缺规则、重复、非 `SOFT`、`hard=true`、阶段非 `OPTIMIZATION`、权重非 0..100 整数、合计≠100、含不支持参数 → 本次求解返回 `422 / 42200` 并点名规则。
- 同一租户存在 **多条 `ACTIVE`**（不同 `code`）时，无法确定本次权重来源：`422 / 42200`，message 必须列出全部 `code`；**不得**任选一条继续。

**4. 可观测（solve_task.metrics）**

`solve_task.metrics` 必须包含（无 ACTIVE DSL 时这些键缺省）：

| 键 | 类型 | 含义 |
|---|---|---|
| `constraintDslId` | string(uuid) | 本次采用的 DSL 行主键 |
| `constraintDslCode` | string | DSL 标识 |
| `constraintDslVersion` | int | 版本号 |
| `constraintDslChecksum` | string | YAML 校验和 |
| `constraintDslWeightTotal` | int | 权重合计（恒为 100） |
| `constraintDslWeights` | object | 8 条规则码 → 权重 |
| `softWeightSource` | string | `ACTIVE_DSL` / 缺省（未启用） |
| `softWeightsOverridden` | boolean | 是否覆盖了既有 `softWeights` |

**5. 验收**

- 同一组任务、同一随机种子：未激活 DSL 与激活“减少换型”DSL 两次求解的 `scoreHard/scoreSoft` 或任务顺序必须至少有其一不同；若完全相同，必须如实报告“该规则在当前场景无可见效果”，不得伪造差异。
- 含不支持参数的 DSL 激活必须 `42200` 且未变成 `ACTIVE`；`skipped=0` 的全量回归通过。

### 25.23 C48 订单导入客户编码列（W9 补口，2026-11-05）

> 号段说明：落档前实际确认文件尾部最大号为 **C47 / §25.22**（C43–C46 分别占用 §25.20/§25.18/§25.19/§25.21），本卡按“被占用则顺延”取 **C48 / §25.23**。
> 迁移号：本条**零 DDL / 无新增迁移**，`orders.customer_id` 为 V1 既有列；已应用迁移一字不改。
> 本卡为**追加式变更**：只新增一个可选的末列与只读导出行，**不改 C1–C47 正文**，不改 `orders` 模板既有 7 列的顺序与语义。

**1. 列定义（orders 模板，第 8 列、可选）**

| 序号 | 列键 | 表头 | 必填 | 类型 | 说明 |
|---|---|---|---|---|---|
| 1–7 | `orderNo`/`source`/`materialCode`/`qty`/`dueDate`/`priority`/`remark` | 订单号/订单来源/产品物料编码/数量/交期/优先级/备注 | 按 C26 原定义 | 原类型 | **原样不动**（顺序、必填、默认值均不变） |
| 8 | `customerCode` | 客户编码 | 否 | TEXT，≤64 | 可选；按 `customer.code` 解析；留空 = 未指定客户 |

- **旧 7 列模板必须继续可导入**：缺 `customerCode` 列时不报缺失列（非必填），按“未指定客户”处理，不得因少一列整批 invalid。
- 模板文件名为 `orders-import-template.xlsx`，新列**追加在末尾**，已发出的 7 列模板位置不变。

**2. 解析与落库语义（禁止静默、禁止自动建客户）**

- 当 `customerCode` 非空：按 `tenant_id = 当前租户 AND code = :customerCode AND deleted = false` 查 `customer`。
- 命中：绑定 `orders.customer_id = customer.id`；预览返回的规范化行含 `customerId`。
- 未命中：**该行 invalid**，`errors[]` 形如 `{row: <Excel 物理行号>, field: "customerCode", code: 42200, message: "客户编码不存在: <code>（列 客户编码）"}`；`row` 即行号、`field` 即列名键、message 点名列名与编码。
- **不自动创建客户、不静默跳过**；`mode=execute` 时只要有 invalid 行即 `committed=false`、`inserted=0`，业务表行数不变。
- 客户查找为只读校验，`mode=preview` 同样生效（预览不落库）。

**3. 导出（导入—查看—导出闭环）**

- `GET /{entity}/export` 的 `orders` 导出在既有 7 列之后输出 `客户编码`（第 8 列，来自 `customer.code`），并追加只读列 `客户名称`（第 9 列，来自 `customer.name`）。
- 订单无 `customer_id`（含旧模板导入、客户留空）时两列留空，展示为“未指定”。
- 导出增加的 `客户名称` 为只读展示列，`ExcelRowReader` 按未知列忽略，导出文件仍可直接回填导入。
- 客户索引查询按 `tenant_id` 过滤，不跨租户泄漏客户编码/名称。

**4. 验收（真实 HTTP，2026-11-05，临时库 + 18086 实例）**

| 场景 | 结果 |
|---|---|
| 旧 7 列模板 execute | `200 / code=0`，`totalRows=2, validRows=2, inserted=2`，落库两行 `customer_id` 为空；traceId `0bce91f2043b47e58c060bd6874df931` |
| 8 列带 `CUS-001` execute | `200 / code=0`，`inserted=2`，落库两行 `customer_id` 正确绑定，`customer.code=CUS-001`；traceId `0f01b8379d294626a402cf4f459f4be7` |
| 错误编码 `CUS-999` preview/execute | `200 / code=0`，`invalidRows=1`，`errors=[{row:3, field:customerCode, code:42200, message:"客户编码不存在: CUS-999（列 客户编码）"}]`，`committed=false, inserted=0`；execute 后 `orders` 行数仍为 4；traceId `131b68e8d5fe4e07a366f29382423410` |
| 导出 `orders` | `200`，表头 `…备注, 客户编码, 客户名称`，带客户行输出 `CUS-001 / C48演示客户甲`，无客户行留空；traceId `2afe47b5220b4d7fa7f05c5135926dd9` |
| 模板下载 | `200`，表头 8 列，前 7 列与旧模板一致，`客户编码` 无 `*`；traceId `9f0fc5227e604d30aa0806f9a83e62c8` |

### 25.24 C49 MRP 齐套三态与替代料/预计到货表达（2026-11-05）

> 号段说明：落档前实际确认文件尾部最大号为 **C48 / §25.23**（C18 为历史空号，不可复用），本卡取 **C49 / §25.24**。
> 迁移号：本条**零 DDL / 无新增迁移**；`bom_items.substitutes`（JSONB）、`inventory_transit.expected_date`、
> `materials.default_supplier_id`、`supplier.code` 均为既有列。已应用迁移一字不改。
> 本卡为**追加式变更**：只新增可选字段，`kitted` 布尔原样保留且语义不变，不破坏既有调用方。

**1. 订单级新增字段（`MrpOrderResult`）**

| 字段 | 类型 | 可空 | 取值 / 口径 |
|---|---|---|---|
| `kittingStatus` | string | 否 | 枚举 `FULL` / `PARTIAL` / `SHORTAGE` |
| `shortageItemCount` | integer | 否 | 该订单缺料物料项数，`= orders[].shortages.length` |
| `totalItemCount` | integer | 否 | 该订单展开后的物料项总数（按物料去重） |

**2. 缺料行新增/补全字段（`MrpShortage`）**

| 字段 | 类型 | 可空 | 取值 / 口径 |
|---|---|---|---|
| `substitutes` | array\<object\> | 否（空数组） | 元素：`materialCode`(string,非空) / `priority`(integer,可空) / `ratio`(string,可空) / `availableQty`(number,非空) |
| `expectedArrival` | string(date-time) | 是 | 有未取消在途即返回最早一条 `inventory_transit.expected_date`（00:00 UTC），**无论是否晚于需求日**；无在途为 null。仅当该日期不晚于需求日时才计入 `transitQty` / `availableQty` |
| `supplierCode` | string | 是 | 物料主数据 `materials.default_supplier_id → supplier.code`；无默认供应商为 null |

`substitutes` 来源为 BOM 明细 `bom_items.substitutes` JSONB，服务层不得硬编码；按 `priority` 升序（null 置后）、同优先级按 `materialCode` 升序。
`substitutes[].availableQty` 与主物料同口径：`max(0, 现有库存 + 父件需求时点前在途 − 安全库存)`，在途按父件最早需求日裁剪。

**3. `kitted` 与 `kittingStatus` 的对应关系**

| `kitted` | `kittingStatus` | 条件 |
|---|---|---|
| `true` | `FULL` | `shortageItemCount = 0` |
| `false` | `SHORTAGE` | `shortageItemCount = totalItemCount` 且 `totalItemCount > 0` |
| `false` | `PARTIAL` | `0 < shortageItemCount < totalItemCount` |

`kitted` 保持既有语义：`shortages` 为空即 `true`，不因新增三态而改变。

**4. `expectedArrival` 口径**

- 只要有未取消的在途，就返回**最早一条的 `expected_date`**（00:00 UTC），**无论是否晚于需求日**；无在途时返回 null，不得由服务层臆造日期。
- 只有当该日期**不晚于需求日**时，才把数量计入 `transitQty` / `availableQty`。
- 上述两条可以同时成立：**日期给了、缺口仍在**（`expectedArrival` 有值而 `transitQty = 0`）。
- 验收样本：`RAW-BMS-002` 的在途为锚点 +60（`2027-01-04`），`1009` 交期为锚点 +53（`2026-12-28`），故返回
  `expectedArrival = 2027-01-04T00:00:00Z`、`transitQty = 0`，缺口 30 未冲抵。

**5. 验收（真实 HTTP，2026-11-05，临时库 + 18086 实例）**

- `POST /api/v1/mrp/analysis` 单查 `1008` → `kittingStatus=FULL`、`kitted=true`、`shortageItemCount=0`。
- 单查 `1009` → `kittingStatus=PARTIAL`、`kitted=false`、`shortageItemCount=2 / totalItemCount=16`。
- 单查 `1010` → `kittingStatus=SHORTAGE`、`kitted=false`、`RAW-BMS-001` 需求 40 / 可用 0 / 缺口 40。
- `RAW-THM-001.substitutes` 非空且含 `RAW-THM-002`（priority/ratio 与种子一致）；`RAW-BMS-002.substitutes` 含 `RAW-BMS-003`。
- `RAW-BMS-002.expectedArrival` 有值（锚点 +60），但晚于 `1009` 交期（+53），`transitQty=0`，缺口未冲抵。

### 25.25 C50 订单读模型内嵌客户编码/名称与订单甘特物料名称/规格（W1，2026-11-05）

> 号段占用依据：落档前实际确认文件尾部最大号为 **C49 / §25.24**（C18 为历史空号，不可复用）；本段取 **C50 / §25.25**。
> 迁移号：**零 DDL / 无新增迁移**；`orders.customer_id`、`orders.product_id`、`customer.code/name`、`material.name/spec` 均为 V1 既有列。
> 变更性质：**纯追加只读展示字段**；`customerId`/`materialCode` 原样保留，写请求体不变。
> 本段只冻结契约与示例；真实 18085/18086 的字段回填由订单读模型实现车道落地，本卡不改 `OrderService`/`OrderView`/`OrderGanttService`。

**1. `Order` 响应新增字段（`GET /orders`、`GET /orders/{orderNo}` 及所有返回 `Order` 的写端点）**

| 字段 | 类型 | 可空 | 只读 | 来源 / 口径 |
|---|---|---|---|---|
| `customerCode` | string(64) | 是 | 是 | `orders.customer_id → customer.code`，同一 `tenant_id`、`deleted=false` |
| `customerName` | string(256) | 是 | 是 | `orders.customer_id → customer.name`，同一 `tenant_id`、`deleted=false` |

- **不做合成**：`customerId=null` 时两字段一律 `null`；客户行不存在或已软删除时同样 `null`。禁止返回“已关联客户”“未知客户”等占位文案。
- **成对返回**：`customerCode` 与 `customerName` 要么同时有值，要么同时为 `null`；V1 中 `customer.code`/`customer.name` 为 `NOT NULL`，不得只回填其中一个。
- **租户隔离**：客户索引必须按当前 `tenant_id` 过滤，禁止跨租户泄漏编码/名称。
- **写请求不接收**：`OrderCreateRequest`/`OrderUpdateRequest` 不接受这两个字段；客户端传入不作为写入来源，仍以 `customerId` 为准。
- 兼容性：可选新增字段，既有调用方忽略即可；`customerId` 保留原样。
- `GET /customers` 当前实测 404，**本契约不要求前端依赖该端点**；客户名称必须随订单响应内嵌。

**2. 订单甘特新增字段（`GET /gantt/order` → `GanttOrderRow`）**

| 字段 | 类型 | 可空 | 只读 | 来源 / 口径 |
|---|---|---|---|---|
| `materialName` | string(256) | 是 | 是 | `orders.product_id → material.name`，同一 `tenant_id`、`deleted=false` |
| `materialSpec` | string(512) | 是 | 是 | `orders.product_id → material.spec`，同一 `tenant_id`、`deleted=false` |

- `materialCode` 与 `productFamily` 原样保留；不改排序、不改窗口过滤、不改交期线口径。
- `material` 行缺失/已软删除或 `material.spec` 为空时，对应字段为 `null`；不得用物料编码或“—”冒充名称/规格。
- 窗口内无任务的空泳道同样返回这两个字段，前端无需逐条请求 `/materials`。

**3. 示例**

```json
GET /api/v1/orders?page=1&size=20  → data.items[0]
{
  "orderNo": "ORD-C50-001",
  "materialCode": "FG-BMS-001",
  "customerId": "…",
  "customerCode": "CUS-001",
  "customerName": "华北储能客户 A"
}
```

- 目标验收（实现车道）：订单列表客户列展示 `CUS-001 华北储能客户 A`；无客户订单展示空白，不展示“已关联客户”。

### 25.26 C51 求解不可行证据（W1，2026-11-05）

> 号段占用依据：落档前实际确认文件尾部最大号为 **C50 / §25.25**（C18 为历史空号，不可复用）；本段取 **C51 / §25.26**。
> 迁移号：**V15** `infeasibility_report`；唯一键 `(tenant_id, solve_task_id)`，终态后置处理器按该唯一键 upsert。
> 变更性质：新增只读端点与 DTO；**失败/超时任务同样可查**；不改 C19 `GET /solve-tasks/{id}/justifications` 的 8 条语义。

**1. 端点**

| 项 | 冻结值 |
|---|---|
| 方法/路径 | `GET /api/v1/solve-tasks/{id}/infeasibility` |
| 入参 | `id`（int64，`solve_task.id`）；无分页、无筛选 |
| 成功 | `200` + `Result<InfeasibilityReport>`；任务不存在 `404/40400` |
| 无证据 | `200` + `verdict=NO_EVIDENCE`，不是 404、不是 500 |
| 实现状态 | `x-implementation-status: implemented`（2026-11-05）；先读 `infeasibility_report`，未命中按 `solve_task.config` 现算 |
| 权限 | `SE_SOLVE` |

**2. `InfeasibilityReport` 顶层字段**

| 字段 | 类型 | 可空 | 口径 |
|---|---|---|---|
| `solveTaskId` | int64 | 否 | 关联 `solve_task.id`；失败/超时任务也必须有 |
| `verdict` | enum | 否 | `NO_FEASIBLE_SOLUTION_FOUND` / `NO_EVIDENCE` / `PARTIAL_EVIDENCE`；**禁止** `INFEASIBLE_PROVEN` |
| `blockingTasks` | array\<BlockingTask\> | 否（空数组） | 每类最多 8 条；未分配/被阻塞任务 |
| `conflictPairs` | array\<ConflictPair\> | 否（空数组） | 每类最多 8 条；资源/顺序冲突对 |
| `resourceWindows` | array\<ResourceWindow\> | 否（空数组） | 最多 8 条；窗口与需求缺口 |
| `violatedRules` | array\<ViolatedRule\> | 否（空数组） | 最多 8 条；按 `ruleCode` 聚合 |
| `assumptions` | array\<string\> | 否（空数组） | 如“未考虑物料”“视野 168h”“冻结任务按不可动” |
| `generatedAt` | date-time | **是** | 证据生成时间；`NO_EVIDENCE` 时必须为 `null`，其余 verdict 非空 |
| `solverRunId` | string | 是 | 求解运行标识；引擎不暴露时为 `null` |
| `candidateSeed` | int64 | 是 | 复现用；单解或引擎不暴露时为 `null` |
| `truncated` | boolean | 否 | 任一数组超过 8 条被截断时为 `true`；完整证据在 DB |
| `narrative` | string | 是 | 面向人的说明；LLM 只改写，不覆盖事实；`NO_EVIDENCE` 时为 `null` |
| `llmStatus` | enum | 否 | `DISABLED` / `OK` / `FALLBACK`；`NO_EVIDENCE` 固定 `DISABLED` |

**3. 嵌套对象字段**

`BlockingTask`：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `taskId` | int64 | 是 | 已落库任务才有；缺失路由等场景为 `null` |
| `orderNo` | string | 否 | 订单业务编码 |
| `operation` | string | 是 | 工序编码/名称；无路由时为 `null` |
| `operationIndex` | integer | 是 | 工序序号；无路由时为 `null` |
| `reasonCode` | enum | 否 | `UNASSIGNED` / `NO_ROUTING` / `NO_RESOURCE` / `RESOURCE_WINDOW_SHORT` / `FROZEN_CONFLICT` / `HORIZON_EXCEEDED` / `MATERIAL_MISSING` / `OTHER` |
| `reason` | string | 否 | 确定性中文说明；不得由 LLM 改写事实部分 |

`ConflictPair`：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `taskA` | int64 | 否 | 任务主键 |
| `taskB` | int64 | 否 | 任务主键 |
| `resourceId` | uuid | 否 | 冲突资源主键 |
| `overlapStart` | date-time | 否 | 重叠开始（UTC，ISO-8601） |
| `overlapEnd` | date-time | 否 | 重叠结束（UTC，ISO-8601） |

`ResourceWindow`：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `resourceId` | uuid | 否 | 资源主键 |
| `windowStart` | date-time | 否 | 可用窗口开始 |
| `windowEnd` | date-time | 否 | 可用窗口结束 |
| `requiredHours` | number | 否 | 需求工时（小时） |
| `gapHours` | number | 否 | 缺口工时（小时，≥0） |

`ViolatedRule`：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `ruleCode` | string | 否 | 规则键（复用既有 RuleCode/ConstraintCodes） |
| `constraintName` | string | 否 | 面向人的约束名 |
| `deduction` | number | 否 | 聚合扣分；硬约束为 `0` |
| `count` | integer | 否 | 违反次数 |

**4. 语义边界**

- 三档语义（强度递增，边界如下）：
  - `NO_EVIDENCE`：未取得任何结构性证据；**绝不**能解读为“不可行”，也**绝不**能用于对外解释失败原因。典型来源：预算耗尽、引擎错误、题面无法重放。实现上不写 `constraint_justification` 行；`infeasibility_report` 仍可 upsert 一行 `verdict=NO_EVIDENCE`、`generated_at=NULL`，接口返回 200；不得编造阻塞任务。
  - `PARTIAL_EVIDENCE`：有证据但不构成必然矛盾（例如只有缺数据类阻塞，或终态不是 `FAILED`/`TIMEOUT`）；是“线索”，不是“结论”。至少一类证据为空时仍按实际返回，禁止用占位数据补齐。
  - `NO_FEASIBLE_SOLUTION_FOUND`：在“当前题面 + 当前约束模型 + 168h 视野”下存在**必然矛盾**（资源窗口无法整块容纳、`order.notBefore` 越过视野、冻结任务越界、两个冻结任务在同一资源上区间重叠、固定主资源 run-only 工时超视野窗口，五类之一）。可对外解释“为什么排不出来”，但**必须同时给出该边界**；这不是数学意义上的永不可行，换日历、加资源、解冻、放宽交期后可能可行（由 C52 反事实验证）。
- 禁用口径：禁止把“预算耗尽 / 求解超时 / 引擎失败 / 只拿到 `PARTIAL_EVIDENCE`”说成“已证明不可行”；`NO_FEASIBLE_SOLUTION_FOUND` 下允许说“存在必然矛盾”或“在该题面与约束下不可行”；**禁止**“数学上永不可行”“任何情况下都排不出来”等无限定语。
- 持久化约定：终态后置处理器按 `(tenant_id, solve_task_id)` upsert 到 `infeasibility_report`（V15），完整 C51 形状存 `report_json`；同一任务重复终态只保留一行。
- `generatedAt` 的 null 纪律由数据库 CHECK 强制（`verdict <> 'NO_EVIDENCE' OR generated_at IS NULL` 与 `verdict = 'NO_EVIDENCE' OR generated_at IS NOT NULL` 两条），**不允许任何兜底**。
- 落库未命中时按 `solve_task.config` 现算，并在 `assumptions` 明写“未落库”；`solverRunId` 不由终态事件携带，只有从 `solve_task.metrics` 取到真实值时才填充，否则保持 `null`，不编造。
- 现有 `GET /solve-tasks/{id}/justifications` 保持 C19 语义（最多 8 条），不扩容、不改排序。

### 25.27 C52 反事实修复验证（W1，2026-11-05）

> 号段占用依据：落档前实际确认文件尾部最大号为 **C51 / §25.26**（C18 为历史空号，不可复用）；本段取 **C52 / §25.27**。
> 迁移号：**零 DDL**；不写计划、不建 `solve_task`、不新增表。
> 变更性质：新增只读验证端点；反事实的日期/资源只能来自求解结果，禁止规则或 LLM 编造。

**1. 端点**

| 项 | 冻结值 |
|---|---|
| 方法/路径 | `POST /api/v1/solve-tasks/{id}/counterfactual` |
| 入参 | `CounterfactualRequest`；`id` 为 `solve_task.id` |
| 成功 | `200` + `Result<CounterfactualResult>`；任务不存在 `404/40400`；参数/kind 不合法 `422/42200` |
| 占位 | Wave 0 返回 `501/50100` |
| 权限 | `SE_SOLVE` |
| 幂等 | 只读重解，不要求 `Idempotency-Key`；不得写主计划 |

**2. `CounterfactualRequest`**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `kind` | enum | 是 | `EXTEND_CALENDAR` / `ADD_RESOURCE` / `UNFREEZE_TASKS` / `RELAX_DUE_DATE` / `SPLIT_BATCH` / `SUBSTITUTE_MATERIAL` |
| `params` | object | 是 | kind 相关参数；未知键忽略，缺必填键返回 `42200` |
| `timeoutSeconds` | integer | 否 | `1..10`，缺省 `5`；超过 10 按 `42200` |

`params` 约定（V1）：

| kind | 约定键 |
|---|---|
| `EXTEND_CALENDAR` | `resourceId`(uuid) / `from`(date-time) / `to`(date-time) |
| `ADD_RESOURCE` | `resourceCode`(string) / `resourceType`(string, 可空) |
| `UNFREEZE_TASKS` | `taskIds`(array\<int64\>) |
| `RELAX_DUE_DATE` | `orderNos`(array\<string\>) / `days`(integer, ≥1) |
| `SPLIT_BATCH` | `orderNo`(string) / `batchCount`(integer, ≥2) |
| `SUBSTITUTE_MATERIAL` | `materialCode`(string) / `substituteCode`(string) |

**3. `CounterfactualResult`**

| 字段 | 类型 | 可空 | 口径 |
|---|---|---|---|
| `solveTaskId` | int64 | 否 | 原 `solve_task.id` |
| `kind` | enum | 否 | 回显请求 kind |
| `status` | enum | 否 | `VERIFIED_FEASIBLE` / `STILL_INFEASIBLE` / `UNVERIFIED` / `OUT_OF_BUDGET` |
| `feasible` | boolean | 否 | **仅** `VERIFIED_FEASIBLE` 为 `true`；其余一律 `false` |
| `affectedTaskCount` | integer | 否 | 修改后题面中受影响任务数；无解时为 0 |
| `unassignedTaskCount` | integer | 是 | `VERIFIED_FEASIBLE`/`STILL_INFEASIBLE` 非空；`UNVERIFIED`/`OUT_OF_BUDGET` 为 `null` |
| `changes` | array\<Change\> | 否（空数组） | 仅 `VERIFIED_FEASIBLE` 非空；最多 20 条 |
| `truncated` | boolean | 否 | `changes` 被截断时为 `true` |
| `solver` | object | 是 | 发生过求解才有；`OUT_OF_BUDGET` 且未启动求解时为 `null` |
| `evidence` | `InfeasibilityReport` | 是 | 仅 `STILL_INFEASIBLE` 非空；其余为 `null` |
| `assumptions` | array\<string\> | 否（空数组） | 本次验证假设 |
| `narrative` | string | 是 | 确定性说明或 LLM 改写；不得出现 facts 中不存在的日期/任务号 |
| `message` | string | 是 | 确定性结论文案 |
| `generatedAt` | date-time | 否 | 响应生成时间 |
| `mainPlanWritten` | boolean | 否 | 恒为 `false`（只读验证，不写计划） |

`Change`：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `taskId` | int64 | 否 | 任务主键 |
| `orderNo` | string | 否 | 订单编码 |
| `operationIndex` | integer | 否 | 工序序号 |
| `resourceCode` | string | 是 | 新资源编码；不换资源时为 `null` |
| `startTime` | date-time | 是 | 新开始时间；仅 `VERIFIED_FEASIBLE` 的求解结果 |
| `endTime` | date-time | 是 | 新结束时间；仅 `VERIFIED_FEASIBLE` 的求解结果 |

`Solver`：`engine`(string) / `seed`(int64, 可空) / `hardScore`(number, 可空) / `softScore`(number, 可空) / `durationMs`(int64) / `terminationReason`(string)。

**4. 状态语义（禁止越界）**

- `VERIFIED_FEASIBLE`：hard=0 且任务全分配；`changes` 中的日期/资源必须来自本次求解结果。
- `STILL_INFEASIBLE`：hard<0；附新的 `evidence`；**不得**返回建议日期。
- `UNVERIFIED`：超时/求解不可用；UI 必须显示“未验证”，**不得**显示“可行”。
- `OUT_OF_BUDGET`：超过总预算；不展示结果。
- 总预算与候选上限：最多 3 个候选 × 5–10s，总预算 30s；不得把一次失败归因拖成 30s+。

### 25.28 C53 CTP 交期承诺询单（W1，2026-11-05）

> 号段占用依据：落档前实际确认文件尾部最大号为 **C52 / §25.27**（C18 为历史空号，不可复用）；本段取 **C53 / §25.28**。
> 迁移号：**零 DDL**；只读查询 +（未来）`audit_log` 承诺事件，不建 `solve_task`、不写订单/计划。
> 变更性质：新增同步端点；只有 `verdict=PROMISED` 才带日期，禁止用 FAST 或规则日期替代求解日期。

**1. 端点**

| 项 | 冻结值 |
|---|---|
| 方法/路径 | `POST /api/v1/orders/ctp` |
| 入参 | `CtpRequest`（JSON，同步） |
| 成功 | `200` + `Result<CtpResult>` |
| 并发忙 | `503` + `code=50300` + `Retry-After`（秒）；限流另用 `429/42900` |
| 超视野 | 业务状态 `OUT_OF_HORIZON`（HTTP 200），不是错误 |
| 占位 | Wave 0 返回 `501/50100` |
| 权限 | `OM_ATP` |
| 时限 | 单次同步上限 15s；不得无限排队 |

> `BUSY` 的 HTTP 映射以 `ErrorCode` 实际映射为准：`SERVICE_UNAVAILABLE=50300 → 503`；`TOO_MANY_REQUESTS=42900 → 429`。
> 二者不得混用，也不得用 `50300` 配 HTTP 429。

**2. `CtpRequest`**

| 字段 | 类型 | 必填 | 可空 | 说明 |
|---|---|---|---|---|
| `materialCode` | string(64) | 是 | 否 | 物料不存在返回 `404/40400` |
| `qty` | number | 是 | 否 | `0 < qty <= 1e9`；否则 `422/42200` |
| `requestedDate` | date | 是 | 否 | 客户请求交期 |
| `customerCode` | string(64) | 否 | 是 | 仅用于审计/展示；客户不存在不阻断询单 |
| `priority` | enum | 否 | 是 | `URGENT` / `HIGH` / `NORMAL` / `LOW` |
| `allowSplitBatch` | boolean | 否 | 是 | 缺省 `false`；V1 不拆批，`true` 不改变 `promisedQty` |
| `allowSubstitute` | boolean | 否 | 是 | 缺省 `false`；与 `materialPolicy` 合并见下 |
| `materialPolicy` | enum | 否 | 是 | `STRICT` / `SUBSTITUTE`，缺省 `STRICT` |
| `timeoutSeconds` | integer | 否 | 是 | `1..15`，缺省 15；越界 `42200` |

- `materialPolicy` 与 `allowSubstitute` 的合并规则：只要任一为替代（`SUBSTITUTE`/`true`），生效策略即 `SUBSTITUTE`；否则 `STRICT`。规则确定性、无隐式覆盖。
- 未知枚举值属于 JSON 反序列化错误，返回 `400/40000`；字段缺失/业务非法返回 `422/42200`。

**3. `CtpResult`**

| 字段 | 类型 | 可空 | 口径 |
|---|---|---|---|
| `inquiryId` | string(uuid) | 否 | 本次询单标识；用于审计关联 |
| `verdict` | enum | 否 | `UNVERIFIED` / `PROMISED` / `UNPROMISABLE` / `OUT_OF_HORIZON` / `NO_ROUTE` / `NO_MATERIAL` / `SOLVER_TIMEOUT` / `BUSY` |
| `promisedDate` | date | 是 | **仅** `PROMISED` 非空；必须来自求解结果的实际完成日 |
| `promisedQty` | number | 是 | **仅** `PROMISED` 非空；V1 等于请求 `qty` |
| `materialReadyAt` | date | 是 | 物料可齐日；数据缺失时为 `null`，不得猜 |
| `capacityVerifiedAt` | date-time | 是 | ACCURATE 验证完成时间；未验证时为 `null` |
| `horizonEnd` | date-time | 是 | **仅** `OUT_OF_HORIZON` 非空；= `min(requestedDate, base + 168h)` |
| `solver` | object | 是 | 仅发生 ACCURATE 验证时非空 |
| `material` | object | 是 | 物料摘要；见下，开放对象 |
| `evidence` | array\<object\> | 否（空数组） | 最多 8 条；元素 `{kind, taskIds, resourceCode, start, end, detail}` |
| `assumptions` | array\<string\> | 否（空数组） | 必须按实际返回，如 `ORDER_LEVEL_MATERIAL_GATE` / `HORIZON_168H` / `NO_RECURSIVE_MAKE` |
| `message` | string | 是 | 确定性说明 |
| `generatedAt` | date-time | 否 | 响应生成时间 |
| `mainPlanWritten` | boolean | 否 | 恒为 `false` |

`Solver`：`engine`(string) / `seed`(int64, 可空) / `hardScore`(number, 可空) / `softScore`(number, 可空) / `durationMs`(int64) / `terminationReason`(string) / `solveTaskId`(int64, **恒为 null**)。

`material`（V1 开放对象，稳定键如下；C54 不用于物料明细）：

| 键 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `bomDepth` | integer | 是 | 实际展开层数 |
| `shortages` | array\<object\> | 是 | 元素至少含 `materialCode` / `requiredQty` / `availableQty` / `shortageQty` / `expectedArrival` |
| `substitutes` | array\<object\> | 是 | 元素至少含 `materialCode` / `substituteCode` / `priority` / `availableQty` |
| `assumptions` | array\<string\> | 是 | 物料侧假设 |

**4. verdict 语义**

| verdict | 条件 | 日期 |
|---|---|---|
| `UNVERIFIED` | **物料侧已判定可齐**（`materialReadyAt` 非空且 ≤ `requestedDate`），但容量尚未由 ACCURATE 验证；C2 容量验证入口态 | `promisedDate=null`、`promisedQty=null`、`capacityVerifiedAt=null`、`solver=null` |
| `PROMISED` | ACCURATE 验证 `status=SOLVED`、`hard=0`、该订单全部工序已分配、完成时间 ≤ `requestedDate` | `promisedDate` 非空，来自求解 |
| `UNPROMISABLE` | 物料侧**已判定**需求日前无法齐套，或容量验证判定不可行 | `promisedDate=null` |
| `OUT_OF_HORIZON` | `requestedDate` 超出 168h 视野 | `promisedDate=null`，`horizonEnd` 非空 |
| `NO_ROUTE` | 订单/物料无可用路由 | `promisedDate=null` |
| `NO_MATERIAL` | 物料主数据/库存/在途/BOM/替代关系缺失或矛盾，**无法判定**（不是缺料结论） | `promisedDate=null` |
| `SOLVER_TIMEOUT` | ACCURATE 超时 | `promisedDate=null` |
| `BUSY` | 并发/槽位不可用 | `promisedDate=null`；HTTP 503 + `Retry-After` |

- `UNVERIFIED` 只表示“物料可齐、容量未验证”，**绝不等于可承诺**；C2 接入后必须改写成 `PROMISED` / `UNPROMISABLE` / `SOLVER_TIMEOUT`，不得直接对外展示为交期。
- `NO_MATERIAL` 与 `UNPROMISABLE` **互斥**：前者是数据不足、无法判定（补齐主数据/库存/在途后可重问）；后者是已判定缺料或容量不可行，属业务结论。不得把“无法判定”降级成 `UNPROMISABLE`，也不得把“已判定缺料”升级成 `NO_MATERIAL`。
- `UNVERIFIED` 时 `promisedDate`、`promisedQty`、`capacityVerifiedAt` **必须全为 `null`**，`solver` 必须为 `null`；`materialReadyAt` 应为非空。

- `UNPROMISABLE` 是“预算内未找到可行”，**不是不可行证明**。
- 任何情况下不得用 FAST 日期、规则日期或请求日期替代 `promisedDate`；UI 必须标注“来自求解验证”。
- 不写 `plan_version`/`task`/`orders`/`solve_task`；未来审计写 `audit_log` 动作 `CTP_PROMISE`（请求/响应/证据放 JSONB），不新建表。
- 超视野不是错误；不要返回 4xx/5xx。

### 25.29 C54 并行求解观测指标（W1，2026-11-05）

> 号段占用依据：落档前实际确认文件尾部最大号为 **C53 / §25.28**（C18 为历史空号，不可复用）；本段取 **C54 / §25.29**。
> 迁移号：**零 DDL**；候选明细与轨迹放 `solve_task.metrics` JSONB，不新建表。
> 变更性质：冻结 `metrics.portfolio` / `timeToBestMs` / `scoreHistory` 的稳定形状；不改 `SolveTaskDetail` 既有字段。

**1. `SolveTaskDetail.metrics` 新增键**

| 键 | 类型 | 可空 | 口径 |
|---|---|---|---|
| `portfolio` | object | 是 | portfolio 关闭或未启用时**键不存在**；启用时必须是下述对象，禁止伪造空对象 |
| `timeToBestMs` | int64 | 是 | 首个 `BestSolutionChangedEvent` 的 elapsed；无事件时为 `null`（可省略） |
| `scoreHistory` | array\<ScorePoint\> | 否 | 最多 200 点；无采样时为 `[]`，不得为 `null` |

**2. `SolvePortfolioObservation`（`metrics.portfolio`）**

| 字段 | 类型 | 可空 | 口径 |
|---|---|---|---|
| `enabled` | boolean | 否 | 本次是否启用 portfolio |
| `candidateCount` | integer | 否 | 实际候选数（含被拒候选） |
| `winnerProfile` | string | 是 | 胜出 profile；全部失败/无赢家时为 `null` |
| `degraded` | boolean | 否 | 是否被迫降级为单候选 |
| `degradedReason` | enum | 是 | `degraded=true` 时非空；否则 `null` |
| `slotWaitMs` | int64 | 是 | 槽位等待；未等待为 `0`，无法测量为 `null` |
| `candidates` | array\<Candidate\> | 否（空数组） | **最多 8 个**；超出截断并置 `truncated=true` |
| `timeToBestMs` | int64 | 是 | 与 `metrics.timeToBestMs` 同值；无事件为 `null` |
| `scoreTimeline` | array\<ScorePoint\> | 否（空数组） | **最多 200 点** |
| `truncated` | boolean | 否 | 候选或轨迹被截断时为 `true` |
| `generatedAt` | date-time | 否 | 观测生成时间 |

`Candidate`：

| 字段 | 类型 | 可空 | 口径 |
|---|---|---|---|
| `profile` | string | 否 | 候选 profile 名 |
| `seed` | int64 | 否 | 候选 seed |
| `status` | enum | 否 | `SUCCEEDED` / `FAILED` / `TIMEOUT` / `REJECTED` |
| `durationMs` | int64 | 是 | `REJECTED` 未启动求解时为 `null` |
| `hardScore` | number | 是 | 未产出分数时为 `null` |
| `softScore` | number | 是 | 未产出分数时为 `null` |
| `terminationReason` | string | 是 | 引擎终止原因；未运行为 `null` |
| `winner` | boolean | 否 | 是否有且仅有一个赢家；全部失败时全为 `false` |
| `failureReason` | enum | 是 | `SOLVER_BUSY` / `TIMEOUT` / `OOM` / `ENGINE_ERROR` / `REJECTED_BY_ADMISSION`；成功时为 `null` |

`degradedReason` 枚举：`DISABLED` / `TASK_SCALE` / `CPU_BUDGET` / `MEMORY_BUDGET` / `SOLVER_BUSY` / `QUEUE_FULL` / `CONFIG_ERROR` / `OOM` / `UNKNOWN`。

口径补充（不改 9 值闭集）：`SOLVER_BUSY` 表达**求解槽位争用**（对应 C28 `engineFallbackReason=SOLVER_BUSY`、`engineFallbackDetail=LOCK_CONTENTION`）；`QUEUE_FULL` 表达准入队列满。二者不得互相替代，也不得把槽位争用归到 `UNKNOWN`。

`ScorePoint`：沿用 `solver-api` 既有 `ScorePoint`（`elapsedMs` / `step` / `hardScore` / `softScore`）；社区版无步数口径时 `step` 固定 `0`，不得伪造。

**3. 观测指标名（非 HTTP 字段，供 W9/运维对接）**

| 指标名 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `solve.portfolio.candidates` | gauge | `metrics.portfolio.candidateCount` | 本次实际候选数 |
| `solve.portfolio.candidate.duration` | timer（tags: profile/status） | 候选起止 | 各 profile 成本 |
| `solve.portfolio.winner.profile` | counter | 选优结果 | 胜出 profile |
| `solve.portfolio.degraded` | counter（tag: reason） | 准入 | 降级原因分布 |
| `solve.time_to_best` | timer | 首个最佳解事件 | 用户体验 |
| `solve.slot.wait` | timer | 槽位 acquire | 槽位是否不足 |
| `solve.queue.depth` / `solve.rejected` | gauge/counter | worker/准入 | 过载 |
| `solve.candidate.oom` | counter | 候选 catch | 内存风险 |
| `atp.accurate.duration` / `ctp.promise.duration` | timer | ATP/CTP | 承诺体验 |
| `infeasible.verdict` / `counterfactual.verified` | counter | B | 归因质量 |
| `ctp.promised` / `ctp.unpromisable` / `ctp.out_of_horizon` | counter | C | 承诺闭环 |

- 存储截断：候选 8 个、曲线 200 点；完整原始数据留在 DB/日志，不扩大 API。
- 聚合胜出率来自跑分报告，不按请求写表。

### 25.30 C55 预留（未分配，2026-11-05）

> 号段占用依据：落档前实际确认文件尾部最大号为 **C54 / §25.29**（C18 为历史空号，不可复用）；本段取 **C55 / §25.30**，**只登记预留，不冻结任何形状**。
> 迁移号：**零 DDL**。

- **C55 未分配**：本段不定义端点、DTO、字段或指标名；任何车道不得自行启用。
- 原路线图中 C55 曾指向 `SolvePortfolioProgress`（STOMP 实时候选进度）。本批次按派工单将其顺延保留；如 A1/FE-A 确认需要实时候选曲线，由 W1 另发新号并单独落档。
- 本批次分配口径（与路线图的差异已在此登记）：
  - C50 = 订单读模型内嵌客户/物料展示字段（原预留槽位，按“按需自用”使用）；
  - C51 = 求解证据；C52 = 反事实；C53 = CTP 询单承诺；
  - C54 = 并行求解观测指标（原路线图该号曾指向 `CtpMaterialBreakdown`，本批次改用于本语义；CTP 物料明细继续放 `C53.material` 开放对象）；
  - C55 = 预留。
- 后续 C56+ 由 W1 按需发号；不得猜号、不得复用 C18 空洞。
