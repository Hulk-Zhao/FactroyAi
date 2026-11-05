# M3-QA-04 种子客户真实数据验证报告

**2 家客户 / 覆盖实体 6 类 / 链路通过 / 发现问题 10 项**

- 客户 A：苏州精工电子（SMT 贴片），`CUS-101`
- 客户 B：安徽中科储能（储能装配），`CUS-102`
- 必测 6 类实体全部走真实导入链路：materials、boms、routings、resources、calendars、orders；另补 customers、operationTemplates、strategies 3 类
- 两家客户均完成「确认 → 冻结 → 释放 → 开工 → 全量求解 → 增量重排 → 发布」链路，`solve_task` 均为 `SOLVED / score_hard=0 / unassignedTaskCount=0`，`plan_version` 已落库
- 业务验收反馈需由客户/用户提供，本报告只做实可自动验证部分，待确认口径见最后一节

---

## 1. 环境与执行口径

| 项 | 值 |
| --- | --- |
| 应用端口 | `18097`（独立实例，PID `24668`，已停止） |
| 数据库 | `aps_seed_customers`（PostgreSQL 15.12，`127.0.0.1:55432`，用户 `aps`） |
| 安全开关 | `--aps.security.mode=dev --aps.security.dev.api-key-enabled=true --aps.security.api-keys.read=m3qa04-read-2026 --aps.security.api-keys.write=m3qa04-write-2026` |
| 制品 | `aps-admin/target/aps-admin-0.1.0-SNAPSHOT-repackaged.jar`（未重新构建） |
| 租户 / 沙箱 | `00000000-0000-0000-0000-000000000001` / `00000000-0000-0000-0000-000000000401`（BASELINE） |
| Flyway | 未改动 V1–V7、V11、V12，未新增迁移 |
| 基线隔离 | 未连接 `18085`（PID `29264`）、未触碰 `aps_local` / `aps_local_2` |
| 版本控制 | 未 commit / 未 push |

**为什么用 repackaged 制品**：任务书给定的 `aps-admin-0.1.0-SNAPSHOT.jar` 仅 30 KB，MANIFEST 无 `Main-Class`，`java -jar` 直接失败；同目录已有 `aps-admin-0.1.0-SNAPSHOT-repackaged.jar`（181 MB，含依赖），本次直接使用，未执行构建。

**导入 / 补链方式**：主数据一律经「模板 → preview → execute」，未直接 INSERT。仅两处例外：

1. 库存无 Excel 导入模板，改用 `PUT /api/v1/inventory` 批量 upsert（16 个物料，onHandQty=10000，仓 `DEFAULT`）；
2. `orders` 导入模板无客户列，导入后用一条 SQL `UPDATE orders SET customer_id=...` 按订单号前缀回填（见问题 P5）。

---

## 2. 客户画像与数据规模

| 维度 | 客户 A（SMT 贴片） | 客户 B（储能装配） |
| --- | --- | --- |
| 客户主数据 | `CUS-101` 苏州精工电子，VIP，leadTimeDays=5 | `CUS-102` 安徽中科储能，NORMAL，leadTimeDays=7 |
| 物料 | 8 个（成品 2、半成品 1、原材料 5） | 8 个（成品 2、半成品 1、原材料 5） |
| BOM | 3 头 / 8 子件行；最深 2 层（FG-SMT-A01 → SF-SMT-A01 → 原材料） | 3 头 / 12 子件行；最深 2 层（FG-ESS-B01 → SF-ESS-B01 → 原材料） |
| 工序模版 | 5 个（SMT 贴片/回流焊/AOI/功能测试/包装） | 5 个（模组装配/电池簇装配/老化/EOL 测试/包装发运） |
| 工艺路线 | 3 条 / 11 节点，FS 顺序（10→20→30→40→50） | 3 条 / 10 节点，FS 顺序 |
| 资源 | 5 个（贴片机、回流焊炉、AOI、功能测试、质检员） | 5 个（模组线、电池簇线、老化柜、EOL 台、质检站） |
| 工作日历 | `苏州精工两班日历`：周一至周六，08:00–16:00 / 16:00–24:00（周日停产） | `中科储能三班日历`：7×24，三班 |
| 订单 | `SO-SMT-2026-001..005`，优先级 URGENT/HIGH/NORMAL/LOW，交期 11-06 ~ 11-20 | `SO-ESS-2026-001..005`，优先级 URGENT/HIGH/NORMAL/LOW，交期 11-08 ~ 11-25 |

