# M1 最小闭环验收报告（SE-06）

- 任务：M1-SE-06 最小闭环验证
- Owner：dev-CLOSE
- 环境：Windows + JDK 21 + Maven 3.9.16 + PostgreSQL 15（`127.0.0.1:55432/aps_local`）
- 后端端口：`18086`（默认配置：无 profile、`aps.security.mode` 默认 `off`、Flyway 默认关闭；真库）
- 结论：**闭环成立**。`scripts/demo-m1.ps1` 一次运行完成 8 个环节、**70 条断言全部 PASS**，退出码 0；脚本结束时已停止自启后端，种子数据保留。

> **契约演进说明（C20 形状统一，2026-11-04）**：M2 起 `POST /solve-tasks` 一律返回
> `SolveTaskDetail`，状态使用六值枚举（`SUCCESS` 已废弃）。因此求解环节的断言由
> 「POST 回包内联 `tasks` + `status=SUCCESS`」迁移为「POST 取数字 `taskId` → 轮询
> `GET /solve-tasks/{id}` 到终态 → 从 GET 取 `tasks` → 与库内 `task` 行数比对」。
> 这是**契约演进导致的断言迁移，不是放宽验收**：断言数从 63 增至 70，口径不变
> （`persistedTaskCount` 与库内 demo 行数仍为 48=48，重跑仍 48→48 且旧行清除）。
> 改动前后对应关系见 §3.9。

---

## 1. 闭环链路

```mermaid
flowchart LR
    A[种子数据 seed-m1-demo.ps1] --> B[8 张 DRAFT 订单]
    B -->|PUT /orders/{no}/confirm| C[CONFIRMED]
    C -->|POST /solve-tasks| D[Timefold 求解<br/>0hard/0soft]
    D -->|事务落库| E[(task 表 48 行)]
    D -->|归因落库| F[(constraint_justification)]
    E -->|GET /gantt/resource| G[资源甘特 4 资源 / 48 任务]
    E -->|再次 POST /solve-tasks| H[旧 task 行替换<br/>行数仍为 48]
```

数据流严格按 M1 已有契约：订单确认（含 C8 三条校验）→ `ScheduleProblemAssembler`（订单→产品→ACTIVE 路线→工序节点/边→资源/日历→冻结任务）→ Timefold → `ScheduleResultWriter`（事务内替换 `task` 与归因）→ `ResourceGanttService` 读模型。

---

## 2. 脚本清单与用法

| 脚本 | 作用 | 用法 |
| --- | --- | --- |
| `scripts/seed-m1-demo.ps1` | 幂等种子数据；`-Clean` 只清理 | `.\scripts\seed-m1-demo.ps1` / `.\scripts\seed-m1-demo.ps1 -Clean` |
| `scripts/demo-m1.ps1` | 一条命令跑完整闭环并断言；默认先执行种子 | `.\scripts\demo-m1.ps1` |

`demo-m1.ps1` 常用参数：

```powershell
# 复用现有种子，不重复 seed
.\scripts\demo-m1.ps1 -SkipSeed

# 保留本次启动的后端（默认会停止）
.\scripts\demo-m1.ps1 -KeepBackend

# 指定数据库/端口
.\scripts\demo-m1.ps1 -DbHost 127.0.0.1 -DbPort 55432 -DbName aps_local -Port 18086
```

清理演示数据（后端已由脚本停止）：

```powershell
.\scripts\seed-m1-demo.ps1 -Clean
```

### 为什么种子数据用 psql 直写而不是应用接口

