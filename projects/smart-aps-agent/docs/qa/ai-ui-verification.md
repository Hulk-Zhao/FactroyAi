# AI 能力接入前端 · 验收记录（2026-11-05）

批次：D（AI 能力接出界面）。前端仅改 `aps-frontend/src`，未改 `docs/api/*`、后端源码、`mocks/handlers.ts`。
所有请求均为真实后端请求，截图与 traceId 来自当次响应；失败/降级一律使用定稿文案，未构造任何模拟文案或日期。

## 1. 截图清单

截图目录：`docs/qa/screenshots/ai-ui-20261105/`（仓库内相对路径，编号沿用取证时的 `35-` 序号）。

| 编号 | 文件 | 场景 | HTTP | traceId |
| --- | --- | --- | --- | --- |
| 35-ai-explain-no-data | [35-ai-explain-no-data.png](screenshots/ai-ui-20261105/35-ai-explain-no-data.png) | 边界① explain 无归因（`available=false`） | 200 | `54ff05df39ee460dbf83678925990e54` |
| 35-atp-timeout | [35-atp-timeout.png](screenshots/ai-ui-20261105/35-atp-timeout.png) | 边界② ATP 限时求解超时（无任何日期） | 200 | `2af255df704349a3869d9af1ffcae65a` |
| 35-whatif-baseline-422 | [35-whatif-baseline-422.png](screenshots/ai-ui-20261105/35-whatif-baseline-422.png) | 边界③ what-if 打 BASELINE 被拒 | 422 / 42200 | `44f283bc55af471bb478598c7db36270` |
| 35-ai-explain-llm-disabled | [35-ai-explain-llm-disabled.png](screenshots/ai-ui-20261105/35-ai-explain-llm-disabled.png) | LLM 未配置正式路径 | 200 | `68dae7649886425fbcb21bc0b96f4f15` |
| 35-atp-fast | [35-atp-fast.png](screenshots/ai-ui-20261105/35-atp-fast.png) | ATP FAST 正常路径 | 200 | `0009957a8c6b4005a986191eba49ac0d` |

## 2. 三个边界用例

### 2.1 边界① 订单详情「AI 解读」无归因

- 前置：任务没有归因记录。`aps_local` 中可见任务均被归因覆盖，故使用 `aps_local` 的临时副本 `aps_ui_verify`，删除 `solve_task_id in (56,41)` 的归因行后取证；取证结束已 `DROP DATABASE aps_ui_verify`。
- 链路：`GET /api/v1/gantt/order`（按 `orderNos` 解析 `tasks[0].id`）→ `GET /api/v1/agent/explain/{taskId}`。
- 响应：HTTP 200，`available=false`，`noDataReason=NO_JUSTIFICATION_RECORD`，`narrative=该求解任务没有归因记录。归因由求解结果写入，早期求解可能没有记录。`
- UI：信息色「暂无归因记录」，不显示为错误，不显示任何编造的摘要/建议。

### 2.2 边界② ATP 限时求解超时

- 环境：`aps_seed_verify`（路由完整，ACCURATE 可进入求解；`aps_local` 的 ACCURATE 恒 422，见第 4 节）。
- 请求：`POST /api/v1/orders/atp`，`mode=ACCURATE`、`timeoutSeconds=1`。
- 响应：HTTP 200，`status=TIMEOUT`、`accuracy=UNVERIFIED`，响应内**没有任何日期字段**。
- UI：黄色告警「系统未给出可信交期（限时求解超时）」+「改用 FAST 估算」按钮；未显示红色错误，未显示任何日期。
- ACCURATE 正常路径（同一库）实测约 10.8 秒，故界面在求解期间展示「实测约 10 秒，最长 30 秒」的等待提示。

### 2.3 边界③ 沙箱「问一问」打 BASELINE

- 请求：`POST /api/v1/agent/whatif`，`sandboxId` 指向主计划（BASELINE）场景。
- 响应：HTTP **422**，`code=42200`，服务端原文 `what-if 仅支持 WHAT_IF 场景，当前场景类型为 BASELINE，拒绝修改主计划`。
- UI：错误正文直接取服务端 `message`（不另行改写标题），并展示 `HTTP 422 · code 42200 · traceId 44f283bc55af471bb478598c7db36270`。

