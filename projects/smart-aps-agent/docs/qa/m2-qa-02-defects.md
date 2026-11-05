# M2-QA-02 主排产全流程联调 · 缺陷清单

> 卡号：`M2-QA-02`（主排产全流程联调）
> 产出物：缺陷清单（**不含修复**）
> 采集环境：`http://localhost:18085`（PID 28496，**08:32 构建**）、PostgreSQL `aps_local` @ 127.0.0.1:55432
> 采集时间：2026-11-04 01:20–02:20（UTC+8 09:20–10:20）
> 数据前缀：本清单全部写操作只使用自造 `QA2-` 实体，落点见文末第 6 节

## 0. 重要前置说明（读清单前必读）

1. **实例构建批次落后于源码**。18085 进程启动于 **08:32:40**，而 `SolveTaskDetail` / `SolveTaskLifecycleService` / `EngineRouter` 的源码在 **10:20–10:44** 又改了三轮。因此第 2/3 节的实测响应代表**08:32 构建**；源码中已修但实例未重建的条目，在该条「修复状态」中显式标注。
2. 本清单所有响应均为**原始报文**，HTTP 码为实测值，traceId 为服务端返回的真实值。
3. 按级别：**P0 = 0 / P1 = 3 / P2 = 9，共 12 条**。级别不因修复状态变化而调整：P1 三条已在源码修复，但**实例未重建、回归未通过**，因此仍计 P1。

## 1. 汇总

| 编号 | 级别 | 模块 | 一句话现象 | 责任流 | 稳定复现 | 修复状态（源码） |
|---|---|---|---|---|---|---|
| QA2-01 | P1 | 求解查询链路 | 引擎降级失败任务的 `GET /solve-tasks/{id}` 恒 500 | 求解链路 | 是（3/3） | 已修，待重建验证 |
| QA2-02 | P1 | 求解受理链路 | 未知 `sandboxId` 返回 500（FK 未捕获） | 求解链路 | 是（2/2） | 已修，待重建验证 |
| QA2-03 | P1 | 求解装配链路 | 不存在的 `orderNos` 被受理 200 并静默剔除 | 装配层 | 是（1/1） | 已修，待重建验证 |
| QA2-04 | P2 | 求解进度 | STOMP 约 1.8 帧/秒，超过 1 次/秒 | 求解链路 | 是 | 未修 |
| QA2-05 | P2 | 求解进度 | 出现 `status=RUNNING + phase=DONE` 自相矛盾帧 | 求解链路 | 是 | 未修 |
| QA2-06 | P2 | 主数据-日历 | `GET /calendars/{id}` 缺 `timezone` | 主数据 | 是 | 未修 |
| QA2-07 | P2 | 主数据-工艺路线 | `FIXED` 缺 `primaryResourceId` 返回 40000，契约要求 42200 | 主数据 | 是 | 未修 |
| QA2-08 | P2 | 主数据-工艺路线 | `INHERIT` + `primaryResourceId` 返回 200 且静默丢弃资源 | 主数据 | 是 | 未修 |
| QA2-09 | P2 | 主数据-工艺路线 | `INHERIT` 无模板主资源仍可保存，求解期才 FAILED | 主数据 | 是 | 未修 |
| QA2-10 | P2 | 订单冻结 | `LOCKED_RESOURCE` 资源编码不存在仍返回 200 | 主数据 | 是 | 未修 |
| QA2-11 | P2 | 订单冻结 | `/release` 自动冻结任务只写 `status` 未写 `lock_type` | 主数据 | 是 | 未修 |
| QA2-12 | P2 | 主数据-工艺路线 | `POST /routings/{id}/activate` 响应 `nodes/edges` 恒为空 | 主数据 | 是 | 未修 |

## 1.1 修复复核（2026-11-04 11:05，只读源码 + 旧实例实测）

| 条目 | 源码证据 | 旧实例（08:32）实测 | 结论 |
|---|---|---|---|
| QA2-01 | `SolveTaskDetail.java#L74` 改为 `Collections.unmodifiableMap(new LinkedHashMap<>(metrics))`，注释明确"保留 null 语义"；调用点 `SolveTaskLifecycleService.java#L496` 不变 | `GET /solve-tasks/77`→`500/50000`（traceId `41757e248a584ce9a953149886c4d6bb`）；`GET /86`→`500/50000`（traceId `a5c598f5256543358897b23148f19b29`） | 源码已修；**旧制品仍 500**，回归待新构建 |
| QA2-02 | `SolveTaskLifecycleService.java#L189-L191` 受理前预检 `sandboxExists` → `NOT_FOUND`"场景不存在: "；`#L209-L213` FK 兜底同样转 `NOT_FOUND` | `POST {"sandboxId":"…00ff"}`→`500/50000`（traceId `993f48d1fa34435db7918fdc1e17a0e5`） | 源码已修（404/40400）；旧制品仍 500 |
| QA2-03 | `SolveTaskLifecycleService.java#L185` 调用 `requireExistingOrderNos` → `ScheduleProblemAssembler.java#L310-L341` 缺失即抛 `400/40000` | `POST orderNos=["QA2-NOT-EXIST-ORDER"]`→`200/0`（traceId `e721aa323b0f4105b23ae887fd348e20`） | 源码已修；旧包 200 属**旧制品观测** |

