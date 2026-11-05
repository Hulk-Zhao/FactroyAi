# M3 QA-04 终版制品验收（关单）

> **验收对象**：`aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`
> **SHA256 `052F55F5A341E2E27045C18233ED3ED81679DEA65B544AC59679D15C80592370`**（182,220,441 B）
> 本报告的全部断言均针对上面这颗制品；**不涉及任何旧包**（旧的 182,013,745 B / `0EC320ED…B92518` 制品已在构建事故中灭失，见 [m2-build-incidents.md](../testing/m2-build-incidents.md)）。
> 该包相对 04:38 包多含 04:51 的收紧：增量重排遇到 `config` 无 `orderNos` 的历史基准一律拒绝（`42200`）。

## 0. 环境与纪律

| 项 | 值 |
| --- | --- |
| 运行方式 | 把 jar **复制到仓库外** `D:\tools\_qa04-final\` 后 `java -jar`；仓库 `aps-admin/target` 全程未写（未执行 `package`/`repackage`） |
| 临时库 | `aps_qa04_final_1`（前缀 `aps_qa04_final_`），Flyway 在空库上 applied **11 migrations → v14** |
| 端口 | 18120（实例启动前已确认空闲） |
| 租户 / 场景 | `00000000-0000-0000-0000-000000000001` / `00000000-0000-0000-0000-000000000401` |
| 基线 | 18085（PID 9704）全程未停/未重启；`aps_local` 未连接、未写入 |
| 数据 | 自造 A/B 两套主数据（各 5 单），**只确认 4 单**：`SO-SMT-2026-001/002`（A）、`SO-ESS-2026-001/002`（B） |

全量求解结果（`/solve-tasks`）：A `taskId=1`（10 tasks，traceId `9ff5e13156ce46f28b2659a6cdbf8dfc`）、B `taskId=2`（9 tasks，traceId `9b2e30a8c6bf49d2b677e37655ef11ef`），符合 A 10 / B 9 的规模要求。

---

## 1. 历史基准拒绝路径（HTTP 级，本轮唯一缺口）

### 1.1 造数：`config` 无 `orderNos` 的 SOLVED 基准

直接落一条历史形态 `solve_task`（引擎参数原样照抄历史行，**没有 `orderNos` 键**）：

```
LEGACY insert output='5' id=5
LEGACY row config={"engine": "timefold", "acceptor": "LATE_ACCEPTANCE", "direction": "FORWARD", "parameters": {}, "randomSeed": 0, "timeoutSeconds": 60, "moveThreadCount": 7, "maximumStepCount": 0, "useFrozenAssignments": true, "unimprovedSecondsSpentLimit": 10}
LEGACY config has orderNos key=false
LEGACY row status=SOLVED
```

### 1.2 默认路径（不传 `affectedTaskIds`）→ 拒绝

```
POST /api/v1/solve-tasks/incremental
{"baseSolveTaskId":5,"sandboxId":"00000000-0000-0000-0000-000000000401","reason":"QA04F-LEGACY-DEFAULT"}

HTTP 422
{"code":42200,"message":"基准求解任务 solveTaskId=5 未记录订单范围，无法做范围的增量重排；请先对该场景执行一次全量求解","data":null,
 "traceId":"b447126ce3f74581a8be45a00c591270"}
```

- `HTTP=422`、`code=42200`
- message 含 `solveTaskId=5` ✅、含“全量求解” ✅
- traceId `b447126ce3f74581a8be45a00c591270`

### 1.3 显式路径（传合法 `affectedTaskIds`）→ 同样拒绝

`affectedTaskIds=[20]`（20 是该场景内真实存在的 B 订单任务，非越界值）：

```
POST /api/v1/solve-tasks/incremental
{"baseSolveTaskId":5,"sandboxId":"00000000-0000-0000-0000-000000000401","affectedTaskIds":[20],"reason":"QA04F-LEGACY-EXPLICIT"}

HTTP 422
{"code":42200,"message":"基准求解任务 solveTaskId=5 未记录订单范围，无法做范围的增量重排；请先对该场景执行一次全量求解","data":null,
 "traceId":"041d2da0a5b848fd9d3bc24c61b7bed9"}