## 3. ACCURATE 在 `aps_local` 恒 422 的数据证据

结论：这不是前端或求解器的问题，而是一条**被 CONFIRMED 订单引用的 ACTIVE 工艺路线**存在无主资源的工序节点。

服务端原文（两次不同物料请求，同一原因）：

```
HTTP=422
{"code":42200,"message":"工序 n10 缺少主资源，无法排产","data":null,"traceId":"b13926d0703d4b80999b3861d071eae5"}
```

```
HTTP=422
{"code":42200,"message":"工序 n10 缺少主资源，无法排产","data":null,"traceId":"332b9a6d599040dd81cc854fe091f1af"}
```

数据事实（`aps_local`，只读查询）：

| 项 | 值 |
| --- | --- |
| 订单 | `QA2-SO-001`，`status=CONFIRMED`，`routing_id=9621ca3e-cfd3-414e-a3e9-9ca837024b5c` |
| 订单 | `QA2-SO-002`，`status=DRAFT`，同一 `routing_id` |
| 路线 | `9621ca3e-cfd3-414e-a3e9-9ca837024b5c`，`status=ACTIVE`，`product_id=a125ff0c-b73c-4a7d-bbea-c04a0357285d` |
| 缺陷节点 | `nodes[0]`：`{"id":"n10","seqNo":10,"overrides":{},"templateId":""}` —— 无 `templateId`，`overrides` 也未给 `primaryResourceId`，因此无法解析主资源 |
| 对照 | 被请求订单自己的路线 `d0000000-0000-0000-0000-000000000501`（`DEMO-FG-100`）中 `n10` 有 `templateId=d0000000-…-401`（`OP-DEMO-CNC`，`primary_resource_id=d0000000-…-301`），路线本身完整 |

ATP 的问题装配包含当前可排订单集合，因此即便请求的是 `DEMO-ORD-001`，也会因 `QA2-SO-001` 的上述路线节点而 422。要修复应落在数据/种子（补齐该节点的 `templateId` 或 `primaryResourceId`），不应在前端兜底。

## 4. 本次验收涉及的库与环境

| 库 | 用途 | 变更 |
| --- | --- | --- |
| `aps_ui_verify` | 边界①（无归因） | 由 `aps_local` 克隆，取证后已 `DROP DATABASE … WITH (FORCE)` |
| `aps_seed_verify` | 边界②（ATP 超时）、ACCURATE 正常路径 | 既有自建库，仅业务请求 |
| `aps_local` | LLM 未配置、ATP FAST 正常路径 | 见下 |

`aps_local` 现状（只读取证）：

- `flyway_schema_history` 共 11 条：`V1–V7、V11、V12` 为原有 9 条；`V13__agent_framework.sql`、`V14__setup_matrix.sql` 于 `2026-11-05 04:18:37` 应用。
- `V13`/`V14` 脚本为纯 DDL（无 `INSERT`/`UPDATE`），完成后 `agent_run=0`、`agent_checkpoint=0`、`agent_prompt_version=0`、`setup_matrix_entry=0`。
- 自 `04:18:00` 以来，全部业务表（`orders`、`task`、`solve_task`、`constraint_justification`、`plan_version`、`sandbox`、`inventory` 等）的 `created_at`/`updated_at` 命中行数均为 **0**。
- 唯一新增 DML：`audit_log` 7 条 `AUTH_LOGIN`（04:32:02–04:51:46，验收登录产生）、`users(admin).updated_at` 1 行（04:51:46，登录回写）。
- 行数快照：`orders=17`、`task=87`、`solve_task=63`、`constraint_justification=37`、`plan_version=4`、`sandbox=4`、`inventory=2`。

18085 进程现状：基线 jar 进程（PID 37740，`-jar aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`）已于 04:48 停止；现监听进程为 PID 29120（`target/classes` + 运行时 classpath，`--spring.flyway.enabled=false`）。制品恢复需由制品车道处理。
