# M3 验收前整改批次（后端）· Round 3 交付

**5 项 / 已修 5 / 受阻 0 / 全量 Skipped=0**

- 执行范围：`d:\tools\smart-aps-agent`（单车道串行，未并行构建）
- 契约：`docs/api/contract-freeze.md`、`docs/api/openapi/*.yaml` 未改动
- 迁移：V1–V7、V11、V12 未改动，未新增迁移
- 依赖：`pom.xml` 未改动；未 commit / push，未 `mvn package`
- 自建库：PostgreSQL 15，`127.0.0.1:18099`，库名 `aps_fix_round3`（结束后已 DROP）

---

## ① P1 计划发布跨客户回填

**根因**：发布时 `backfillTasks` 按 `(tenant, sandbox)` 全场景回填，而增量求解的发布范围（本次订单集合）从未被记录，导致同场景内 B 客户发布把 A 客户任务的 `plan_version_id` 改写成 B 的版本，`task_count` 变成全场景 19。

**改动文件**
- `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/schedule/IncrementalSolveRepository.java`：DRAFT 快照写入 `{"scopeOrderNos":[...]}`（本次求解订单范围）
- `aps-service/src/main/java/com/hengce/aps/service/schedule/IncrementalSolveService.java:170`：创建 DRAFT 时传入 `orders`
- `aps-service/src/main/java/com/hengce/aps/service/plan/PlanVersionService.java`：发布前解析 `scopeOrderNos`，按范围取快照/回填
- `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/plan/PlanVersionRepository.java`：`listTasksForSnapshot` / `backfillTasks` 新增订单范围过滤；无范围标记时仍按场景全量（仅 WHAT_IF→BASELINE 的 apply 路径使用）

**修复前失败证据**（新增真库用例 `PlanPublishScopeRealDbIntegrationTest`，场景与现场一致：同一租户 BASELINE 场景、A/B 两客户订单、任务 10+9=19 条）

```
[EVIDENCE] P1 before/after: A tasks -> planA=10, A tasks -> planB=10, B tasks -> planB=0, planB.taskCount=19
AssertionError: 发布 B 后 A 客户任务被回填到 B 的 plan_version_id（跨客户回填）
expected: 0L but was: 10L
```

**修复后通过证据**（同一用例；`planA` 与 `planB` 为实际落库主键）

```
[EVIDENCE] P1 before/after: planA=ce1e5959-cd3b-48c9-9ffd-1590da8fe339,
            planB=d629afe2-ed9e-45e9-95cc-c0e56deb1703,
            A tasks -> planA=10, A tasks -> planB=0,
            B tasks -> planB=9, planB.taskCount=9
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

**落库前后 `plan_version_id` 对照**

| 客户 A 的 10 条任务 | 发布 B 前 | 修复前发布 B 后 | 修复后发布 B 后 |
|---|---|---|---|
| 指向 planA | 10 | 0 | 10 |
| 指向 planB | 0 | 10 | 0 |
| planB.task_count | 9（应为 9） | 19 | 9 |

---

## ② 日历时区错位

**根因**：`ScheduleProblemAssembler#expand` 把日历班次的本地时间直接 `atOffset(ZoneOffset.UTC)` 解释，东八区 08:00 早班因此变成 UTC 08:00，落到本地 16:00。

**改动文件**
- `aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleProblemAssembler.java`：新增 `businessZone`（`@Value("${aps.business.time-zone:Asia/Shanghai}")`），班次起点/终点改为 `LocalDateTime.atZone(businessZone).toOffsetDateTime()`；日期游标、生效日、默认日历“今天”都按业务时区计算
- `aps-service/src/test/java/com/hengce/aps/service/schedule/ScheduleProblemAssemblerCalendarTest.java`：构造器传入 `Asia/Shanghai`，新增 08:00 用例，另将“UTC 偏移”用例改为按瞬时比较（不依赖表示偏移）

**修复前失败证据**（`08:00–12:00` 班次，`2026-11-04T00:00+08:00` ~ `2026-11-05T00:00+08:00`）

```
expected: 2026-11-04T08:00+08:00
 but was: 2026-11-04T16:00+08:00
```

**修复后通过证据**