修复后判据已随契约调整（原 R-02 的 400/40000 改为 404/40400，见第 5 节）。

---

## 2. P1 缺陷（3 条，逐条五要素）

### QA2-01 引擎降级失败任务详情接口 500

- **现象**：`engineFallback=true` 的 FAILED 求解任务，`GET /api/v1/solve-tasks/{id}` 始终 `500/50000`，前端轮询永远读不到终态与失败原因。
- **复现请求**：
  - `POST /api/v1/solve-tasks`，Header `X-API-Key: dev-write-key`，Body：
    `{"engine":"ORTOOLS","sandboxId":"a2a2a2a2-0000-4000-8000-000000000001","orderNos":["QA2-SO-003"],"parameters":{}}`
    → `HTTP 200`，`data.taskId=73`、`status=PENDING`
  - 等待 3s（任务已进入 FAILED）后：`GET /api/v1/solve-tasks/73`
  - 历史稳定实例：`GET /api/v1/solve-tasks/58`（同为 ORTOOLS 降级失败）
- **实际响应**：
  - `HTTP 500`
    `{"code":50000,"message":"系统内部错误","data":null,"traceId":"0614faca4ac043ada2f7917ce4584529"}`
  - 同一任务连续 3 次 GET 均 500，另两条 traceId：`3b45e947b37f40b0a10d56855ea28e56`、`8e4ec382a32f42da95e96a4cf60ef84b`
  - 历史任务 58：`{"code":50000,"message":"系统内部错误","data":null,"traceId":"723758f8697e438285f50d18e7aef961"}`
  - DB 侧证据（`solve_task.id=58`）：
    `{"phase":"FAILED","engine":"ORTOOLS","progress":1.0,"bestScore":null,"engineFallback":true,"engineFallbackReason":"ENGINE_ERROR", ...}`
- **期望响应**：
  - `HTTP 200`，`code=0`
  - `data.status="FAILED"`，`data.errorCode="ENGINE_ERROR"`（或 `engineFallbackReason` 同值），`data.errorMessage` 非空
  - `data.metrics` 可含 `engineFallback=true`；`bestScore` 允许为 `null` 或省略该键，但**不得因此 500**
- **根因（文件#行号）**：
  - `aps-solver/solver-api/src/main/java/com/hengce/aps/solver/api/task/SolveTaskDetail.java#L68`
    `metrics = metrics == null ? Map.of() : Map.copyOf(metrics);` —— `Map.copyOf` 拒绝 null value，metrics 中出现 `"bestScore": null` 时抛 NPE
  - 调用点：`aps-service/src/main/java/com/hengce/aps/service/schedule/SolveTaskLifecycleService.java#L496`（`new SolveTaskDetail(record.id(), …, record…metrics…)`）
  - 触发数据：降级失败路径在 `metrics` 写入 `bestScore=null`（`ScheduleService#L341-L357` 的 `fallbackOrFailed` 分支），DB `solve_task.id=58` 可直接复核
- **建议口径**：崩溃点用 `Collections.unmodifiableMap(new LinkedHashMap<>(metrics))` 或过滤 null 键；不建议把 `bestScore` 从 metrics 里删掉（前端靠它区分"无解"与"未产出"）。
- **修复状态（源码，10:20）**：`SolveTaskDetail.java#L74` 已改为 `Collections.unmodifiableMap(new LinkedHashMap<>(metrics))` 并保留 null 语义（注释写明"`Map.copyOf` 会因 null 值抛 NPE"）。**18085 仍是 08:32 制品，`GET /solve-tasks/77` 与 `/86` 实测仍 500**（traceId `41757e248a584ce9a953149886c4d6bb`、`a5c598f5256543358897b23148f19b29`）；新构建后按 R-01a/R-01b 回归。

### QA2-02 未知 sandboxId 触发外键异常，返回 500