客户编码使用 `CUS-{NNN}`，导入走 `customers` 模板（已存在则更新，幂等键 `tenant_id + code`）。

---

## 3. 客户 A（苏州精工电子）导入对账

| 实体 | 导入文件行 | preview 总/有效/无效 | preview 错误行 | execute 插入/更新/跳过 | 库内落库对象 | 对账 |
| --- | --- | --- | --- | --- | --- | --- |
| customers | 1 | 1 / 1 / 0 | 0 | 1 / 0 / 0 | customer 1（CUS-101） | 一致 |
| materials | 8 | 8 / 8 / 0 | 0 | 8 / 0 / 0 | material 8 | 一致 |
| resources | 5 | 5 / 5 / 0 | 0 | 5 / 0 / 0 | resource 5 | 一致 |
| calendars | 1 | 1 / 1 / 0 | 0 | 1 / 0 / 0 | calendar 1（苏州精工两班日历） | 一致 |
| operationTemplates | 5 | 5 / 5 / 0 | 0 | 5 / 0 / 0 | operation_template 5 | 一致 |
| boms | 8 | 8 / 8 / 0 | 0 | 3 / 0 / 0 | bom 3 头 + bom_item 8 行 | 一致（文件行=子件行 8） |
| routings | 11 | 11 / 11 / 0 | 0 | 3 / 0 / 0 | routing 3 头 + routing_node 11 节点 | 一致（文件行=节点行 11） |
| orders | 5 | 5 / 5 / 0 | 0 | 5 / 0 / 0 | orders 5 | 一致 |
| strategies | 1 | 1 / 1 / 0 | 0 | 1 / 0 / 0 | strategy 1（苏州精工-交期优先策略） | 一致 |

imports 原始记录：`target/m3qa04/imports-A.log`（每行含 http=200、traceId、total/valid/invalid/inserted/updated/skipped）。

导入后补链（API，不 INSERT）：

| 补链动作 | 接口 | 结果 |
| --- | --- | --- |
| routing 绑定工序模版 + 标准工时 + 主资源 | `PUT /api/v1/routings/{id}/graph` | 3 条全部 200 |
| routing 激活 | `POST /api/v1/routings/{id}/activate` | 3 条全部 200，status=ACTIVE |
| 资源绑定客户日历 | `PUT /api/v1/resources/{code}` | 5 条全部 200 |
| BOM 激活 | `POST /api/v1/boms/{id}/activate` | 全部 ACTIVE |

> 原因见 P2 / P3 / P4：导入模板本身不含模板/工时列、不含 calendarId 列，且 boms/routings 导入后为 DRAFT。

---

## 4. 客户 B（安徽中科储能）导入对账

| 实体 | 导入文件行 | preview 总/有效/无效 | preview 错误行 | execute 插入/更新/跳过 | 库内落库对象 | 对账 |
| --- | --- | --- | --- | --- | --- | --- |
| customers | 1 | 1 / 1 / 0 | 0 | 1 / 0 / 0 | customer 1（CUS-102） | 一致 |
| materials | 8 | 8 / 8 / 0 | 0 | 8 / 0 / 0 | material 8 | 一致 |
| resources | 5 | 5 / 5 / 0 | 0 | 5 / 0 / 0 | resource 5 | 一致 |
| calendars | 1 | 1 / 1 / 0 | 0 | 1 / 0 / 0 | calendar 1（中科储能三班日历） | 一致 |
| operationTemplates | 5 | 5 / 5 / 0 | 0 | 5 / 0 / 0 | operation_template 5 | 一致 |
| boms | 12 | 12 / 12 / 0 | 0 | 3 / 0 / 0 | bom 3 头 + bom_item 12 行 | 一致（文件行=子件行 12） |
| routings | 10 | 10 / 10 / 0 | 0 | 3 / 0 / 0 | routing 3 头 + routing_node 10 节点 | 一致（文件行=节点行 10） |
| orders | 5 | 5 / 5 / 0 | 0 | 5 / 0 / 0 | orders 5 | 一致 |
| strategies | 1 | 1 / 1 / 0 | 0 | 1 / 0 / 0 | strategy 1（中科储能-交期优先策略） | 一致 |