```

- `HTTP=422`、`code=42200`，message 与默认路径完全一致（含 `solveTaskId=5` 与“全量求解”）
- traceId `041d2da0a5b848fd9d3bc24c61b7bed9`
- **两条路径都被挡住，不存在绕过分支**

### 1.4 副作用核对

```
LEGACY plan_version count before=1
LEGACY plan_version count after=1
```

两次拒绝都没有新建 DRAFT 版本（未产生任何可发布的半成品）。

> 这条同时关闭上一轮的“红灯”：历史 `config` 无 `orderNos` 时**不再回退“场景全量”**，而是明确要求补做一次全量求解。

---

## 2. 两条已修缺陷在终版制品上的复核

### 2.1 P1 默认路径跨客户（不传 `affectedTaskIds`）

增量请求（基准 = B 的全量求解 `solveTaskId=2`，**不带** `affectedTaskIds`）：

```
POST /api/v1/solve-tasks/incremental
{"baseSolveTaskId":2,"sandboxId":"00000000-0000-0000-0000-000000000401","reason":"QA04F-DEFAULT-B"}

HTTP 200
{"code":0,"data":{"planId":"b9fa1467-0cfe-44da-90c0-52e3d4f1f9eb","solveTaskId":3,"baseSolveTaskId":2,
 "affectedOrderCount":2,"orderNos":["SO-ESS-2026-002","SO-ESS-2026-001"],...},
 "traceId":"2da4910a284f4f1b864677f592ae56bc"}
```

`orderNos` 只含 B 的两单，A 订单未被带进来（traceId `2da4910a284f4f1b864677f592ae56bc`）。

增量求解结束（`solveTaskId=3` SOLVED）后发布，返回：

```
POST /api/v1/plans/b9fa1467-0cfe-44da-90c0-52e3d4f1f9eb/publish   {"reason":"QA04F-GUARD-RUNNING"}

HTTP 200
{"code":0,"data":{"planId":"b9fa1467-0cfe-44da-90c0-52e3d4f1f9eb","planNo":"PLAN-20261104-001","version":1,
 "status":"PUBLISHED","affectedTaskCount":9,"taskCount":9,...},
 "traceId":"7d56ca99dc2f44ecbe314e049ae75826"}
```

`taskCount=9`，与 B 自己的任务数一致（traceId `7d56ca99dc2f44ecbe314e049ae75826`）。

### 2.2 发布时的落库原始输出（psql）

```sql
select coalesce(pv.plan_no,'<NULL>') as plan_no, coalesce(pv.version_no::text,'<NULL>') as ver,
       case when o.order_no like 'SO-SMT%' then 'A-SMT' else 'B-ESS' end as grp, count(*) as task_cnt
  from task t join orders o on o.id=t.order_id left join plan_version pv on pv.id=t.plan_version_id
 where t.sandbox_id='00000000-0000-0000-0000-000000000401' and t.deleted=false
 group by 1,2,3 order by 1,2,3;
```

```
      plan_no      |  ver   |  grp  | task_cnt
-------------------+--------+-------+----------
 <NULL>            | <NULL> | A-SMT |       10
 PLAN-20261104-001 | 1      | B-ESS |        9
```

```sql
select plan_no, version_no, status, task_count,
       (select count(*) from task t where t.plan_version_id=pv.id and t.deleted=false) as bound_tasks
  from plan_version pv
 where pv.sandbox_id='00000000-0000-0000-0000-000000000401' order by version_no;

select count(*) as a_tasks_bound_to_b_plan
  from task t join orders o on o.id=t.order_id
 where t.plan_version_id=(select id from plan_version where sandbox_id='00000000-0000-0000-0000-000000000401' and status='PUBLISHED' order by version_no desc limit 1)
   and t.deleted=false and o.order_no like 'SO-SMT%';

select count(*) as scene_tasks, count(*) filter (where plan_version_id is null) as null_version_tasks
  from task where sandbox_id='00000000-0000-0000-0000-000000000401' and deleted=false;