```
expandShiftShouldLandOnBusinessLocalTime: PASS
task start = 2026-11-04T08:00+08:00（本地 08:00）
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

---

## ③ `/mrp/suggestions` 性能

**根因**：每次请求都全量执行 `mrpAnalysisService.analyze()`，只用结果做内存过滤/分页，没有任何结果缓存。

**改动文件**
- 新增 `aps-service/src/main/java/com/hengce/aps/service/mrp/MrpSuggestionCache.java`：按 `(tenantId, sandboxId, horizonDays, orderNos)` 缓存，TTL 默认 45 秒（`aps.mrp.suggestion-cache-ttl-seconds`，落在 30–60s）
- `aps-service/src/main/java/com/hengce/aps/service/mrp/MrpSuggestionService.java`：先查缓存，未命中再分析并回填缓存
- 主动失效（不等 TTL）：
  - `aps-service/src/main/java/com/hengce/aps/service/masterdata/order/OrderService.java`：创建 / 更新 / 确认订单后 `invalidate(tenantId)`
  - `aps-service/src/main/java/com/hengce/aps/service/mrp/InventoryService.java`：`upsert` 成功后 `invalidate(tenantId)`
  - `aps-service/src/main/java/com/hengce/aps/service/masterdata/bom/BomService.java`：创建 / 激活 / 删除 BOM 后 `invalidate(tenantId)`

**修复前/后耗时对照**（真库、单线程、同一入参；修复前为 QA 实测基线）

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 单次（空结果） | 328ms | 冷调用 189–258ms；缓存命中 0ms |
| 简单并发（100 并发 P50） | 6.5s | 命中缓存后不再触发 analyze（工程内单线程实测 `cachedAvg=0ms`） |

**缓存失效正确性用例**（`MrpSuggestionRealDbIntegrationTest`）

```
[EVIDENCE] 缓存失效(写库存): before.total=3, after.total=2
[EVIDENCE] 采购建议耗时(单线程同入参): coldAvg=189ms, cachedAvg=0ms
```

写库存后同一入参立即由 3 条缺料降为 2 条，且缺料物料消失，证明失效不依赖 TTL。

---

## ④ routings 导入模板缺字段

**根因**：routings 导入模板没有工序模版与标准工时列，解析器也不解析，落库时 `routing_node.operation_template_id` / `standard_seconds` 只能后续 `PUT /routings/{id}/graph` 补。

**改动文件**
- `aps-service/src/main/java/com/hengce/aps/service/importexport/ImportEntity.java`：ROUTINGS 模板新增 `operationTemplateCode`（工序模版编码）、`standardSeconds`（标准工时(秒)）
- `aps-service/src/main/java/com/hengce/aps/service/importexport/handler/RoutingImportHandler.java`：
  - 解析两列并写入 normalize / `RoutingNodeCommand`
  - `operation_template_id` 按编码查 `OperationTemplateService`，找不到抛 `ImportRowException(field=operationTemplateCode, code=42200)`
  - `standardSeconds` 校验非负，非法同样走 42200

**修复后通过证据**（真库导入一份带工序模板与工时的文件）

```
[EVIDENCE] routings 导入落库原始行:
  {operation_template_id=34c13515-a171-4731-9569-0546dfd92962, standard_seconds=60, operation_code=OP-10}
未知工序模版: field=operationTemplateCode, code=42200, message=工序模版不存在: QA1-...-OP-MISSING-
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

模板列已包含「工序模版编码」「标准工时(秒)」，导入后两列直接非空，无需再补 graph。

---

## ⑤ 齐套校验口径不一致

### 两侧实际行为（修复前）

| 入口 | 行为 | 代码位置 |
|---|---|---|
| 增量求解 `POST /solve-tasks/incremental` | 零库存 → 同步返回 `42200` + 缺料明细 | `IncrementalSolveService.java:151-153` |
| 全量求解 `POST /solve-tasks` | 受理阶段不做任何 MRP 齐套校验，直接创建 `solve_task` | 原 `SolveTaskLifecycleService.submit` |

### 契约判定

- C21（§24.3）只定义增量求解的订单范围与版本语义，**没有**说“增量才校验齐套”。
- C25（§24.7，`contract-freeze.md:1732`）明确：`blocking`（boolean，默认 `true`）——`true` 时齐套未通过**阻止排产提交**（`42200` + 缺料明细）。
- 因此“增量侧做校验”符合契约，“全量侧缺失校验”不符合；方向为：**全量补齐**，不是去掉增量校验。

### 结论（已对齐，非受阻）