imports 原始记录：`target/m3qa04/imports-B.log`。补链动作与客户 A 相同（5 资源绑日历、3 routing 补图并激活、BOM 激活）。

两家合计库内落库（最终对账，SQL 采集）：

| 对象 | 客户 A | 客户 B |
| --- | --- | --- |
| customer | 1 | 1 |
| material | 8 | 8 |
| resource | 5 | 5 |
| calendar（客户级） | 1 | 1 |
| operation_template | 5 | 5 |
| bom 头 / bom_item | 3 / 8 | 3 / 12 |
| routing 头 / routing_node | 3 / 11 | 3 / 10 |
| orders | 5 | 5 |
| strategy | 1 | 1 |

BOM 与 routing 均为 `ACTIVE`：

```
FG-SMT-A01 V1.A ACTIVE 3 items | FG-SMT-A02 V1.A ACTIVE 2 | SF-SMT-A01 V1.A ACTIVE 3
FG-ESS-B01 V1.0 ACTIVE 5 items | FG-ESS-B02 V1.0 ACTIVE 4 | SF-ESS-B01 V1.0 ACTIVE 3
routing 节点：FG-SMT-A01 5 | FG-SMT-A02 3 | SF-SMT-A01 3 | FG-ESS-B01 5 | FG-ESS-B02 4 | SF-ESS-B01 1
```

---

## 5. 交流实测：客户 A（苏州精工，订单 `SO-SMT-2026-001` + 补充 `SO-SMT-2026-002`）

### 5.1 链路步骤

| # | 步骤 | 接口 | HTTP | traceId | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 确认主订单 | `PUT /api/v1/orders/SO-SMT-2026-001/confirm` | 200 | `100d2fb726344a7a8d7b192595d2b745` | status=CONFIRMED |
| 2 | 确认补充订单 | `PUT /api/v1/orders/SO-SMT-2026-002/confirm` | 200 | `5e545a53ac6947138ab9d1f140cf3723` | status=CONFIRMED |
| 3 | 冻结 | `PUT /api/v1/orders/SO-SMT-2026-001/freeze` | 200 | `dc223e3ac8d04befb0df732f507b9a68` | freezeStatus=FROZEN |
| 4 | 释放 | `PUT /api/v1/orders/SO-SMT-2026-001/release` | 200 | `0a7bedeca01e44f5ad39b453d34e73b5` | status=RELEASED |
| 5 | 开工 | `PUT /api/v1/orders/SO-SMT-2026-001/start` | 200 | `22ed579a38764e1ab409b936cd64edeb` | status=IN_PROGRESS |
| 6 | 提交全量求解 | `POST /api/v1/solve-tasks` | 200 | `f5a2c432cb78457c9348e88e75963e0c` | solveTaskId=3 |
| 7 | 查询求解 | `GET /api/v1/solve-tasks/3` | 200 | — | SOLVED，tasks=10 |
| 8 | 增量重排（首次） | `POST /api/v1/solve-tasks/incremental` | **422** | `deaf1bcc03d2433c858b89e536ab404c` | 42200 MRP 齐套未通过（SHORTAGE） |
| 9 | 库存造数 | `PUT /api/v1/inventory` | 200 | — | 16 物料 × 10000 |
| 10 | 增量重排（重试） | `POST /api/v1/solve-tasks/incremental` | 200 | `0f418250b2e04c1799762eaa88a655bc` | planId=`f164dfa0…`，solveTaskId=4，affectedOrderCount=1 |
| 11 | 查询增量求解 | `GET /api/v1/solve-tasks/4` | 200 | — | SOLVED，tasks=10 |
| 12 | 发布计划 | `POST /api/v1/plans/f164dfa0-2670-40f9-8066-d47bcdf2cdd3/publish` | 200 | `d8c3aeb5e579477e8524ce11a64232fc` | PUBLISHED，version=2，taskCount=10 |

### 5.2 落库原始行

