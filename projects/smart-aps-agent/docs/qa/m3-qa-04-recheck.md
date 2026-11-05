# M3-QA-04 复验报告（种子客户真实数据验证）

> **制品事故提示（2026-11-05 04:38:24）**：本轮之后的二次复验期间发生“交付基线制品被覆盖（不可恢复）”事故，记录见 [m2-build-incidents.md](../testing/m2-build-incidents.md)。该事故只影响制品基线，不改变本报告的复验结论。

**P1：否（有条件修复）** — 按客户任务范围调用时 A→A 版 `10/10`、B→B 版 `9/9`、B 版本下 A 任务 `0`；但使用 API 默认支持的“场景全量”调用（不传 `affectedTaskIds`）时，A 的 10 条任务仍被写入 B 的版本（`task_count=19`），原缺陷可复现。
**其余 9 项：已修 3（P2、P6、P10）／未修 6（P3、P4、P5、P7、P8、P9）／不适用 0。**
另发现 1 项 P1 同源的新问题：增量求解异步，若在 `SOLVED` 前发布，计划快照固化为旧任务，求解完成后任务重建，`task.plan_version_id` 全为 `NULL`（复现步骤见第 8 节）。

---

## 0. 复验环境

| 项 | 值 |
| --- | --- |
| 实例 | 独立库 `aps_qa04_recheck`（PostgreSQL 15.12 @ 127.0.0.1:55432，用户 `aps`），独立端口 **18110** |
| 启动 | `aps-admin-0.1.0-SNAPSHOT.jar`（182,013,745 B，`Main-Class=org.springframework.boot.loader.launch.JarLauncher`），`--spring.profiles.active=dev --aps.security.mode=dev` |
| 数据 | 两家客户按原报告口径重建：A 苏州精工（SMT，8 物料 / 5 资源 / 5 工序模版 / 3 路线 11 节点 / 5 订单），B 安徽中科储能（储能装配，8 / 5 / 5 / 3 路线 10 节点 / 5 订单）；全部通过导入模板 + 少量 API 补绑（未改 `scripts/seed-demo.ps1`） |
| 隔离 | 未连接 `aps_local` / `aps_local_2`；未停 18085（PID 37740 全程存活）；未改业务代码、契约、OpenAPI、迁移；未 commit/push、未 `mvn package` |
| 清理 | 实例已停（18110 无监听）；`DROP DATABASE aps_qa04_recheck WITH (FORCE)` 成功；库总数 18 → 17（净增 0） |

证据目录：`target/m3qa04-recheck/evidence/`（HTTP 原文、SQL 原文与输出、导入日志）。

---

## 1. P1（高）计划发布跨客户回填 —— 复验

### 1.1 场景
同一 sandbox `00000000-0000-0000-0000-000000000401`：A、B 各确认 2 张订单 → 全量求解 → 增量重排 → 发布，A、B 各自发布后逐条查 `task.plan_version_id`。

### 1.2 正证（按客户任务范围 `affectedTaskIds`）

A 增量（`POST /api/v1/solve-tasks/incremental`）：

```json
{"baseSolveTaskId":1,"sandboxId":"00000000-0000-0000-0000-000000000401",
 "affectedTaskIds":[A 客户 10 条任务 id],"reason":"QA04 复验 A 客户范围增量"}
```

- `traceId=01195229548f4f418a245ec0a35cda23`，`affectedOrderCount=2`，`orderNos=SO-SMT-2026-001,SO-SMT-2026-002`
- 发布 `POST /plans/991946e5-6cf5-4d80-a841-8fe16ccc5c2a/publish`，`traceId=6cc39d9443484e0ca13f3b217b236c34`
  `{"code":0,...,"planNo":"PLAN-20261104-008","version":8,"taskCount":10,"affectedTaskCount":10,...}`

B 增量同理（只传 B 客户 9 条任务）：

- `traceId=b0435384f7b34c09af660879cd166614`，`affectedOrderCount=2`
- 发布 `traceId=b3300667747c4ba0a9445718d61e0d74`，`{"planNo":"PLAN-20261104-009","version":9,"taskCount":9,"affectedTaskCount":9}`

SQL 原文（`evidence/p1-check.sql`）：