- **现象**：`POST /api/v1/solve-tasks` 传入不存在的 `sandboxId`，服务端未做存在性校验，直接 INSERT 触发 FK 约束，返回 500。
- **复现请求**：
  - `POST /api/v1/solve-tasks`，Header `X-API-Key: dev-write-key`，Body：
    `{"sandboxId":"a2a2a2a2-0000-4000-8000-0000000000ff","parameters":{}}`
- **实际响应**：
  - `HTTP 500`
    `{"code":50000,"message":"系统内部错误","data":null,"traceId":"af7934bfd5854a0dbba8fb2acb3b9923"}`
  - 历史同现象 traceId：`d938e73911b7462a895b04267bf5c761`
  - 服务端日志（原始）：
    `DataIntegrityViolationException ... 违反外键约束 "fk_solve_task_sandbox"；详细：键值对(sandbox_id)=(a2a2a2a2-0000-4000-8000-0000000000ff)没有在表"sandbox"中出现`
- **期望响应**：
  - `HTTP 400`，`code=40000`，`message` 明确指出 `sandboxId` 不存在（含该 UUID 或"场景不存在"字样）
  - 不得出现 `50000`；不得落库半成品任务
- **根因（文件#行号）**：
  - `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/schedule/SolveTaskRepository.java#L80`（`INSERT INTO solve_task (tenant_id, sandbox_id, …)`，FK 异常未捕获）
  - 受理入口无 sandbox 存在性校验：`aps-service/.../schedule/SolveTaskLifecycleService.java#L196-L204`（`solveTaskRepository.create(...)`）
  - 约束定义：`db/migration` 中 `fk_solve_task_sandbox`（`solve_task.sandbox_id → sandbox.id`）
- **修复状态（源码，10:26）**：`SolveTaskLifecycleService.java#L189-L191` 新增受理前预检
  `if (request.sandboxId() != null && !solveTaskRepository.sandboxExists(tenantId, sandboxId)) throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sandboxId);`
  ，`#L209-L213` 对 `DataIntegrityViolationException` 做 FK 兜底并转同一 `NOT_FOUND`。
  契约依据：`contract-freeze.md#L525`（显式传入须校验存在且属于当前租户）、`#L919`（code/UUID 不存在或已删除 → `40400`）。
  **旧实例仍返回 500**（traceId `993f48d1fa34435db7918fdc1e17a0e5`）；新构建后按 R-02b 回归（期望 `404/40400`）。

### QA2-03 不存在的订单号被受理为 200 并静默剔除

- **现象**：`orderNos` 点名一个不存在的订单号，接口返回 200 受理成功，后台把该订单号静默丢掉，最终任务 `SOLVED` 且 `message` 为"无待排任务"。
- **复现请求**：
  - `POST /api/v1/solve-tasks`，Header `X-API-Key: dev-write-key`，Body：
    `{"sandboxId":"a2a2a2a2-0000-4000-8000-000000000001","orderNos":["QA2-NOT-EXIST-ORDER"],"parameters":{}}`
    → `HTTP 200`，`data.taskId=72`、`status=PENDING`
  - 随后 `GET /api/v1/solve-tasks/72`
- **实际响应**：
  - 受理：`HTTP 200`、`code=0`、`data.status="PENDING"`
  - 终态：`HTTP 200`、`code=0`、`data.status="SOLVED"`、`data.phase="DONE"`、
    `data.errorMessage="无待排任务；降级标记: calendarFallback=false, dependencyFallback=false, setupMatrixFallback=false"`、
    `data.metrics.taskCount=0`、`persistedTaskCount=0`
  - traceId（受理）：`b0892ef4b656433abc9a6026d8bd87e4`；（终态）`11c7d19ec5e34a0d9b03a91994beac16`
- **期望响应**：
  - `HTTP 400`，`code=40000`，`message="请求的订单号不存在: QA2-NOT-EXIST-ORDER"`（逐字点名缺失订单号）
  - 不得 200 受理
- **根因/现状（文件#行号）**：
  - 研判校验已存在于源码：`ScheduleProblemAssembler.java#L310-L316`（`requireExistingOrderNos`）→ `#L325-L341`（`requireAllRequestedOrdersFound`，缺失时抛 `400/40000`）
  - 源码调用点：`SolveTaskLifecycleService.java#L185`（`scheduleService.requireExistingOrderNos(request)`）
  - **现状（2026-11-04 11:05）**：入口校验已在当前源码实现；18085 仍是 08:32 旧制品，实测 `POST orderNos=["QA2-NOT-EXIST-ORDER"]` → `200/0`（taskId `88`，traceId `e721aa323b0f4105b23ae887fd348e20`）。**旧包 200 属旧制品观测**，不代表当前实现；新构建后按 R-03 回归。