**改动文件**
- `aps-service/src/main/java/com/hengce/aps/service/schedule/SolveTaskLifecycleService.java:231-248`：新增带开关的重载 `submit(request, key, checkMrpBlocking)`；默认入口在受理阶段调用 `MrpBlockingChecker.evaluate(sandboxId, orderNos, true)`，`blocked=true` 时抛 `UNPROCESSABLE_ENTITY(42200)`，且不创建 `solve_task`
- `aps-service/src/main/java/com/hengce/aps/service/schedule/IncrementalSolveService.java:186`：增量入口调用 `submit(..., false)`，避免重复分析
- `aps-service/src/test/java/com/hengce/aps/service/it/CoreFlowE2ETest.java`：全链路用例按契约先备足子件库存（否则现在会被 `42200` 正确拦截）
- 新增用例 `SolveTaskLifecycleServiceTest#submitShouldBlockFullSolveWhenMrpShortage`

**验证证据**
```
com.hengce.aps.common.exception.BizException: MRP 齐套未通过（SHORTAGE），缺料物料: QA1-...-RM-1
  at SolveTaskLifecycleService.submit(SolveTaskLifecycleService.java:247)
```
全量入口在缺料时被拦截；备料后 `CoreFlowE2ETest` 通过（`solve status=SOLVED, plan status=PUBLISHED, taskCount=2`）。

---

## 全量测试结果

命令：`APS_TEST_DB_ENABLED=true mvn -B -ntp test`（`ASP_TEST_DB_PORT=18099`、库 `aps_fix_round3`）

| 模块 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| aps-common | 19 | 0 | 0 | 0 |
| aps-infrastructure | 34 | 0 | 0 | 0 |
| solver-api | 8 | 0 | 0 | 0 |
| solver-dsl | 19 | 0 | 0 | 0 |
| solver-timefold | 97 | 0 | 0 | 0 |
| aps-service | 328 | 0 | 0 | 0 |
| aps-web | 53 | 0 | 0 | 0 |
| **合计** | **558** | **0** | **0** | **0** |

`BUILD SUCCESS`，全量 `Skipped=0`。

**环境清理**
- 未启动应用实例；测试库容器端口 `127.0.0.1:18099`（未使用 18085 / `aps_local` / `aps_local_2`）
- `DROP DATABASE aps_fix_round3 WITH (FORCE)`：删除 35 张表、1 条 tenant 种子记录，库内已无 `aps%` 数据库
- 容器 `aps-fix-r3-pg` 已停止并移除，`18099` 端口已释放（`Test-NetConnection=False`）

---

## 这批改动还可能影响哪些既有用例

1. **所有全量求解用例**（`CoreFlowE2ETest`、`SolveTaskRetryPartialRealDbIntegrationTest`、任何 `POST /solve-tasks` 的后端/前端联调用例）：默认 `blocking=true` 时，缺料会从“能提交”变为 `42200`。已按契约在 E2E 中补齐库存；外部调用方若依赖旧行为，需先备料或显式配置 `aps.mrp.blocking-enabled=false`。
2. **日历/排产时间断言**（`ScheduleProblemAssemblerCalendarTest`、真库排产/重叠/甘特用例）：班次窗口整体从 UTC 改为业务时区（默认 `Asia/Shanghai`），所有本地时间断言应落在 08:00 而非 16:00；跨日窗口的日期游标也随之改变。
3. **计划发布/回滚**（`PlanVersionService`、`SandboxLifecycleRealDbIntegrationTest`、`PlanCompareRealDbIntegrationTest`）：`task_count` 现在按求解订单范围统计；同一 sandbox 多客户发布不再互相覆盖。历史遗留的、无 `scopeOrderNos` 的 DRAFT 仍按场景全量发布（仅 apply 路径）。
4. **MRP 建议**（`MrpSuggestionServiceTest`、`MrpControllerTest`、`MrpSuggestionRealDbIntegrationTest`）：同一入参 45 秒内返回缓存；依赖“每次调用都重新分析”的用例需要先 `MrpSuggestionCache#clear()` 或触发写订单/库存/BOM 失效。
5. **routings 导入**（`ImportService`、`RoutingImportHandler`、导入模板快照用例）：模板列数由 8 增至 10，按列位断言的用例需要同步；`operationTemplateCode` 不存在时由“静默空值”变为 `42200` 错误行。
6. **服务构造签名**：`OrderService`、`InventoryService`、`BomService`、`MrpSuggestionService`、`SolveTaskLifecycleService`、`RoutingImportHandler` 增加依赖；直接 `new` 的单元测试已同步更新，Spring 上下文不受影响。