```sql
select case when o.order_no like 'SO-SMT%' then 'A-苏州精工' else 'B-中科储能' end as customer,
       coalesce(pv.plan_no,'<NULL>') as plan_no,
       coalesce(pv.version_no,'<NULL>') as plan_ver,
       count(*) as task_cnt
from task t
join orders o on o.id = t.order_id
left join plan_version pv on pv.id = t.plan_version_id
where t.sandbox_id = '00000000-0000-0000-0000-000000000401' and t.deleted = false
group by 1,2,3 order by 1,2,3;

select pv.plan_no, pv.version_no, pv.status, pv.task_count,
       count(t.id) as actual_tasks,
       case when pv.task_count = count(t.id) then 'OK' else 'MISMATCH' end as verdict
from plan_version pv
left join task t on t.plan_version_id = pv.id and t.deleted = false
where pv.sandbox_id = '00000000-0000-0000-0000-000000000401'
group by pv.plan_no, pv.version_no, pv.status, pv.task_count order by pv.version_no;

select coalesce(pv.plan_no,'<NULL>') as plan_no, count(*) as a_tasks
from task t join orders o on o.id = t.order_id
left join plan_version pv on pv.id = t.plan_version_id
where t.deleted = false and o.order_no like 'SO-SMT%' group by 1;
```

结果（`evidence/p1-final.out`，发布后即时采样）：

```
  customer  |      plan_no      | plan_ver | task_cnt
 A-苏州精工 | PLAN-20261104-008 | 8        |       10
 B-中科储能 | PLAN-20261104-009 | 9        |        9

      plan_no      | version_no |  status   | task_count | actual_tasks | verdict
 PLAN-20261104-008 | 8          | PUBLISHED |         10 |           10 | OK
 PLAN-20261104-009 | 9          | PUBLISHED |          9 |            9 | OK

      plan_no      | a_tasks
 PLAN-20261104-008 |      10
```

判据全部满足：A 10 条全在 A 版本；B 版本下 A 任务 = 0；两个版本 `task_count` 等于各自范围任务数。
（001–007 历史版本 `actual_tasks=0` 是增量求解重建任务后的历史快照，见第 8 节，不计入本次归属判定。）

### 1.3 反证（默认“场景全量”路径，仍复现原缺陷）

同一场景改为**不传 `affectedTaskIds`**（该字段在契约中可选，空表示按场景全量）：

```json
{"baseSolveTaskId":4,"sandboxId":"00000000-0000-0000-0000-000000000401",
 "reason":"QA04 反证：B 全场景增量（不传 affectedTaskIds）"}
```

- `traceId=9050abf9be794aebac71f5321fe0a2a6`，`affectedOrderCount=4`，
  `orderNos=SO-SMT-2026-002,SO-ESS-2026-002,SO-ESS-2026-001,SO-SMT-2026-001` —— **A 的两张订单被纳入 B 的重排范围**
- 求解完成：`metrics.taskCount=19`；发布 `traceId=5c568c7471f14fd0a61702dfe0a80ad7`
  `{"planNo":"PLAN-20261104-007","version":7,"taskCount":19,"affectedTaskCount":19}`

同一 SQL 的结果（`evidence/p1-wide.out`）：

```
  customer  |      plan_no      | plan_ver | task_cnt
 A-苏州精工 | PLAN-20261104-007 | 7        |       10   ← A 任务被写入 B 版本
 B-中科储能 | PLAN-20261104-007 | 7        |        9

      plan_no      | ... | task_count | actual_tasks | verdict
 PLAN-20261104-007 | 7   | PUBLISHED  |         19 |           19 | OK

      plan_no      | a_tasks
 PLAN-20261104-007 |      10   ← 判据要求为 0
```

结论：**发布侧已按 DRAFT 快照 `scopeOrderNos` 收敛，但 `scopeOrderNos` 的来源（增量求解的 affected 任务集合）在默认调用下仍是整场景**。因此“同一沙箱多客户”下，只要调用方不显式传 `affectedTaskIds`，原 P1 的跨客户回填仍会发生，`task_count` 仍为 19。原报告 `PLAN-003 task_count=19` 的形态可 1:1 复现（本次为 `PLAN-007`）。

---

## 2. P2（高）routings 模板缺模版/工时 —— 已修（核心项），残留换型工时列

模板表头（`evidence/template-headers.txt`）：

```
routings: 产品物料编码* | 路线版本* | 生效日期* | 失效日期 | 节点序号 | 前驱节点序号 | 工序编码 | 工序模版编码 | 标准工时(秒) | 主资源编码
```

导入 execute 响应（`traceId=c3d22cec11d9497d8af939a3de12265d`，B 客户 `traceId` 见 `imports.log`）已直接回填：

```json
{"operationCode":"OP-SMT-A01","operationTemplateCode":"OP-SMT-A01",
 "operationTemplateId":"23006833-cbee-4c93-8fb2-59b1a1d1d42a","standardSeconds":180,
 "primaryResourceCode":"RES-SMT-A01","primaryResourceId":"20266cea-b63a-411c-b913-667c85acdd14"}
```