`solve_task`（SQL 原样）：

```
id | status | engine  | score_hard | score_soft | metrics(taskCount/assigned/unassigned/persisted/durationMs)
 3 | SOLVED | TIMEFOLD | 0 | 0 | 10 / 10 / 0 / 10 / 10059   ← 全量求解
 4 | SOLVED | TIMEFOLD | 0 | 0 | 10 / 10 / 0 /  5 / 10048   ← 增量重排（仅落受影响订单任务）
```

`plan_version`：

```
id                                   | plan_no           | version_no | status    | task_count | sandbox_id
f164dfa0-2670-40f9-8066-d47bcdf2cdd3 | PLAN-20261104-002 | 2          | PUBLISHED | 10         | …0401
```

`task`（A 最终状态，节选；A 全部 10 条，SO-SMT-001 为 FROZEN、SO-SMT-002 为 PLANNED）：

```
task_id | order_no         | op_index | operation_code | resource_code | status  | start_time                    | end_time
23      | SO-SMT-2026-001  | 10       | OP-SMT-A01     | RES-SMT-A01   | FROZEN  | 2026-11-06T17:00:30+08:00     | 2026-11-07T05:00:30+08:00
21      | SO-SMT-2026-001  | 20       | OP-REF-A01     | RES-REF-A01   | FROZEN  | 2026-11-07T16:00:20+08:00     | 2026-11-08T04:00:20+08:00
17      | SO-SMT-2026-001  | 30       | OP-AOI-A01     | RES-AOI-A01   | FROZEN  | 2026-11-11T00:00:10+08:00     | 2026-11-11T04:48:10+08:00
25      | SO-SMT-2026-001  | 40       | OP-TEST-A01    | RES-TEST-A01  | FROZEN  | 2026-11-11T07:00:05+08:00     | 2026-11-11T07:50:05+08:00
19      | SO-SMT-2026-001  | 50       | OP-PACK-A01    | RES-QC-A01    | FROZEN  | 2026-11-11T23:00:00+08:00     | 2026-11-11T23:20:00+08:00
29      | SO-SMT-2026-002  | 10       | OP-SMT-A01     | RES-SMT-A01   | PLANNED | 2026-11-04T23:00:30+08:00     | 2026-11-05T07:00:30+08:00
…（其余 4 条见 `target/m3qa04/chain-A.json` 任务明细）
```

`orders`：

```
order_no        | customer | status      | freeze_status | priority | due_date   | qty
SO-SMT-2026-001 | CUS-101  | IN_PROGRESS | FROZEN        | URGENT   | 2026-11-06 | 120
SO-SMT-2026-002 | CUS-101  | CONFIRMED   | UNFROZEN      | HIGH     | 2026-11-12 |  80
```

> 注：上表 `task` 的 `plan_version_id` 在 A 发布当时为 `f164dfa0…`；**B 发布后被全量改写为 B 的计划版本**，见 P1。

---

## 6. 链路实测：客户 B（安徽中科储能，订单 `SO-ESS-2026-001` + 补充 `SO-ESS-2026-002`）

### 6.1 链路步骤

| # | 步骤 | 接口 | HTTP | traceId | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 确认主订单 | `PUT /api/v1/orders/SO-ESS-2026-001/confirm` | 200 | `bf44778da65f4db693804dca79e24036` | status=CONFIRMED |
| 2 | 确认补充订单 | `PUT /api/v1/orders/SO-ESS-2026-002/confirm` | 200 | `a42f507f94984b5590c7e78dda7c176f` | status=CONFIRMED |
| 3 | 冻结 | `PUT /api/v1/orders/SO-ESS-2026-001/freeze` | 200 | `d12ec133a2ce4d9099e1c3d9501c2b03` | freezeStatus=FROZEN |
| 4 | 释放 | `PUT /api/v1/orders/SO-ESS-2026-001/release` | 200 | `1de9b3706fe14ca899b767e2e8a4daaa` | status=RELEASED |
| 5 | 开工 | `PUT /api/v1/orders/SO-ESS-2026-001/start` | 200 | `40013d56a2ae453c97d8346698c421b0` | status=IN_PROGRESS |
| 6 | 提交全量求解 | `POST /api/v1/solve-tasks` | 200 | `6620d4dc8e4c47e888aa6f49ec714a49` | solveTaskId=5 |
| 7 | 查询求解 | `GET /api/v1/solve-tasks/5` | 200 | — | SOLVED，metrics taskCount=9 |
| 8 | 增量重排 | `POST /api/v1/solve-tasks/incremental` | 200 | `8922f2c601864f7b84f43dde29517b12` | planId=`fc2b8872…`，solveTaskId=6，affectedOrderCount=1 |
| 9 | 查询增量求解 | `GET /api/v1/solve-tasks/6` | 200 | — | SOLVED，tasks=19 |
| 10 | 发布计划 | `POST /api/v1/plans/fc2b8872-60f1-4c33-94ae-7ee3550be108/publish` | 200 | `55d5609d2abe4d0b85a350550fafd22e` | PUBLISHED，version=3，taskCount=19 |

