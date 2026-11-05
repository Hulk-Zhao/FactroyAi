# M3-QA-04 后端缺陷修复（第 4 轮）

**2 项 / 已修 2 / 受阻 0 / 全量 631，Skipped=0（主工作树 `d:\tools\smart-aps-agent`）**

- 缺陷一（P1）：默认调用路径（不传 `affectedTaskIds`）跨客户回填 —— **已修死**，显式与缺省两条路径都收敛到基准求解范围。
- 缺陷二：`RUNNING` 期间发布固化旧任务 —— **已修**，发布入口硬拦截 42200 并返回 `solveTaskId`。
- 未新增迁移（复用 `solve_task.config` JSONB，**V15 未占用**）；未改契约（`contract-freeze.md` 无需顺延）。
- 未 commit / push，未 `mvn package`；未触碰 `aps_local*` 与 18085。

---

## 1. 缺陷一：默认路径跨客户回填

### 1.1 根因

- [IncrementalSolveService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/IncrementalSolveService.java) 的缺省分支直接遍历**整场景任务**：

  ```java
  } else {
      for (TaskRef task : sceneTasks) {
          affected.add(task.taskId());
      }
  }
  ```

  → `orders` 变成 A+B 全量，DRAFT 快照的 `scopeOrderNos` 也随之变成 A+B，发布时按该范围快照/回填。
- 同时 `solve_task.config` 只保存了 `SolveConfig`（engine/参数），**没有保存本次求解的订单范围**，导致基准解无法还原“当前版本”的订单集，缺省分支只能退化成整场景。

### 1.2 修复