---

## 3. P2 缺陷（9 条）

### QA2-04 STOMP 进度推送超过 1 次/秒

- **现象**：订阅 `/topic/solve-tasks/{id}`，10 秒收到 18 帧（≈1.8 帧/秒，间隔约 500ms），超过契约"进度推送节流 1 次/秒"。
- **复现请求**：
  - 用 Spring `WebSocketStompClient`（`StandardWebSocketClient` + `MappingJackson2MessageConverter`，payload 类型 `Map`）
  - `connectAsync("ws://localhost:18085/ws/solve-tasks/{taskId}")` → `subscribe("/topic/solve-tasks/{taskId}")`
  - 同时 `POST /api/v1/solve-tasks`（`{"sandboxId":"…0001","orderNos":["QA2-SO-003"],"parameters":{}}`）
  - 计时 10s，统计帧数与相邻帧时间差
- **实际响应**：10s 内 18 帧；最小相邻间隔约 500ms；帧示例
  `{"taskId":51,"status":"RUNNING","progress":0.1000,"phase":"SOLVING","bestScore":null,"elapsedMs":500,...}`
- **期望响应**：同一任务相邻进度帧间隔 ≥1000ms（终态帧不受此限）；10s 内进度帧 ≤10 帧
- **根因（文件#行号）**：
  - `aps-service/.../schedule/SolveTaskLifecycleService.java#L72`（`PROGRESS_TICK_MILLIS = 500L`）与 `#L356`（以 500ms 周期调度）
  - `aps-service/.../schedule/SolveProgressPublisher.java#L53-L61`（`publish` 无最小间隔门控，直接 `eventPublisher.publishEvent`）
  - 契约：`docs/api/contract-freeze.md#L1434-L1450`

### QA2-05 出现 `RUNNING + phase=DONE` 自相矛盾帧

- **现象**：终态前收到 `status=RUNNING` 但 `phase=DONE`、`resultTaskId=null` 的帧，约 2ms 后才收到 `SOLVED` 帧。前端按 phase 渲染会把运行中误判为已完成。
- **复现请求**：同 QA2-04 的订阅方式，观察 `PERSISTING(0.90)` 之后的最后一帧
- **实际响应**：
  `{"status":"RUNNING","phase":"DONE","progress":0.9500,"elapsedMs":10042,"resultTaskId":null}`
  2ms 后：`{"status":"SOLVED","phase":"DONE","progress":1.0,"resultTaskId":51,...}`
- **期望响应**：`phase=DONE` 的帧必须 `status="SOLVED"` 且 `resultTaskId` 非空；或在 `DONE` 前不发 RUNNING 帧，直接发终态帧
- **根因（文件#行号）**：
  - `SolveTaskLifecycleService.java#L443`（`publish` 硬编码 `SolveTaskStatus.RUNNING.name()`）
  - `ScheduleService.java#L369`（`progressSink.update("DONE", 1.0, bestScore(solution))`，在结果落库后、终态帧前触发）
  - 契约：`docs/api/contract-freeze.md#L1384-L1406`（phase 映射）

### QA2-06 日历详情缺 `timezone`

- **现象**：`GET /api/v1/calendars/{id}` 响应无 `timezone` 字段，前端无法按契约做时区换算。
- **复现请求**：`GET /api/v1/calendars/9c85da83-f36f-459e-bd26-1975479f1c37`
- **实际响应**：
  `HTTP 200`，`data={"id":"9c85da83-…","name":"QA2-CAL-01","calendarVersion":1,"type":"FACTORY","effectiveFrom":"2026-11-01","shifts":[…],"holidays":[],"version":3}`
  —— 无 `timezone`（traceId `62bd6944d7e04366a28e9f9cf2fba60d`）
- **期望响应**：`HTTP 200`，`code=0`，`data.timezone="Asia/Shanghai"`（只读投影，取配置 `aps.calendar.timezone`，缺省 `Asia/Shanghai`）
- **根因（文件#行号）**：
  - `aps-service/.../masterdata/calendar/dto/CalendarResponse.java#L23-L34`（record 无 `timezone` 分量）
  - `aps-service/.../masterdata/calendar/CalendarService.java#L373-L385`（`toResponse` 未注入配置值）
  - 契约：`docs/api/contract-freeze.md#L939`、`#L946-L948`

### QA2-07 `FIXED` 缺 `primaryResourceId` 返回 40000（应为 42200）