### 6.2 落库原始行

`solve_task`：

```
id | status | engine  | score_hard | score_soft | metrics(taskCount/assigned/unassigned/persisted/durationMs)
 5 | SOLVED | TIMEFOLD | 0 | 0 |  9 /  9 / 0 /  9 / 10067   ← 全量（仅 B 订单范围）
 6 | SOLVED | TIMEFOLD | 0 | 0 | 19 / 19 / 0 /  4 / 10075   ← 增量（沙箱全量 19，落库仅受影响 4）
```

`plan_version`：

```
id                                   | plan_no           | version_no | status    | task_count | sandbox_id
fc2b8872-60f1-4c33-94ae-7ee3550be108 | PLAN-20261104-003 | 3          | PUBLISHED | 19         | …0401
```

`task`（B 订单，5 + 4 条，全部 PLANNED，`plan_version_id=fc2b8872…`）：

```
task_id | order_no         | op_index | operation_code | resource_code | status  | start_time                 | end_time
35      | SO-ESS-2026-001  | 10       | OP-MOD-B01     | RES-MOD-B01   | PLANNED | 2026-11-04T23:00:15+08:00  | 2026-11-05T00:30:15+08:00
38      | SO-ESS-2026-001  | 20       | OP-STACK-B01   | RES-STACK-B01 | PLANNED | 2026-11-05T16:00:30+08:00  | 2026-11-05T17:30:30+08:00
32      | SO-ESS-2026-001  | 30       | OP-AGE-B01     | RES-AGE-B01   | PLANNED | 2026-11-07T13:00:30+08:00  | 2026-11-07T15:00:30+08:00
34      | SO-ESS-2026-001  | 40       | OP-EOL-B01     | RES-EOL-B01   | PLANNED | 2026-11-08T05:00:10+08:00  | 2026-11-08T05:30:10+08:00
36      | SO-ESS-2026-001  | 50       | OP-PACK-B02    | RES-QC-B01    | PLANNED | 2026-11-08T06:00:05+08:00  | 2026-11-08T06:18:05+08:00
43      | SO-ESS-2026-002  | 10       | OP-STACK-B01   | RES-STACK-B01 | PLANNED | 2026-11-05T21:00:30+08:00  | 2026-11-05T21:30:30+08:00
…（其余 3 条见 chain-B.json）
```

`orders`：

```
order_no        | customer | status      | freeze_status | priority | due_date   | qty
SO-ESS-2026-001 | CUS-102  | IN_PROGRESS | FROZEN        | URGENT   | 2026-11-08 | 6
SO-ESS-2026-002 | CUS-102  | CONFIRMED   | UNFROZEN      | HIGH     | 2026-11-14 | 2
```

---

## 7. 导出校验（orders / materials）

| 导出 | 接口 | HTTP | traceId | 文件行数（不含表头） | 库内行数 | 对账 |
| --- | --- | --- | --- | --- | --- | --- |
| orders | `GET /api/v1/orders/export` | 200 | `c0c2bd926a8345f9b24702118f43a429` | 10 | 10（CUS-101=5，CUS-102=5） | 一致 |
| materials | `GET /api/v1/materials/export` | 200 | `6edd3ba8b2c74212839e1a6cd1a41c9d` | 16 | 16（A=8，B=8） | 一致 |