1. **固化订单范围**（不改表结构）：`solve_task.config` 在保持原字段顶层不变的前提下追加 `orderNos`。
   - [ScheduleProblemAssembler#effectiveOrderNos](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleProblemAssembler.java)：显式 `orderNos` 取点名集合；缺省时取场景内全部已确认订单。
   - [ScheduleService#toConfigJson](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java)：`SolveConfig` → Map 后追加 `orderNos`。
   - [SolveTaskLifecycleService#submit](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/SolveTaskLifecycleService.java)：异步入口创建 `solve_task` 时写入该范围；同步 `ScheduleService#submit` 同样写入。
2. **两条路径按基准范围收敛**：
   - 缺省：`sceneTasks` 先与 `base.config.orderNos` 求交，再计算 affected/orders。
   - 显式：`affectedTaskIds` 必须同时属于该场景与基准求解范围；越界返回 `42200`（明确提示基准范围）。
   - 基准任务没有 `orderNos`（旧数据）时保持既有“场景全量”语义，不把默认路径变成“必须传参”。

### 1.3 反证式回归（先红后绿）

新增 [IncrementalSolveDefaultScopeRealDbIntegrationTest.java](file:///d:/tools/smart-aps-agent/aps-service/src/test/java/com/hengce/aps/service/it/IncrementalSolveDefaultScopeRealDbIntegrationTest.java)：
不传 `affectedTaskIds`，A 客户 10 条任务（2 订单）、B 客户 9 条任务（2 订单）交叉，基准 `solve_task.config.orderNos = B 的两个订单`，走真 `IncrementalSolveService` + 真 `PlanVersionService`（仅桩掉异步求解与 MRP）。

| 运行 | scopeOrderNos | A 任务 → B 版本 | B 任务 → B 版本 | `plan_version.task_count` |
| --- | --- | --- | --- | --- |
| 修复前（临时回退范围过滤） | A+B 共 4 单 | **10** | 9 | **19** |
| 修复后 | B 共 2 单 | **0** | 9 | **9** |

修复前断言失败原文：`B 版本下仍出现 A 客户任务（默认路径跨客户回填） expected: 0 but was: 10`。

### 1.4 psql 落库原文

隔离库：`aps_fix_round4`（容器 `aps-fix-r4-pg`，端口 18111）。查询按版本分组计数。

**修复前：**

```
      plan_no      | version_no |  status   | snapshot_task_count | bound_tasks
-------------------+------------+-----------+---------------------+-------------
 PLAN-20261104-001 | 1          | PUBLISHED |                  19 |          19
(1 row)

           plan_version_id            | tasks | a_tasks | b_tasks
--------------------------------------+-------+---------+--------
 95172471-2cfa-4e78-b34c-212351fbb33a |    19 |      10 |       9
(1 row)
```

**修复后：**

```
      plan_no      | version_no |  status   | snapshot_task_count | bound_tasks
-------------------+------------+-----------+---------------------+-------------
 PLAN-20261104-001 | 1          | PUBLISHED |                   9 |           9
(1 row)

           plan_version_id            | tasks | a_tasks | b_tasks
--------------------------------------+-------+---------+--------
 466c67f5-bbe5-4a44-a1a7-765fb36a36b0 |     9 |       0 |       9
 NULL                                 |    10 |      10 |       0
(2 rows)
```

> `plan_no` 的日期段来自该取证进程的系统日期；关键是指标：修复前 A 的 10 条被写入 B 版本（19 条全部绑定），修复后 B 版本仅 9 条、A 的 10 条保持 `NULL`。

### 1.5 HTTP 证据

见 [m3-qa-04-publish-http.txt](file:///d:/tools/smart-aps-agent/docs/qa/m3-qa-04-publish-http.txt)：

```
缺陷一 scoped publish | POST /api/v1/plans/69685919-19f7-45fa-b044-8d36583c4403/publish | HTTP 200 | code=0 | traceId=22753aafe1514b708f6b66240f4154a5 | A tasks=0, B tasks=9, plan.task_count=9
```

---

## 2. 缺陷二：求解 RUNNING 期间发布会固化旧任务

### 2.1 缺口位置

[PlanVersionService#publish](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/plan/PlanVersionService.java) 原先只校验计划版本自身状态（PUBLISHED 幂等、ARCHIVED 拒绝），**没有校验同租户同场景是否存在 `PENDING/RUNNING` 的 `solve_task`**。增量求解是异步的，发布先固化旧任务，求解完成重建任务后新任务 `plan_version_id` 全为 `NULL`。

### 2.2 修复

- [PlanVersionRepository#findActiveSolveTasks](file:///d:/tools/smart-aps-agent/aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/plan/PlanVersionRepository.java)：按 `(tenant_id, sandbox_id)` 查询 `status IN ('PENDING','RUNNING')`。
- `publish` 在幂等/归档判断之后、取快照之前硬拦截：

  ```java
  throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
      "场景内存在未完成的求解任务（solveTaskId=.../RUNNING），发布被拒绝；请等待求解结束后再发布");
  ```

  接口自身返回 422/42200，文案指出具体 `solveTaskId`，不依赖前端置灰。

### 2.3 测试

新增 [PlanPublishActiveSolveGuardRealDbIntegrationTest.java](file:///d:/tools/smart-aps-agent/aps-service/src/test/java/com/hengce/aps/service/it/PlanPublishActiveSolveGuardRealDbIntegrationTest.java)：
- `RUNNING` 时发布 → `BizException`，`ErrorCode.UNPROCESSABLE_ENTITY`，文案含 `solveTaskId=<id>` 与 `RUNNING`；计划仍为 `DRAFT`，绑定任务数 0。
- 置为 `SOLVED` 后再发布 → `PUBLISHED`，4 条任务 `plan_version_id` 全部回填，`task_count=4`。

HTTP 证据（[m3-qa-04-publish-http.txt](file:///d:/tools/smart-aps-agent/docs/qa/m3-qa-04-publish-http.txt)）：

```
缺陷二 publish while RUNNING | POST /api/v1/plans/fabb52e0-d57f-41da-a606-379a5d7d51a2/publish | HTTP 422 | code=42200 | traceId=23a5767b79a44760ae1e3f9f25395a26 | solveTaskId=28, plan.status=DRAFT, boundTasks=0
缺陷二 publish after SOLVED | POST /api/v1/plans/fabb52e0-d57f-41da-a606-379a5d7d51a2/publish | HTTP 200 | code=0 | traceId=b5093079c49541099ddfbfab5f9080eb | boundTasks=4, plan.task_count=4
```

---

## 3. 全量回归

- 执行：`APS_TEST_DB_ENABLED=true mvn -B -ntp test`（**主工作树** `d:\tools\smart-aps-agent`）。
- 隔离环境：PG 容器 `aps-fix-r4-pg`，端口 **18111**，库 `aps_fix_round4`；未连接 `aps_local*` 与 18085。
- 结果：**BUILD SUCCESS，631 tests，Failures=0，Errors=0，Skipped=0**。

| 模块 | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| aps-common | 19 | 0 | 0 | 0 |
| aps-agent | 23 | 0 | 0 | 0 |
| aps-domain | 0 | 0 | 0 | 0 |
| aps-infrastructure | 34 | 0 | 0 | 0 |
| solver-api | 8 | 0 | 0 | 0 |
| solver-dsl | 24 | 0 | 0 | 0 |
| solver-timefold | 104 | 0 | 0 | 0 |
| solver-ortools | 0 | 0 | 0 | 0 |
| aps-service | 364 | 0 | 0 | 0 |
| aps-web | 55 | 0 | 0 | 0 |
| aps-admin | 0 | 0 | 0 | 0 |
| **合计** | **631** | **0** | **0** | **0** |

---

## 4. 迁移与契约

- 迁移：**未新增**。订单范围固化在既有 `solve_task.config` JSONB 中，V15 未占用。
- 契约：**未变更**。新增错误分支复用既有 `42200`（`UNPROCESSABLE_ENTITY`），`contract-freeze.md` 无顺延。
- 新增测试 3 个文件，生产代码改动 6 个文件（均在计划/增量求解链路），未顺手重构。

---

## 5. 顺带两处（未修，判断如下）

- **`routing_node` 换型工时列仍缺**：该列属于 `routing_node` 数据模型/导入导出字段面，与本批“发布范围 + 发布校验”无交集；补齐需要动表/ DTO/ 导入模板的一整条链路，建议并入 routing 主线单独做，避免在本批改契约。
- **库存导入模板仍 404**：判断为 `ImportEntity.INVENTORY` 缺少模板资源或下载路由未注册，属于导入导出车道；不影响本次两个缺陷，未在本次触碰。