- **现象**：`primaryResourceMode=FIXED` 但未给 `primaryResourceId`，返回 `400/40000`；契约冻结为 `42200`。
- **复现请求**：
  `PUT /api/v1/routings/38aede66-e236-4880-be0a-08abe57ff341/graph`，Header `X-API-Key: dev-write-key`，Body：
  `{"nodes":[{"seqNo":10,"operationCode":"OPA","standardSeconds":60,"primaryResourceMode":"FIXED"}],"edges":[],"version":2}`
- **实际响应**：
  `HTTP 400`，`{"code":40000,"message":"primaryResourceMode=FIXED 时必须提供 primaryResourceId","data":null,"traceId":"9ea0e4df865749de963bb657bbbfee6b"}`
- **期望响应**：`HTTP 422`，`code=42200`，`message` 同上语义
- **根因（文件#行号）**：
  - `aps-service/.../process/routing/RoutingNodeModes.java#L56-L59`（`throw new BizException(ErrorCode.BAD_REQUEST, …)`）
  - 契约：`docs/api/contract-freeze.md#L1081`

### QA2-08 `INHERIT` 携带 `primaryResourceId` 仍 200 且静默丢弃

- **现象**：模式为 `INHERIT` 却传了 `primaryResourceId`，保存成功但不报错，且该资源被静默丢弃，回读只剩 `primaryResourceMode=INHERIT`。
- **复现请求**：
  `PUT /api/v1/routings/38aede66-e236-4880-be0a-08abe57ff341/graph`，Body：
  `{"nodes":[{"seqNo":10,"operationCode":"OPA","standardSeconds":60,"primaryResourceMode":"INHERIT","primaryResourceId":"b4deee4f-fd6f-4237-bedb-e9dc192b189c"}],"edges":[],"version":2}`
- **实际响应**：
  - 写：`HTTP 200`，`code=0`（traceId `5413f42e62c641cb8dc8275a3c2b2b33`）
  - 回读：`HTTP 200`，`data.nodes[0]={"seqNo":10,"operationCode":"OPA","standardSeconds":60,"primaryResourceMode":"INHERIT","optional":false,"sequence":10}` —— `primaryResourceId` 消失（traceId `80799fe45622440ea9ac7a79da141e82`）
- **期望响应**：`HTTP 422`，`code=42200`，`message` 指明"`primaryResourceMode=INHERIT` 时不得携带 `primaryResourceId`"；不得静默丢弃
- **根因（文件#行号）**：
  - `RoutingNodeModes.java#L48-L51`（`INHERIT` 分支不做互斥校验，直接返回）
  - `RoutingService.java#L486-L489`（`RoutingOverrides.build(mode, primaryResourceId, …)` 对 `INHERIT` 不写键，资源被丢弃）
  - 契约：`docs/api/contract-freeze.md#L1078-L1084`

### QA2-09 `INHERIT` 无模板主资源仍可保存，求解期才 FAILED

- **现象**：节点 `INHERIT` 且工序模版无主资源、租户资源数 >1，保存成功；求解时才以 `FAILED` 报"缺少主资源"。
- **复现请求**：
  - `PUT /api/v1/routings/{id}/graph` 建/改 `INHERIT` 节点（不带 `primaryResourceId`，对应工序模版无 `primaryResourceId`/`primaryResourceGroupId`）
  - `POST /api/v1/solve-tasks`（`{"engine":"ORTOOLS","sandboxId":"…0001","orderNos":["QA2-SO-003"],"parameters":{}}`；改用 TIMEFOLD 同样命中）
- **实际响应**：
  - 图保存：`HTTP 200`、`code=0`
  - 求解：`data.status="FAILED"`，`data.errorMessage="工序 n10 缺少主资源，无法排产"`，`data.metrics.validationFailure=true`（task 70 / task 44，traceId `30cac6d07f7a47698a7f9562f977634c`）
- **期望响应**：图保存阶段即 `HTTP 422`、`code=42200`，`message` 指向该节点（`INHERIT` 无法解析出主资源）；不应把契约校验推迟到求解期
- **根因（文件#行号）**：
  - `RoutingService.java#L486-L489`（保存时仅做三态规范化，未校验 `INHERIT` 能否解析出主资源）
  - 延迟暴露点：`aps-service/.../schedule/ScheduleProblemAssembler.java#L624-L625`（`"工序 " + node.getNodeKey() + " 缺少主资源，无法排产"`）；解析逻辑 `#L600-L626`

### QA2-10 `LOCKED_RESOURCE` 引用不存在的资源编码仍 200