导出文件：`target/m3qa04/export/orders.xlsx`（4319 B，sheet「数据」，列与导入模板一致）、`target/m3qa04/export/materials.xlsx`（4810 B）。

---

## 8. 问题清单（按严重度）

### P1（高）计划发布按 sandbox 全量回填 `task.plan_version_id`，跨客户污染

B 客户发布 `PLAN-20261104-003`（`task_count=19`）后，A 客户的 10 条任务 `plan_version_id` 也被改写为 B 的计划版本：

```
code    | plan_version_id                      | task count
CUS-101 | fc2b8872-60f1-4c33-94ae-7ee3550be108 | 10   ← A 未被本次发布触及，却被改写
CUS-102 | fc2b8872-60f1-4c33-94ae-7ee3550be108 |  9
```

同一库内 `plan_version` 依次累积为 001(10) / 002(10) / 003(19)，003 覆盖两家客户，无法按客户/订单/车间区分发布范围。多租户单沙箱多客户场景下，任一家重排发布都会把其他客户任务挂到自己的计划版本上，影响计划追溯与回滚。

### P2（高）routings 导入模板缺工序模版、标准工时、换型工时列，导入后路线不可直接求解

导入模板仅含 `productCode / routingVersion / effectiveDate / expiryDate / seqNo / fromSeqNo / operationCode / primaryResourceCode`。入库后 `routing_node.operation_template_id`、`standard_seconds` 为空，求解返回 422「缺少标准工时」。本报告通过 `PUT /routings/{id}/graph` 逐条补绑模板与工时后才可求解（6 条路线全部人工补链），真实客户批量导入时无法承受。

### P3（中）boms / routings 导入默认 DRAFT，不自动激活

导入 execute 成功后 BOM、routing 状态为 DRAFT，订单 `confirm` 因缺 ACTIVE BOM/routing 失败（首轮 A 链路已复现：确认未生效，订单仍 DRAFT）。需额外 `POST /boms/{id}/activate`、`POST /routings/{id}/activate`。模板无状态列，也无法在导入时直接指定 ACTIVE。

### P4（中）resources 导入无日历绑定列

`resources` 模板无 `calendarId`/`calendarCode` 列，导入后资源不绑日历；而 `calendars` 导入的 FACTORY 日历是租户级，必须通过资源绑定才按客户作息生效。本次用 `PUT /resources/{code}` 逐条补绑（10 条）。否则 A 的两班制会退化为默认 7×24，排产结果与客户实际作息不符。

### P5（中）orders 导入无客户列，订单无法归属种子客户

`orders` 模板无 `customerId`/`customerCode`，导入的 10 条订单 `customer_id` 为空，客户维度查询与按客户统计失效。本次用一条 SQL `UPDATE orders SET customer_id=(...)` 回填（A 5 条、B 5 条），是本次唯一直接写主数据表的地方。

### P6（中）初始全量求解不做 MRP 齐套校验，增量重排做；库存无 Excel 导入模板

全量求解 `solve_task=3` 在零库存下直接 SOLVED；随后增量重排返回 **422**（`deaf1bcc03d2433c858b89e536ab404c`，MRP 齐套未通过 SHORTAGE）。为通过增量，需先用 `PUT /inventory` 造 16 条库存（10000/物料）。库存当前只有 API，无 Excel 模板，种子客户数据无法一次性导入。两家客户的齐套校验口径需要客户确认真实库存/在途后重跑。

### P7（低）boms / routings 的 execute `inserted` 计数是「头数」而非「文件行数」

`boms` A 文件 8 行 → inserted=3；`routings` A 文件 11 行 → inserted=3。对账必须追加 `bom_item`（8/12）与 `routing_node`（11/10）子表计数，否则会被误判为丢行。接口未在响应中区分「头 / 明细」。

### P8（低）求解详情返回沙箱全量任务，与请求范围不一致

B 全量求解 `solve_task=5` 的 metrics 为 `taskCount=9`（仅 B 两条订单），但 `GET /solve-tasks/5` 的 `tasks` 数组返回 19 条（含 A 客户 10 条）；`solve_task=6` 同理（metrics 19 / detail 19）。调用方无法仅凭详情区分「本次求解范围」与「沙箱快照范围」，与 P1 同源。