落库校验（`evidence/db-import-check.out`）：

```
 routing_nodes_total | with_tpl | with_std
                  21 |       21 |       21
```

**未调用任何 `PUT /routings/{id}/graph` 补链**，两家客户 6 条路线、19 条任务照常求解（A 10 / B 9，`0hard/0soft`）。原报告的“6 条路线全部人工补链”不再需要。

未知模版负向（`evidence/resp-P2-unknown-template.txt`，`traceId=acb336dace0348a7b9efd0b58b492a08`）：

```json
{"code":0,"message":"success","data":{"entity":"routings","mode":"PREVIEW","totalRows":1,
 "validRows":0,"invalidRows":1,"committed":false,
 "errors":[{"row":2,"field":"operationTemplateCode","code":42200,"message":"工序模版不存在: OP-NOT-EXIST"}]}}
```

HTTP 状态为 `200`，错误以**行级 `42200`** 返回（不是 HTTP 422），批量导入不会被整单拒绝。若调用方只判 HTTP 码会漏判，建议文档写明。

残留：模板仍**无“换型工时(秒)”列**（原 P2 三项中的第三项）。换型只能走 graph 接口手填，或依赖 `setup_seconds`。

---

## 3. P6（中）全量求解不做 MRP 齐套校验 —— 已修（主项），库存模板仍缺

### 3.1 缺料（零库存）

先把 16 个物料库存置 0（`traceId=80f069009cfd41989f825afdcb69d23e`），再全量求解：

```
POST /api/v1/solve-tasks  ->  HTTP 422
{"code":42200,"message":"MRP 齐套未通过（SHORTAGE），缺料物料: RM-SMT-A01, RM-SMT-A02, RM-SMT-A03, RM-SMT-A04, RM-SMT-A05, SF-SMT-A01",
 "data":null,"traceId":"4fb48bedb3e54f4197c523e1d86d8d9d"}
```

（原报告为零库存直接 `SOLVED`。）

### 3.2 齐套（16 物料 × 10000）

```
POST /api/v1/solve-tasks  ->  HTTP 200
{"code":0,...,"taskId":12,"status":"PENDING","traceId":"2cfb75db04f1453f8db876a54aceee53"}
GET  /api/v1/solve-tasks/12 -> "status":"SOLVED","metrics":{"taskCount":10,"durationMs":10041,...},
                               "traceId":"629802a82f9f4dde916cf3f32b1f08d9"
```

残留：`GET /api/v1/inventory/import/template` 仍为 **404**（无库存 Excel 模板，16 条库存只能 `PUT /inventory`），该项未修。

---

## 4. 时区 —— 已修

自建日历 `TZ-0812-12H`（FACTORY，班次 `08:00–12:00`，周一到周日），绑定 `RES-TZ-01`；工序 `OP-TZ-01` 标准工时 14400s、订单 `SO-TZ-001` 数量 1（任务恰为 4h，只能整段落入班次，用于强制暴露班次起点）。求解 `traceId=9bfe9dc3a2b04c498c294da36494b5e4`，落库原文（`evidence/tz-check.out`）：

```
 order_no  |      start_utc      |   start_local_cst   |    end_local_cst    | hours
 SO-TZ-001 | 2026-11-05 00:00:00 | 2026-11-05 08:00:00 | 2026-11-05 12:00:00 |  4.00
```

A 两班日历（08:00–16:00）同样落在本地整点：`SO-SMT-2026-001 / RES-SMT-A01` 本地 `2026-11-05 08:00:30`（08:00 + 30s 换型），不再是原来的 16:00。B 三班日历 00:00–08:00 段落在本地 `03:00:30`（= 00:00 班次 + setup），均符合 `aps.business.time-zone=Asia/Shanghai`。

---

## 5. 其余问题逐条对照