- **现象**：冻结为 `LOCKED_RESOURCE` 但 `lockedResourceCodes` 写的是不存在的资源编码，接口 200 接受，留下无法解释的脏锁。
- **复现请求**：
  `PUT /api/v1/orders/QA2-SO-002/freeze`，Header `X-API-Key: dev-write-key`，Body：
  `{"freezeStatus":"LOCKED_RESOURCE","lockedResourceCodes":["QA2-NO-RES"],"version":5}`
- **实际响应**：
  `HTTP 200`，`code=0`，`data.freezeStatus="LOCKED_RESOURCE"`，`data.freezeLock={"resourceCodes":["QA2-NO-RES"]}`（traceId `41d718ad3db84e66806ac97339858cff`）
- **期望响应**：`HTTP 422`，`code=42200`，`message` 点名不存在的资源编码；不得落库
- **根因（文件#行号）**：
  - `aps-service/.../masterdata/order/OrderService.java#L440-L495` 的 `normalizeLock`：只校验 `codes.isEmpty()`，未校验资源存在（`throw new BizException(ErrorCode.BAD_REQUEST, "LOCKED_RESOURCE 需要至少一个 lockedResourceCodes")` 所在分支）
  - 契约：`docs/api/contract-freeze.md#L1491-L1510`（C23 四态冻结）

### QA2-11 订单释放自动冻结任务只写 `status`，未写 `lock_type`

- **现象**：`PUT /orders/{orderNo}/release` 把订单下 `PLANNED` 任务置 `FROZEN`，但 `task.lock_type` 仍为 `NONE`，与"释放自动冻结写 `lock_type`"的契约不一致；甘特图锁标识会漏显。
- **复现请求**：
  - `PUT /api/v1/orders/QA2-SO-003/release`（orderNo 为已 `CONFIRMED` 且其下有 `PLANNED` 任务的订单）
  - 断言 SQL：`SELECT id, status, lock_type FROM task WHERE order_id = <QA2-SO-003>`
- **实际响应**：`HTTP 200`、`code=0`；DB 实测：
  `1710 | FROZEN | NONE`、`1711 | FROZEN | NONE`、`1712 | FROZEN | NONE`
- **期望响应**：`HTTP 200`、`code=0`；DB 三行应为 `status=FROZEN, lock_type=FROZEN`
- **根因（文件#行号）**：
  - `aps-infrastructure/.../persistence/order/OrderTaskFreezeRepository.java#L42-L47`
    `UPDATE task SET status = 'FROZEN', updated_at = CURRENT_TIMESTAMP WHERE …` —— **未写 `lock_type`**
  - 调用点：`OrderService.java#L389-L391`（释放订单并自动冻结 `PLANNED` 任务）
  - 契约：`docs/api/contract-freeze.md#L1491-L1510`

### QA2-12 激活工艺路线响应 `nodes/edges` 恒为空

- **现象**：`POST /routings/{id}/activate` 返回 `200`，但 `data.nodes=[]`、`data.edges=[]`，即使该路线图有节点。前端激活后刷新会用空图覆盖本地状态。
- **复现请求**：
  `POST /api/v1/routings/38aede66-e236-4880-be0a-08abe57ff341/activate`（该路线已 ACTIVE，走幂等分支）
  对照：`GET /routings/38aede66-e236-4880-be0a-08abe57ff341/graph` 有 1 个节点
- **实际响应**：
  `HTTP 200`，`data={"id":"38aede66-…","productCode":"QA2-FG-002","routingVersion":"V8","status":"ACTIVE","nodes":[],"edges":[],"version":4}`（traceId `05a82772374e4538af49451860d23e09`）
- **期望响应**：`HTTP 200`、`code=0`，且 `data.nodes`/`data.edges` 与该路线图一致（至少 `nodes` 含 `seqNo=10` 节点）
- **根因（文件#行号）**：
  - `aps-web/.../controller/masterdata/RoutingController.java#L160`（`toRoutingView(routingService.activate(id), null)`，graph 入参写死 `null`）
  - `RoutingController.java#L214-L222`（`graph == null` 时 `nodes/edges` 保持 `List.of()`）

---

## 4. 接口/契约缺口（不计入缺陷，供 S0 判定）