### P9（信息）多线程与换型矩阵未生效

请求 `moveThreadCount=1`，提交成功且 `moveThreadCountApplied=true`，但 `moveThreadCountEffective=NONE`、`multiThreadedAvailable=false`；`setupMatrixFallback=true`。求解仍 SOLVED 且 hard=0，说明当前数据规模下单线程足够，但换型时间未参与优化，结果质量依赖后续真实换型矩阵。

### P10（信息/环境）任务书给定的 jar 不可执行

`aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar` 为 30 KB thin jar，MANIFEST 无 `Main-Class`/`Class-Path`，`java -jar` 报「no main manifest attribute」。本次改用同目录 `aps-admin-0.1.0-SNAPSHOT-repackaged.jar`（181 MB），未重新构建。**注意**：基线实例（18085，PID 29264）是在 jar 被替换前启动的旧 fat jar 进程，若重启将用新 thin jar 无法启动，需先修正制品产出方式。

---

## 9. 清理与净变化

| 项 | 结果 |
| --- | --- |
| 停止实例 | PID `24668`（18097）已停止；`Get-NetTCPConnection -LocalPort 18097` 无监听 |
| 数据库 | `aps_seed_customers` 已 DROP（先 `pg_terminate_backend`） |
| 库列表净变化 | DROP 前 11 个（含 `aps_seed_customers`）→ DROP 后 10 个，无残留 |
| 基线隔离 | 18085（PID `29264`）全程未停止/未重启；`aps_local`、`aps_local_2` 未连接、未改动 |
| 迁移 | 未修改既有 V1–V7 / V11 / V12，未新增迁移 |
| 版本控制 | 未 commit / 未 push |
| 临时产物 | 全部在 `target/m3qa04/`（导入文件、模板、日志、导出、证据 JSON），不入库不入提交 |

---

## 10. 需要客户确认的问题

以下口径必须由客户/用户判断，本次不替客户设定：

1. **交期与优先级的权衡**：当前策略为 FORWARD（交期优先）。客户 A 的 `SO-SMT-2026-005`（HIGH，交期 11-09，40 件）与 `SO-SMT-2026-001`（URGENT，120 件）在贴片机资源上冲突时，是否应让 URGENT 绝对优先、还是按「优先级 × 剩余交期」加权？请客户给出优先级档位与交期违约的权重。
2. **工艺路线与工序顺序是否真实**：A 的 SMT→回流焊→AOI→功能测试→包装 是否与实际产线一致；B 的 `SF-ESS-B01` 半成品只有 1 道装配工序（是否漏工序）。另 B 的 `OP-AGE-B01` 老化 7200 秒、`OP-STACK-B01` 电池簇装配 900 秒是否客户认可的单件工时。
3. **班次与工作日历**：A 周一至周六两班（08:00–16:00 / 16:00–24:00，周日停产）、B 7×24 三班，是否符合客户实际作息；节假日未配置（holidays 为空），客户是否需要按真实节假日/检修日排产。
4. **换型与收尾工时**：当前 `routing_node.setupSeconds` 与资源 `setupSeconds` 均为示意值，且 `setupMatrixFallback=true`（无换型矩阵）。客户是否需要提供真实换型矩阵（按产品/工序对），以及换型是否应占用资源主产能。
5. **MRP 齐套口径**：本次库存为造数（16 物料各 10000），且全量求解不校验齐套、增量校验。客户需确认：齐套是否只按现有库存，还是含在途采购/在制；是否允许缺料订单先排产再挂起。
6. **计划发布范围**：当前发布按沙箱全量（P1），一次发布含两家客户 19 条任务。客户是否需要按「客户 / 车间 / 订单」维度发布与回滚？这直接决定 P1 的修复优先级。
7. **订单冻结与重排边界**：已开工订单（`FROZEN`）任务是否允许被增量重排移动；客户对「已开工订单只锁时间窗、不锁资源」还是「资源与时间双锁」的预期。

> 业务验收反馈（如「这张订单排到 11-06 17:00 是否符合车间实际」「该客户优先级档位是否够用」）必须由客户/用户填写，本报告不代填。