- 需要**固定 UUID** 才能保证重复执行时精确清理、演示可复现；
- 需要显式写入 `tenant_id`、`version`、`deleted` 等基础字段，以及多级 BOM、路线节点/边快照；
- 走主数据 API 需要几十次调用，且 ACTIVE 状态切换有额外校验，不利于“一键可复现”；
- 因此采用 **psql + 显式 SQL**，字段与 [V1__init.sql](file:///d:/tools/smart-aps-agent/db/migration/V1__init.sql)、[schema.md](file:///d:/tools/smart-aps-agent/docs/db/schema.md) 一致，并在 SQL 中显式带 `tenant_id`。

种子内容与规模：

| 类别 | 内容 |
| --- | --- |
| 物料 | `MAT-FG-001`（成品）、`MAT-SF-001`（半成品）、`MAT-RM-003/004`（原料）、`MAT-FG-002`（无 BOM 负例） |
| BOM | 2 层：FG → SF / RM-003；SF → RM-004 / RM-003，两条均 ACTIVE |
| 资源 | 6 个：2×`UNIT_PER_HOUR`（SMT）、2×`SECONDS_PER_UNIT`（总装 + 质检辅）、1×`BATCH_SECONDS`（老化）、1 个 HUMAN 辅助 |
| 日历 | `CAL-FAC-7X24`，FACTORY，00:00–24:00 全周 |
| 工序模版 | 4 个：`OP-SMT-01`、`OP-AGE-01`、`OP-ASSY-01`、`OP-QC-01` |
| 工艺路线 | 1 条 ACTIVE，6 节点（10/20/30/40/50/60），5 条 FS 边，其中 20→30 带 30 分钟 lag；节点 40 带 HUMAN 辅助资源、节点 50 带 QA 辅助资源 overrides |
| 订单 | 8 张有效 DRAFT（qty 6–14）+ 1 张无 BOM 负例 |
| 策略 | `交期优先·正向排产策略`，用于隔离本次演示的 `solve_task` 行 |

---

## 3. 实测结果（8 个环节）

运行命令与结果：

```text
PS> .\scripts\demo-m1.ps1
...
ALL PASS: 70 assertions
first solve: taskRows=48, second solve: taskRows=48, newMaxTaskId=975
Backend stopped (PID=12388). Seed data is kept.
```

### 3.1 健康检查（PASS）

```text
PASS  health.http  http=200
PASS  health.body  code=0 status=UP
```

### 3.2 确认订单（PASS，8/8）

每张订单 `PUT /api/v1/orders/{orderNo}/confirm` 均返回 200：

```text
PASS  confirm.SO-20261104-0001  http=200 code=0
PASS  confirm.SO-20261104-0001.status  status=CONFIRMED version=2
...（SO-20261104-0002 ~ 0008 同）
```

真库证据：

```text
order_no|status|version
SO-20261104-0001|CONFIRMED|2
SO-20261104-0002|CONFIRMED|2
...
SO-20261104-0008|CONFIRMED|2
SO-20261104-0009|DRAFT|1
```

### 3.3 失败路径：缺 ACTIVE BOM（PASS）

```text
PASS  confirm.noBom.http  http=422
PASS  confirm.noBom.code  code=42200 message=产品 MAT-FG-002 缺少唯一 ACTIVE 的 BOM，无法确认订单
```

### 3.4 求解（PASS，POST + GET 轮询）

```text
PASS  solve.run1.http  http=200 code=0
PASS  solve.run1.taskId  taskId=32
PASS  solve.run1.get.taskId  POST=32 GET=32
PASS  solve.run1.status  status=SOLVED
PASS  solve.run1.getTasksEqSandboxRows  GET tasks=57 sandbox rows=57
solve.run1 taskId=32 status=SOLVED GET tasks: 57 (sandbox rows: 57)
```

请求参数为 8 个显式单号、`timeoutSeconds=30`、`unimprovedSecondsSpentLimit=10`、`moveThreadCount=1`。
`POST /solve-tasks` 返回 `SolveTaskDetail`（异步受理时为 `PENDING`），脚本用 `Wait-SolveTerminal`
轮询 `GET /solve-tasks/{id}` 到 `SOLVED` 后读取 `tasks[]`。

契约样本（单独一次 POST，证明响应含数字 `taskId` 与六值状态之一）：

```json
{"code":0,"message":"success","data":{"taskId":31,"sandboxId":"00000000-0000-0000-0000-000000000401",
 "strategyId":"d0000000-0000-0000-0000-000000000801","engine":"TIMEFOLD","status":"PENDING",
 "progress":0.0,"phase":"QUEUED","elapsedMs":0,"degraded":false,"metrics":{},
 "createdAt":"2026-11-03T22:35:03.919596Z","updatedAt":"2026-11-03T22:35:03.919596Z","tasks":[]},
 "traceId":"74955a1bb6a14651a0c75e9d7f4271d2"}
```

轮询同一 `taskId=31` 到终态：

```text
GET_TASKID=31 GET_STATUS=SOLVED GET_TASKS=57
GET_SAMPLE={"orderNo":"SO-20261104-0001","resourceCode":"RES-SMT-01",
            "startTime":"2026-11-04T09:00:30Z","endTime":"2026-11-04T10:20:30Z"}
```

说明：`tasks[]` 是**场景快照**（该 `sandboxId` 下全部 `deleted=false` 任务），因此 57 = 48 条
demo 任务 + 9 条同 sandbox 其他会话（`SE04-SO-*`）既有任务；demo 的 48 条由
`solve.run1.getTasksCoverDb` 逐条比对存在性（`missing=0`），不改变验收口径。

### 3.5 solve_task 落库（PASS）

```text
PASS  solveTask.rowCount  rows=1
solve_task id=32 status=SOLVED score=0hard/0soft durationMs=10818 taskCount=48 persisted=48 justifications=12
    fallbacks: calendar=false dependency=false setupMatrix=true
PASS  solveTask.status  status=SOLVED
PASS  solveTask.scoreHard  score_hard=0
PASS  solveTask.durationMs  durationMs=10818
PASS  solveTask.persistedTaskCount  persisted=48 demoRows=48
```

真库证据：

```text
id|status|score_hard|score_soft|duration_ms|persisted|justifications
33|SOLVED|0|0|10782|48|12
32|SOLVED|0|0|10818|48|12
```

（id=32 为第一次求解，id=33 为重跑；两次均 SOLVED 且硬分为 0。）

### 3.6 task 表一致性（PASS）

```text
PASS  task.count  taskRows=48 demoRows=48 (GET sandbox tasks=57)
PASS  solve.run1.getTasksCoverDb  missing=0 of 48 demo rows in GET tasks
PASS  task.endGtStart  violations=0
PASS  task.setupEndEqStart  violations=0 (all setup_end_time = start_time)
PASS  task.setupSpan  violations=0 (expected 30s for ops 10/20/30, 10s for 40/50/60)
PASS  task.status  nonPlanned=0
PASS  task.ops  unexpectedOps=0
PASS  task.qty  mismatches=0
PASS  task.sequenceContiguous  violations=0 (1..n per resource, resources=4)
PASS  task.orderOpChain  violations=0 (each order has ops 10..60)
PASS  task.secondaryHuman  op40 with HUMAN=8/8
PASS  task.secondaryQa  op50 with QA=8/8
task rows=48, primary resources=4 (RES-ASSY-01,RES-AGE-01,RES-SMT-01,RES-SMT-02), orders=8
```

`sequence_in_resource` 组内连续性（真库）：

```text
code|tasks|seq_min|seq_max|seq_distinct
RES-ASSY-01|24|1|24|24
RES-AGE-01|8|1|8|8
RES-SMT-01|8|1|8|8
RES-SMT-02|8|1|8|8
```

单张订单的逐工序证据（`SO-20261104-0001`，时间为 UTC）：

```text
operation_index|resource|start|end|setup_start|setup_end|sequence|status
10|RES-SMT-01|2026-11-04 09:00:30|2026-11-04 10:20:30|09:00:00|09:00:30|4|PLANNED
20|RES-SMT-02|2026-11-04 20:00:30|2026-11-04 22:00:30|20:00:00|20:00:30|2|PLANNED
30|RES-AGE-01|2026-11-06 15:00:30|2026-11-06 15:10:30|15:00:00|15:00:30|5|PLANNED
40|RES-ASSY-01|2026-11-07 06:00:10|2026-11-07 06:04:10|06:00:00|06:00:10|6|PLANNED
50|RES-ASSY-01|2026-11-07 16:00:10|2026-11-07 16:02:50|16:00:00|16:00:10|8|PLANNED
60|RES-ASSY-01|2026-11-07 22:00:10|2026-11-07 22:04:10|22:00:00|22:00:10|9|PLANNED
```

辅助资源落库：

```text
SO-20261104-0001|40|["d0000000-0000-0000-0000-000000000306"]   # HUMAN
SO-20261104-0001|50|["d0000000-0000-0000-0000-000000000305"]   # QA
```

### 3.7 甘特读模型与 task 表一致（PASS）

请求：

```text
GET /api/v1/gantt/resource?sandboxId=00000000-0000-0000-0000-000000000401
    &from=2026-11-03T18:00:30Z&to=2026-11-10T19:06:10Z
    &resourceCodes=RES-ASSY-01,RES-AGE-01,RES-SMT-01,RES-SMT-02
```

```text
PASS  gantt.http  http=200 code=0
gantt resources=4, tasks=48
PASS  gantt.resourceCount  gantt=4 taskTable=4
PASS  gantt.taskCount  gantt=48 taskTable=48
PASS  gantt.idsExistInTask  missing=0
PASS  gantt.idsUnique  duplicates=0
```

抽样任务（`SO-20261104-0001 / op40`，task id=1077）逐字段比对：

```text
PASS  gantt.sample.orderNo       gantt=SO-20261104-0001 db=SO-20261104-0001
PASS  gantt.sample.opIndex       gantt=40 db=40
PASS  gantt.sample.resource      gantt=RES-ASSY-01 db=RES-ASSY-01
PASS  gantt.sample.start         epoch=1794031210
PASS  gantt.sample.end           epoch=1794031450
PASS  gantt.sample.setupStart    epoch=1794031200
PASS  gantt.sample.setupEnd      epoch=1794031210
PASS  gantt.sample.qty           gantt=8.0000 db=8.0000
PASS  gantt.sample.status        gantt=PLANNED db=PLANNED
PASS  gantt.sample.sequence      gantt=6 db=6
PASS  gantt.sample.secondary     secondaryCodes=RES-OP-01
```

说明：`from/to` 覆盖 task 表最小开始时间与最大结束时间各外扩 1 小时；附加 `resourceCodes` 过滤是为了把同租户、同 sandbox 下其他会话的既有任务排除在比对之外，不影响契约。

### 3.8 重跑不产生重复任务（PASS）

```text
before re-run: taskRows=48 maxTaskId=1119
PASS  solve.run2.http  http=200 code=0
PASS  solve.run2.taskId  taskId=33 previous=32
PASS  solve.run2.status  status=SOLVED
PASS  rerun.getTasksStable  GET tasks before=57 after=57
PASS  rerun.getTasksEqSandboxRows  GET tasks=57 sandbox rows=57
    solve_task id=33 status=SOLVED score_hard=0 durationMs=10782
PASS  solve.run2.solveTaskStatus  status=SOLVED
PASS  rerun.taskCountStable  before=48 after=48
PASS  rerun.oldRowsRemoved  rowsWithId<=1119 = 0
PASS  rerun.newIds  newMaxId=1167 oldMaxId=1119
PASS  rerun.noDuplicatePairs  duplicate(order,op) groups=0
```

两次求解的 `task` 行数均为 48；第二次运行后，第一次运行的全部 task id（≤1119）已不存在，新 id 从 1120 起增长，且 `(order_no, operation_index)` 无重复。

### 3.9 断言迁移对照（旧 63 → 新 70，口径不变）

| 环节 | 旧断言（C1 信封，POST 同步返回） | 新断言（C20 `SolveTaskDetail`，POST + GET 轮询） |
| --- | --- | --- |
| 4 求解 | `solve.run1.http` | `solve.run1.http`（保留） |
| 4 求解 | — | `solve.run1.taskId`（新增：POST 回包数字 `taskId > 0`） |
| 4 求解 | — | `solve.run1.get.taskId`（新增：GET 的 `taskId` 与 POST 一致） |
| 4 求解 | `solve.run1.status` = `SUCCESS` | `solve.run1.status` = `SOLVED`（同一终态断言，值随六值枚举迁移） |
| 4 求解 | `responseTaskCount`（POST 内联 `tasks`，仅记录不单列断言） | `solve.run1.getTasksEqSandboxRows`（新增：GET `tasks` 条数 = 库内同 sandbox 行数 57=57） |
| 5 落库 | `solveTask.rowCount/status/scoreHard/durationMs` | 原样保留（4 条） |
| 5 落库 | `solveTask.persistedTaskCount`：`persisted == POST tasks` | `solveTask.persistedTaskCount`：`persisted == 库内 demo 行数`（48=48，口径不放松） |
| 6 task 表 | `task.count`：`taskRows == POST tasks` | `task.count`：`taskRows == demoRows`（48=48）＋ `solve.run1.getTasksCoverDb`（新增：GET 快照逐条覆盖 demo 行，`missing=0`） |
| 6/7 | 其余 20 条时间/工序/序列/甘特断言 | 原样保留 |
| 8 重跑 | `solve.run2.http/status` | `solve.run2.http` 保留；`status` 改为 `SOLVED` |
| 8 重跑 | — | `solve.run2.taskId`（新增：新 `taskId >` 上次）、`rerun.getTasksStable`（GET 57→57）、`rerun.getTasksEqSandboxRows`（57=57） |
| 8 重跑 | `rerun.taskCountStable/oldRowsRemoved/newIds/noDuplicatePairs` | 原样保留（48→48、旧行清除、新 id 递增、无重复） |

合计：旧 63 → 新 70（净增 7），无一条被删除或弱化。

---

## 4. 发现的缺陷及归属

### 缺陷 1（已最小修复）：空排序列导致 MyBatis-Plus 租户拦截器解析 SQL 失败

- 现象：首次执行 `POST /api/v1/solve-tasks` 返回 **500 / 50000**。
- 后端日志关键片段：

```text
MybatisPlusException: Failed to process, Error SQL: SELECT ... FROM routing r ...
ORDER BY  , r.id ASC
Caused by: net.sf.jsqlparser.parser.ParseException: Encountered unexpected token: "," ","
```

- 根因：`RoutingMapper.selectPageByFilter` 的 XML 使用 `ORDER BY ${sortColumn} ${sortDirection}, r.id ASC`；`ScheduleProblemAssembler.resolveRouting` 传入 `null, null`，生成 `ORDER BY , r.id ASC` 的非法 SQL，租户拦截器解析时抛异常。
- 归属：`aps-infrastructure` 的 masterdata 工艺路线 Mapper XML（**不在本次可改范围**）。
- 本次处理：在允许范围内对 `aps-service/.../schedule/**` 做最小修复——[ScheduleProblemAssembler.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleProblemAssembler.java#L249-L251) 调用时传入白名单排序列 `"effective_date", "DESC"`。
- 建议 masterdata owner：在 Mapper 层给 `sortColumn/sortDirection` 默认值或改为 `<if>` 动态拼接，避免任何调用方传空值即 500。

修复后重建 `aps-admin` 可执行 jar（模块级，未跑全量 `mvn verify`）：

```powershell
mvn -B -o -pl aps-admin -am -DskipTests package
# BUILD SUCCESS, 03:43:38 +08:00
```

### 观察项（非缺陷，但需说明）

- `setupMatrixFallback=true`：演示数据未配置换型矩阵，求解器按契约回退到工序模版的静态 `setup_seconds`；该降级标记已透传到响应与 `solve_task.metrics`。
- 甘特只按主资源分组，辅助资源以 `secondaryResourceCodes` 体现在任务上，不单独成行——与现有读模型契约一致。

---

## 5. `task` 表与甘特一致性证据

| 比对项 | task 表 | 甘特接口 | 结果 |
| --- | --- | --- | --- |
| 任务数 | 48 | 48 | PASS |
| 主资源数 | 4 | 4 | PASS |
| 任务 id 集合 | 48 个 | 48 个，无缺失、无重复 | PASS |
| 抽样任务字段 | id=1077 的 11 个字段 | 同 id 逐字段相等 | PASS |
| 辅助资源 | op40: HUMAN、op50: QA | 抽样任务 `secondaryResourceCodes=RES-OP-01` | PASS |

---

## 6. 已知限制

1. **BOM 不展开层级**：求解以订单成品直接对应 ACTIVE 路线，种子中的多级 BOM 只用于确认校验，不参与工序展开或用量卷积（SE-04 已知限制）。
2. **异步执行、轮询取终态**：`POST /solve-tasks` 由生命周期服务受理后后台执行（默认 `PENDING`），本次 48 任务约 11–12 秒；脚本轮询 `GET /solve-tasks/{id}` 到 `SOLVED` 后取 `tasks[]`。
3. **`task` 无 `solve_task_id`**：同一 sandbox 下不同求解的 task 只能按订单清理；本演示通过显式 `orderNos` + 独立策略隔离，避免覆盖其他会话数据。
4. **`sequence_in_resource` 是资源维度的全局序号**：不是订单内序号，例如 `SO-20261104-0001` 的工序 10 为 4，这是 8 张订单共享 RES-SMT-01 的正常结果。
5. **共享真库存在其他会话数据**：演示断言均按显式订单清单 `SO-20261104-0001..0009` 与显式资源清单 `RES-SMT-01`、`RES-SMT-02`、`RES-ASSY-01`、`RES-AGE-01`、`RES-QC-01`、`RES-OP-01` 过滤，不使用任何 `LIKE` 前缀；未清理、未修改其他会话的订单、任务与资源。
6. **演示数据保留**：为便于复现，脚本结束后保留种子数据与最近两次求解结果；清理使用 `.\scripts\seed-m1-demo.ps1 -Clean`。

---

## 7. 复现步骤（完整）

```powershell
# 0) 数据库（若未启动）
.\scripts\local-db-up.ps1

# 1) 一条命令跑完整闭环（自动 seed + 启后端 18086 + 8 环节断言 + 停后端）
.\scripts\demo-m1.ps1

# 2) 只重跑断言、复用种子
.\scripts\demo-m1.ps1 -SkipSeed

# 3) 清理
.\scripts\seed-m1-demo.ps1 -Clean
```

预期结尾输出：

```text
ALL PASS: 70 assertions
first solve: taskRows=48, second solve: taskRows=48, newMaxTaskId=<递增>
Backend stopped (PID=<本次 PID>). Seed data is kept.
```

---

## 8. 看板状态

- 开始前：`M1-SE-06` = 待开始，owner 空
- 执行中：`M1-SE-06` = 进行中，owner `dev-CLOSE`
- 完成后：`M1-SE-06` = 已完成，owner `dev-CLOSE`

（看板更新通过 `PATCH /api/tasks/M1-SE-06` 完成。）