| 编号 | 原问题 | 复验结论 | 本次证据 |
| --- | --- | --- | --- |
| P2 | routings 模板缺工序模版/标准工时/换型工时 | **已修（两项）**，换型工时列仍缺 | 21/21 节点 `operation_template_id`、`standard_seconds` 已填充；零 graph 补链可求解 |
| P3 | boms/routings 导入默认 DRAFT | **未修** | 导入后 `status=DRAFT` 各 6 条；`PUT /orders/SO-TZ-001/confirm` 返回 `42200 产品 FG-TZ-001 缺少唯一 ACTIVE 的 BOM，无法确认订单`（`traceId=8b473296dfec42bd9b45dc043eb40064`）；须手工 `activate` |
| P4 | resources 导入无日历绑定列 | **未修** | 模板列：`资源编码* / 资源名称* / 资源类型* / 产能模式* / 产能值 / 批量 / 效率系数 / 换型工时(秒) / 收尾工时(秒)`，无 calendar；导入后 `10 个资源 / 0 个有 calendar_id`，逐个 `PUT /resources/{code}` 补绑后 10/10 |
| P5 | orders 导入无客户列 | **未修** | 模板列：`订单号* / 订单来源 / 产品物料编码* / 数量* / 交期* / 优先级 / 备注`，无 customer；导入后 `10 订单 / 0 有 customer_id`，本次改用 `PUT /orders/{no}` 逐条回填（未写 SQL） |
| P6 | 全量不做齐套校验；库存无模板 | **已修（校验）**，库存模板仍缺 | 缺料 HTTP 422/42200（`traceId=4fb48…`）；齐套 HTTP 200 → SOLVED；`inventory/import/template` 404 |
| P7 | execute `inserted` 是头数不是行数 | **未修** | `boms` A：`totalRows=8, inserted=3`；`routings` A：`totalRows=11, inserted=3`；B：`12→3`、`10→3`；明细仍为 `bom_item=20`、`routing_node=21` |
| P8 | 求解详情返回沙箱全量任务 | **未修** | 时区求解 `metrics.taskCount=1` 而 `data.tasks` 长度 20（含 A/B 全部任务）；齐套全量 `metrics.taskCount=10`、`tasks=20` |
| P9 | 多线程/换型矩阵未生效 | **未修**（信息级） | `moveThreadCountApplied=true` 但 `moveThreadCountEffective=NONE`、`multiThreadedAvailable=false`、`setupMatrixFallback=true` |
| P10 | 给定 jar 不可执行 | **已修** | `aps-admin-0.1.0-SNAPSHOT.jar` 182,013,745 B，MANIFEST 含 `Main-Class: ...JarLauncher`、`Start-Class: ...ApsAdminApplication`，本次以 `java -jar` 直接启动成功 |

---

## 6. 仍然存在的问题（按严重度）

1. **P1 默认路径仍跨客户**（高）：不传 `affectedTaskIds` 时，B 的增量范围 = 沙箱内所有非冻结任务，发布后 A 的 10 条任务被写入 B 版本、`task_count=19`。建议：增量接口对“场景全量”显式限制/二次确认，或在发布时按 `affectedOrderNos ∪ 本客户` 再校验。
2. **增量求解异步 + 发布无状态校验**（高，新发现）：`POST /solve-tasks/incremental` 返回时求解仍 `RUNNING`；此时 `POST /plans/{id}/publish` 会把 **旧的** 任务集合固化（`task_count=10/19`），求解完成后任务被重建，最终 `task.plan_version_id` 全为 `NULL`。首次复现：`PLAN-001 actual_tasks=0`、`PLAN-002 actual_tasks=0`。复现步骤：提交 incremental → 不轮询 → 立即 publish。
3. **全量求解会重建任务**（中）：发布后再次全量求解（P6 的 `solve_task=12`）会把 `PLAN-008/009` 的 10/9 条归属清空（历史 `task_count` 与实际散挂不再一致），影响计划追溯与回滚。
4. **P3/P4/P5**（中）：导入链路仍要求 3 次人工补链（激活 BOM/路线、资源绑日历、订单回填客户），真实客户批量导入不可用。
5. **P7/P8**（低）：导入计数与求解详情的“范围”语义仍不一致，对账/排障易误判。
6. **P2 换型工时列缺失、P6 库存模板缺失**（低）。

---

## 7. 清理核对

| 项 | 结果 |
| --- | --- |
| 实例 | 18110 无监听；进程已停止 |
| 数据库 | `aps_qa04_recheck` 已 `DROP ... WITH (FORCE)`；`select count(*) from pg_database` 18 → 17 |
| 基线 | 18085（PID 37740）全程未停/未重启；`aps_local`、`aps_local_2` 未连接 |
| 变更 | 未改代码/契约/OpenAPI/迁移；未 commit/push；未 `mvn package` |
| 端口 | 仅新增 18110，现已释放；与禁用端口（18085/18092/18093/18098/18099/18101/18193）无冲突 |

> 终版制品（SHA256 `052F55F5…C80592370`）的复核与关单结论见 [m3-qa-04-final-acceptance.md](m3-qa-04-final-acceptance.md)；本报告 §6 的 P1 与“发布无状态校验”两项已在终版制品上验证关闭。