```

```
      plan_no      | version_no |  status   | task_count | bound_tasks
-------------------+------------+-----------+------------+-------------
 PLAN-20261104-001 |          1 | PUBLISHED |          9 |           9

 a_tasks_bound_to_b_plan
-------------------------
                       0

 scene_tasks | null_version_tasks
-------------+--------------------
          19 |                 10
```

**结论：A 任务 → B 版本 = 0；`task_count=9` 且 `bound_tasks=9`。P1 无回归。**

### 2.3 RUNNING 期间发布 → 拒绝

在增量求解 `solveTaskId=3` 仍 `RUNNING` 时（发布尝试后立刻查询状态仍为 `RUNNING`）调用发布：

```
POST /api/v1/plans/b9fa1467-0cfe-44da-90c0-52e3d4f1f9eb/publish   {"reason":"QA04F-GUARD-RUNNING"}

HTTP 422
{"code":42200,"message":"场景内存在未完成的求解任务（solveTaskId=3/RUNNING），发布被拒绝；请等待求解结束后再发布","data":null,
 "traceId":"1df36ad4dbc841ccb91129defb67159d"}
```

- `HTTP=422`、`code=42200`、文案含 `solveTaskId=3` ✅
- traceId `1df36ad4dbc841ccb91129defb67159d`
- 被拒后版本仍为 `DRAFT`、`task_count=0`、`bound_tasks=0`（未固化半成品快照）

### 2.4 SOLVED 后发布 → 200

见 2.1：`HTTP 200 / code 0 / status PUBLISHED / taskCount=9`，`bound_tasks=9`（见 2.2 原始输出）。

### 2.5 观察项（非缺陷，供演示口径说明）

- 全量求解本身不落 `plan_version`；发布前场景内 19 条任务 `plan_version_id` 全为 `NULL`，只有增量发布的 B 版本把 9 条 B 任务绑定到版本。
- 因此“场景任务数（19）”与“版本任务数（9）”会给两个数字，A 的 10 条任务停留在场景里、`plan_version_id=NULL`。这是“版本只固化解范围内任务”的设计语义（`PlanVersionService` 按快照 `scopeOrderNos` 取任务与回填），不是残留脏数据；但演示/对账时需要用“版本”口径而非“场景”口径解释。

---

## 3. 清理核对

| 项 | 结果 |
| --- | --- |
| 实例 | 18120 无监听（`port18120_listen=False`）；进程 35636 已停止 |
| 数据库 | `aps_qa04_final_1` 已 `DROP DATABASE ... WITH (FORCE)`；`aps_qa04_final%` 现存 **0** 个 |
| `aps*` 库总数 | 13（创建前）→ 12（清理后）。差额 1 来自**另一车道并发删除 `aps_atp_verify_tmp`**，非本次操作；本次只创建/删除了 `aps_qa04_final_1`，净变化 0 |
| 基线 | 18085 仍由 PID 9704 监听（jar 命令行未变）；`aps_local` 未连接、未写入 |
| 制品 | 仓库内 `aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar` SHA256 仍为 `052F55F5…C80592370`（182,220,441 B），验收全程未被改写 |
| 变更 | 未改代码/迁移/契约；未 commit/push；未 `package`/`repackage` |

---

## 4. 结论

| 断言 | 结果 |
| --- | --- |
| 历史基准（无 `orderNos`）默认路径 → 422/42200，文案含 `solveTaskId` + “全量求解” | 通过（traceId `b447126ce3f74581a8be45a00c591270`） |
| 历史基准显式路径（合法 `affectedTaskIds`）→ 同样 422/42200 | 通过（traceId `041d2da0a5b848fd9d3bc24c61b7bed9`） |
| P1 默认路径跨客户：A → B 版本任务数 0、`task_count=9` | 通过 |
| RUNNING 发布 → 422/42200 且文案含 `solveTaskId` | 通过（traceId `1df36ad4dbc841ccb91129defb67159d`） |
| SOLVED 后发布 → 200 且 `task_count == bound_tasks`（9=9） | 通过（traceId `7d56ca99dc2f44ecbe314e049ae75826`） |

**QA-04 通过。**