| 编号 | 缺口 | 证据 | 影响 |
|---|---|---|---|
| G1 | `FLOAT` 候选资源集为全租户资源，不可用资源也进题面 | solve 46 报文含 `DEMO-RES-BATCH-01(runSeconds=600)`、`SE04-R1/R2(28800)` 等无关资源 | 浮动工序可能被排到不适配资源；契约 §20.3.1.4 只约束时长一致性，未约束候选集 |
| G2 | 卡片写"重复发布 40903"，契约 C24 §24.9 为幂等 200 | 实测重复 `POST /plans/{id}/publish` 返回 200 | 验收口径冲突，建议以契约 200 为准并修卡片 |
| G3 | `INHERIT` 携带 `primaryResourceId` 的语义未定义 | 见 QA2-08 | 实现选择"静默丢弃"，属未定义行为，需契约补一句 |
| G4 | `calendar.timezone` 无 DB 列，只能配置投影 | 见 QA2-06 | 多时区需求需另发迁移号 |
| G5 | `tasks[]` 是"场景任务快照"而非"本次求解任务" | `GET /solve-tasks/{id}` 返回场景内其他订单任务 | 易被误读为本次求解产出，建议前端/文档显式说明 |
| G6 | `snapshot.taskId` 为字符串（BIGSERIAL 的 JSON 序列化） | 甘特/任务快照报文 | 与 `solve_task.id` 数值类型不一致，前端易踩 |

---

## 5. 回归判据（已按契约对齐，2026-11-04 11:05 修订）

回归脚本：`scripts/qa/m2-qa-02-p1-regress.ps1`（同一请求、同一断言；退出码 = FAIL 条数）。判据共 5 条，**任何一条不满足即 FAIL**；契约未明确规定的一律标 `TBD-contract`，不自行发明期望值。

### R-01a 引擎降级路径可读（对应 QA2-01 主路径）

1. `POST /solve-tasks` Body `{"engine":"ortools","sandboxId":"<QA2 sandbox>","orderNos":["<有效订单>"],"parameters":{}}` → `HTTP 200` 且 `code=0`，取得 `data.taskId`
2. 轮询 `GET /solve-tasks/{taskId}`（500ms，上限 30s）：**全程不得出现 HTTP 500 / code 50000**
3. 终态报文必须同时满足：
   - `HTTP 200`，`code=0`
   - `data.degraded=true`
   - `data.metrics.engineFallback=true`
   - `data.metrics.engineFallbackReason="SOLVER_UNAVAILABLE"`
   - `data.metrics.engineFallbackDetail="ORTOOLS_NOT_ENABLED"`
   - 顶层四个声明字段**恒存在**（值可为 null）：`bestScore`、`resultTaskId`、`errorCode`、`errorMessage`
   - `data.status != "FAILED"`（契约 L1754：ortools 未启用时"不得因此让整个请求失败"）
   - **`data.status` 与降级标记正交（已裁定 2026-11-04，见 `contract-freeze.md` §24.2 第 6 条）**：完整可行解落库时 `status` 可为 `SOLVED`，但 `SOLVED` 必须同时带 `degraded=true` 与 `engineFallback*` 标记；断言 = 若 `status=="SOLVED"` 则 `degraded=true` 且 `engineFallback=true`，否则 FAIL（不得出现「`SOLVED` 且无降级标记」）
   - 契约依据：`contract-freeze.md#L1754`（did）、`#L1439`（声明字段一律输出）

### R-01b 含显式 null 的 metrics 可读（对应 QA2-01 崩溃条件）

1. 对 `-NullMetricsTaskIds`（缺省 `77,74,73,58`，均为历史降级失败任务）逐个 `GET /solve-tasks/{id}`
2. 命中即 PASS 的条件：`HTTP 200` + `code=0` + `data.metrics` 中**至少存在一个显式 null 值的键**（如 `bestScore: null`）
3. 若候选全部返回 500，则 FAIL 并打印每条 traceId
4. 若历史任务已被清理：用"降级失败"路径现造一条（无人值守时以 `engine` 传入未启用适配器或触发引擎异常），把新 id 通过 `-NullMetricsTaskIds` 传入；造数方法与本条 FAIL 输出一并记入本文件，用后按第 6 节数据卫生口令清理（`DELETE FROM solve_task WHERE id = <新 id>`，仅限自造行）

### R-02a `sandboxId` 格式非法（对应 QA2-02 反序列化层）

1. `POST /solve-tasks` Body `{"sandboxId":"not-a-uuid","parameters":{}}`
2. 必须满足：`HTTP 400` 且 `code=40000`（非法 UUID 在参数反序列化层被拒）
3. 契约依据：`contract-freeze.md` §24.9「`400/40000` 与 `404/40400` 的分界」（逐字条款：非法 UUID 一律 `40000`）；通用规则见 `conventions.md` §3 规则 4

### R-02b `sandboxId` 合法但不存在（对应 QA2-02 主路径）

1. `POST /solve-tasks` Body `{"sandboxId":"a2a2a2a2-0000-4000-8000-0000000000ff","parameters":{}}`
2. 必须满足：`HTTP 404` 且 `code=40400`，`message` 匹配 `场景不存在`（可含该 UUID），且 `HTTP < 500`
3. 契约依据：`contract-freeze.md#L525`（显式传入须校验存在且属于当前租户）、`#L919`（code/UUID 不存在或已删除 → `40400`）；**不是** `400/40000`
4. 旧判据「400/40000」为错误口径，已作废

### R-03 显式点名不存在订单（对应 QA2-03）

1. `POST /solve-tasks` Body `{"sandboxId":"<QA2 sandbox>","orderNos":["QA2-NOT-EXIST-ORDER"],"parameters":{}}`
2. 必须满足：`HTTP 400` 且 `code=40000`，`message` 包含 `QA2-NOT-EXIST-ORDER`
3. 不得返回 `200`（含以 `PENDING` 受理成功）
4. 契约依据：`contract-freeze.md` §24.2 第 5 条（**逐字条款**，2026-11-04 冻结）：请求体数组元素不存在 → `400/40000` 整批拒绝并逐字点名；路径资源不存在才用 `40400`

### TBD-contract 清单（**已清空：T1–T3 于 2026-11-04 全部冻结**）

| 项 | 裁定结果 | 契约落点 |
|---|---|---|
| T1 | `status` 与 `degraded` 正交：降级后完整可行解可 `SOLVED`，但必须带 `degraded=true` + `engineFallback*`；二者不互相推断 | `contract-freeze.md` §24.2 第 6 条 |
| T2 | 非法 UUID → `400/40000`（`40400` 只用于路径资源不存在） | `contract-freeze.md` §24.9 错误码分界；`conventions.md` §3 规则 4 |
| T3 | 请求体 `orderNos[]` 元素不存在 → `400/40000` 整批拒绝并逐字点名 | `contract-freeze.md` §24.2 第 5 条 |

### 回归前置

- 实例必须是**包含修复的新构建**。对 18085 旧构建（08:32）跑本脚本的基线为 **PASS=1 / FAIL=4**（仅 R-02a 通过），与源码 10:20–10:44 的修复状态不符，属正常现象。
- 脚本只写 `QA2-` 前缀数据，不触碰共享 sandbox `00000000-0000-0000-0000-000000000401`。

---

## 6. 测试数据落点（自造，`QA2-` 前缀）

| 表 | 数量 | 关键标识 | 用途 |
|---|---|---|---|
| `material` | 6 | `QA2-FG-001/002/003`、`QA2-RM-*` | 主数据 CRUD、BOM |
| `resource` | 2 | `QA2-RES-A`(`b4deee4f`)、`QA2-RES-B` | 求解/甘特/锁资源 |
| `calendar` | 1 | `QA2-CAL-01`(`9c85da83`) | 班次/节假日/timezone |
| `operation_template` | 3 | `OPA/OPB/OPC` | 工艺路线节点 |
| `routing` | 6 | `38aede66`（QA2-FG-002 V8，现 ACTIVE）等 | 三态/激活/图 |
| `bom` | 3 | `QA2-BOM-*` | BOM 树 |
| `orders` | 4 | `QA2-SO-001..004` | 优先级/四态冻结/释放 |
| `strategy` | 1 | `QA2-STR-01`(`a3eabd26`，8×12.5) | 软约束权重 |
| `constraint_dsl` | 1 | `QA2-DSL-01`（ACTIVE v2） | DSL 校验/激活/回滚 |
| `inventory` | 2 | `QA2-INV-*` | MRP SHORTAGE |
| `sandbox` | 1 | `a2a2a2a2-0000-4000-8000-000000000001`（直接 INSERT） | 隔离求解场景 |
| `solve_task` | 20+ | `44/46-52/54/56-59/61/63/70/72/73/79/86/88` | 求解生命周期（79/86/88 为回归脚本复跑新造） |
| `plan_version` | 4 | `PLAN-20261104-001..004` | 发布/快照/回滚 |
| `task` | 6 | `1647/1648/1649/1710/1711/1712` | 甘特/冻结/释放 |

未写入共享 sandbox `00000000-0000-0000-0000-000000000401` 的任何任务行（已核对）。

本次复测的额外写操作已还原：
- 路由 `38aede66`：QA2-08 写入 `INHERIT` 后已用原 `FIXED + b4deee4f` 回写（`version` 由 2 递增至 4，属版本号递增，非结构变更）
- 订单 `QA2-SO-002`：`LOCKED_RESOURCE` 重入，`version` 仍为 5（无结构变更）
