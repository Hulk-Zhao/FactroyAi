# M3 P2 清理车道 · P2 统一收敛台账

- 核查日期：2026-11-04
- 范围：M3 安全扫描 8 条 P2 + M2 遗留 9 条 P2，共 **17 条**
- 方式：只读源码核查 + 对照已冻结契约；未修改任何 `aps-service` / `aps-web` / `aps-infrastructure` Java 业务代码，未触碰 `docs/api/openapi/*.yaml`，未 commit/push，未执行 `mvn package`，未启动后端实例（上述为**首轮核查口径**；后续契约同步由契约车道单独完成，见 §5）
- 口径说明：本文作为唯一收敛入口，不改 `docs/qa/m3-security-scan.md` 与 `docs/qa/m2-qa-02-defects.md` 正文，避免与并行车道产生文档冲突；两份原报告仍是原始扫描/联调记录

## 0. 计数

**P2 总数 17 / 已修 13 / 需修 0 / 转已知限制 2 / 转后续 2**

| 分类 | 数量 | 条目 |
| --- | --- | --- |
| 已修 | 13 | P2-1、P2-2、P2-3、P2-4、P2-7、P2-8、QA2-04、QA2-05、QA2-06、QA2-07、QA2-10、QA2-11、QA2-12 |
| 需修 | 0 | — |
| 明确接受并转已知限制 | 2 | P2-6、QA2-09 |
| 转后续 | 2 | P2-5、QA2-08 |

> 计数口径（2026-11-04 修复批次收口）：13 = 原已修 4（P2-1~4）+ 本轮落地 9（P2-7、P2-8、QA2-04~07、QA2-10~12）。P2-5 代码已修但无 HTTP 取证入口，仍列“转后续”；QA2-08 仍等契约先行。逐条证据见 §5。

### 0.1 关于“M2 遗留 9 条”的来源核对

- 逐目录检索后，`docs/qa/m2-qa-02-defects.md` 明确自述 **P2 = 9**（`QA2-04`–`QA2-12`，见该文件第 13、22–30 行），即本轮采用的 9 条。
- `docs/perf/m2-qa-01-perf.md` 第 363–365 行另有 **3 条性能 P2**（堆内存、指标失真、50 订单耗时），属性能报告风险表，不在缺陷清单的 9 条口径内，**未计入上表**，避免重复计数。
- `docs/plan` 下未发现 P2 台账条目；`docs/ui/ui-audit.md` 的 P2 属前端审计，不在本车道范围。

---

## 1. 逐条判定表

### 1.1 M3 安全扫描（8 条）

原始记录：[m3-security-scan.md](file:///d:/tools/smart-aps-agent/docs/qa/m3-security-scan.md#L184-L201) 风险清单，以及第 39–49 行“疑似泄露”表。

| 编号 | 原始描述 | 出处 | 判定 | 证据位置 / 最小修复方案 |
| --- | --- | --- | --- | --- |
| P2-1 | 导出资源编码缺租户谓词 | `m3-security-scan.md:194`（另见 `:43`） | **已修** | [EntityDataExporter.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/importexport/EntityDataExporter.java#L271-L278) 的 `resourceCodeOf` 已为 `where id = ? and tenant_id = ? and deleted = false`；修复随提交 `8fe9249` 落地 |
| P2-2 | 求解完成/归因清理缺租户谓词 | `m3-security-scan.md:195`（另见 `:44-L45`） | **已修** | [ScheduleRepository.java](file:///d:/tools/smart-aps-agent/aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/schedule/ScheduleRepository.java#L221-L231) 的 `finishSolveTask` 已带 `AND tenant_id = ?`；[归因清理](file:///d:/tools/smart-aps-agent/aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/schedule/ScheduleRepository.java#L260-L278) 已带 `WHERE tenant_id = ?` |
| P2-3 | 导入作业状态/终态缺租户谓词 | `m3-security-scan.md:196`（另见 `:46`） | **已修** | [ImportJobRepository.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/importexport/ImportJobRepository.java#L99-L177) 的 `status/markRunning/finishSuccess/finishFailed/cancel` 全部改为显式 `tenantId` 参数并带 `tenant_id` 谓词；调用点 [ImportJobService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/importexport/ImportJobService.java#L165) 与 [取消回读](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/importexport/ImportJobService.java#L232) 已同步 |
| P2-4 | 关联表替换无租户谓词 | `m3-security-scan.md:197`（另见 `:47`） | **已修** | [RbacRepository.java](file:///d:/tools/smart-aps-agent/aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/rbac/RbacRepository.java#L215-L229) 与 [角色权限替换](file:///d:/tools/smart-aps-agent/aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/rbac/RbacRepository.java#L240-L251) 均先调用租户守卫；[守卫实现](file:///d:/tools/smart-aps-agent/aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/rbac/RbacRepository.java#L254-L272) 校验 `users/role.tenant_id`，DELETE 也用 `EXISTS` 绑定租户 |
| P2-5 | 审计脱敏边界与绕过点 | `m3-security-scan.md:198`（另见 `:154`） | **转后续**（代码已修，无 HTTP 取证入口） | 修复批次（2026-11-04）已扩 [AuditValues.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/audit/AuditValues.java#L19-L71)：敏感键集合、`Map/List/Set/数组` 嵌套递归、JSON 字符串按 `"key": value` 正则脱敏；6 处直写均已包 `AuditValues.redact`（OrderService 3、PlanDispatchService、SandboxService、TaskAdjustService；全仓调用点 11 处含 AuditLogService 2 处），单测通过。仍列“转后续”的原因：HTTP 层无可观测入口，无法做制品级取证；原“8 处直写”按代码实为 6 处，以代码为准 |
| P2-6 | 4xx 错误信息透传 | `m3-security-scan.md:199`（另见 `:150`） | **明确接受并转已知限制** | [RestExceptionHandler.java](file:///d:/tools/smart-aps-agent/aps-web/src/main/java/com/hengce/aps/web/advice/RestExceptionHandler.java#L62-L72) 仍对 4xx 返回 `exception.getMessage()`；[ImportJobService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/importexport/ImportJobService.java#L219-L229) 与 [StrategyService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/strategy/StrategyService.java#L453-L459) 会把 `BizException` 文案写入可见错误行。判定理由：契约要求逐字点名订单号等业务标识（`contract-freeze.md` §24.2），4xx 文案本身是面向用户的稳定文案，检索未发现 SQL/表名透出；若统一改成泛化文案会直接破坏验收判据。影响面：未来若开发者把内部细节写进 `BizException`，会经此路径外泄。触发条件：新增 4xx 文案包含内部标识/SQL/表名/他租户 ID |
| P2-7 | Swagger/Actuator 匿名 | `m3-security-scan.md:200`（另见 `:130`） | **已修** | 修复批次（2026-11-04）：[application.yml](file:///d:/tools/smart-aps-agent/aps-admin/src/main/resources/application.yml#L93-L101) 默认 `springdoc.api-docs/swagger-ui.enabled=${APS_SPRINGDOC_ENABLED:false}`（prod 默认关闭），[application-dev.yml](file:///d:/tools/smart-aps-agent/aps-admin/src/main/resources/application-dev.yml#L32-L37) 显式打开。实测：dev `GET /v3/api-docs` → 200；prod profile 下 `/v3/api-docs`、`/swagger-ui.html`、`/actuator/*` 全 404 |
| P2-8 | sort 契约不一致 / 限流可绕过 / 上传压缩比 | `m3-security-scan.md:201`（另见 `:174-L180`） | **已修**（主判定：限流键；sort 子项**转后续**、上传子项**接受**，见右） | 限流（主判定，已修）：修复批次（2026-11-04）把 [RateLimitFilter.resolveKey](file:///d:/tools/smart-aps-agent/aps-web/src/main/java/com/hengce/aps/web/filter/RateLimitFilter.java#L103-L118) 改为以 [TenantContext](file:///d:/tools/smart-aps-agent/aps-web/src/main/java/com/hengce/aps/web/filter/RateLimitFilter.java#L7) 解析出的身份 + 凭据指纹（JWT subject / API Key 哈希 / IP）组键，不再取客户端可控的 `X-Tenant-Id`；实测轮换 `X-Tenant-Id` 至第 3 次即 `429 / 42900`，额度不再随头放大。sort（子项）：仍不一致（[ResourceService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/masterdata/resource/ResourceService.java#L462-L477) 未知字段静默回退，[RoutingService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/process/routing/RoutingService.java#L590-L608) 与 [SandboxService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/sandbox/SandboxService.java#L241-L251) 抛 400）→ **转后续**（契约一致性，非安全）。上传（子项）：[ImportLimits.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/importexport/ImportLimits.java#L9-L18) 已有 5MB/5000 行上限，POI 5.3 默认 `ZipSecureFile` 防护生效 → **接受** |

### 1.2 M2 遗留缺陷（9 条）

原始记录：[m2-qa-02-defects.md](file:///d:/tools/smart-aps-agent/docs/qa/m2-qa-02-defects.md#L123-L249) 第 3 节。

| 编号 | 原始描述 | 出处 | 判定 | 证据位置 / 最小修复方案 |
| --- | --- | --- | --- | --- |
| QA2-04 | STOMP 约 1.8 帧/秒，超过 1 次/秒 | `m2-qa-02-defects.md:22`、`:125-L139` | **已修** | 修复批次（2026-11-04）：[SolveTaskLifecycleService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/SolveTaskLifecycleService.java#L82) 新增 `PROGRESS_MIN_INTERVAL_MILLIS=1000` 门控（`PROGRESS_TICK_MILLIS=500` 保留为采样节拍，推送前按 taskId+phase 判最小间隔；状态变化/终态不受限）。实测：连续 10 帧间隔 1001–1511ms，均 ≥1000ms，满足契约 `contract-freeze.md:1515` 的 progress/bestScore ≤1 次/秒 |
| QA2-05 | 出现 `RUNNING + phase=DONE` 自相矛盾帧 | `m2-qa-02-defects.md:23`、`:141-L152` | **已修** | 修复批次（2026-11-04）：[ScheduleService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java#L400-L406) 已删除“落库后先发 RUNNING+phase=DONE”的进度帧，`phase=DONE` 只随终态帧（`status=SOLVED`、`resultTaskId` 非空）输出。实测：全流程无 `RUNNING + phase=DONE` 帧，无 DONE 假帧 |
| QA2-06 | `GET /calendars/{id}` 缺 `timezone` | `m2-qa-02-defects.md:24`、`:154-L165` | **已修** | 修复批次（2026-11-04）：[CalendarResponse.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/masterdata/calendar/dto/CalendarResponse.java#L33) 新增 `timezone` 分量；[CalendarService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/masterdata/calendar/CalendarService.java#L78) 由 `aps.calendar.timezone`（缺省回落 `aps.business.time-zone` → `Asia/Shanghai`）注入。实测 `GET /calendars/{id}` 响应含 `"timezone":"Asia/Shanghai"`，与契约 `contract-freeze.md:949` 一致 |
| QA2-07 | `FIXED` 缺 `primaryResourceId` 返回 40000，契约要求 42200 | `m2-qa-02-defects.md:25`、`:167-L178` | **已修** | 修复批次（2026-11-04）：[RoutingNodeModes.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/process/routing/RoutingNodeModes.java#L54-L61) 该分支改抛 `ErrorCode.UNPROCESSABLE_ENTITY`。实测：`resourceMode=FIXED` 且缺 `primaryResourceId` → HTTP 422 / code 42200，与契约 `contract-freeze.md:1091` 一致 |
| QA2-08 | `INHERIT` + `primaryResourceId` 返回 200 且静默丢弃 | `m2-qa-02-defects.md:26`、`:180-L193` | **转后续**（契约先行） | [RoutingNodeModes.normalize](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/process/routing/RoutingNodeModes.java#L48-L65) 无 `INHERIT` 互斥分支；[RoutingService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/process/routing/RoutingService.java#L486-L489) 对 `INHERIT` 不写键。契约 §20.3.1.5（`:1088-L1092`）只定义 `FLOAT/FIXED` 校验，**未定义 `INHERIT` 携带 id**，原报告 G3 也承认语义未定。最小方案：先补契约“`INHERIT` + 非空 id → `42200`”，再在 `normalize` 加分支；避免无契约依据改语义 |
| QA2-09 | `INHERIT` 无模板主资源仍可保存，求解期才 FAILED | `m2-qa-02-defects.md:27`、`:195-L207` | **明确接受并转已知限制** | 现状：[RoutingService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/process/routing/RoutingService.java#L486-L489) 仅做三态规范化；[ScheduleService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java#L336-L345) 把装配期 `42200` 转任务 `FAILED + validationFailure=true`。契约 `contract-freeze.md:1045` 明确主资源解析链在**装配/求解期**，最终才 42200；保存期不知道目标场景资源集，无法可靠判定。影响面：用户保存成功后才在求解阶段看到失败；触发条件：节点 `INHERIT`、模板无主资源/资源组、租户资源数 >1。不修理由：改成保存期 422 需契约改口径并引入场景上下文；如产品坚持前置，另立契约条款 |
| QA2-10 | `LOCKED_RESOURCE` 资源编码不存在仍 200 | `m2-qa-02-defects.md:28`、`:209-L220` | **已修** | 修复批次（2026-11-04）：[OrderService.normalizeLock](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/masterdata/order/OrderService.java#L450-L470) 在“去空后非空”前置之上叠加**当前租户 + `deleted=false` 的资源存在性校验**，缺失编码点名返回 `42200`；契约同步补 §24.5 第 2 条存在性条款（`contract-freeze.md:1663`）。实测 `lockedResourceCodes:["RES-NOPE"]` → HTTP 422 / code 42200，message `锁定资源不存在: [RES-NOPE]`。<br>**更正说明（2026-11-04 修复批次核实）**：若旧版本写作“订单未校验 BOM/工艺路线”，该描述有误——实际缺口是**锁定资源编码存在性未校验**，BOM/工艺路线校验本就有（`validateRouting` 与确认时 `verifyUniqueActiveBom/Routing` 覆盖）。本行原编号与原描述保留以便追溯。 |
| QA2-11 | `/release` 自动冻结任务只写 `status` 未写 `lock_type` | `m2-qa-02-defects.md:29`、`:222-L235` | **已修** | 修复批次（2026-11-04）：[OrderTaskFreezeRepository.java](file:///d:/tools/smart-aps-agent/aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/order/OrderTaskFreezeRepository.java#L44) 同一条 UPDATE 改为 `SET status='FROZEN', lock_type='FROZEN'`，满足契约 `contract-freeze.md:1627`。落库实测行：`9001 | FROZEN | FROZEN | 1`（task_id \| status \| lock_type \| version） |
| QA2-12 | `POST /routings/{id}/activate` 响应 `nodes/edges` 恒为空 | `m2-qa-02-defects.md:30`、`:237-L248` | **已修** | 修复批次（2026-11-04）：[RoutingController.java](file:///d:/tools/smart-aps-agent/aps-web/src/main/java/com/hengce/aps/web/controller/masterdata/RoutingController.java#L163-L166) 激活后调用 `routingService.getGraph(id)` 再组装响应（幂等分支同路径）。实测：`activate` 响应与随后 `GET /routings/{id}` 详情均为 `nodes=2, edges=1`。<br>**更正说明（2026-11-04 修复批次核实）**：若旧版本写作“`GET /routings/{id}` graph 恒空”，该描述有误——实际坏点是 `POST /routings/{id}/activate` 返回空图，`GET /routings/{id}` 详情本来就带图。本行原编号与原描述保留以便追溯。 |

---

## 2. 需修项最小修复方案（历史记录：已于 2026-11-04 修复批次全部落地）

1. **P2-7（配置）**：新增 prod profile 配置关闭 `springdoc.api-docs` / `swagger-ui`；或把文档路径纳入鉴权。需先确认部署使用的 profile 名（当前 `deploy/docker-compose.yml:195` 默认 `dev`）。
2. **P2-8（限流键）**：`RateLimitFilter.resolveKey` 去掉 `X-Tenant-Id`，改用可信身份（JWT subject / API Key 哈希 / IP）；限流键不能由客户端头控制。
3. **QA2-04**：新增按 taskId 的最小推送间隔门控（状态变化/终态立即发，progress/bestScore ≥1000ms）。
4. **QA2-05**：`phase=DONE` 不再以 `RUNNING` 发布；DONE 只允许出现在终态帧（且 `status=SOLVED`、`resultTaskId` 非空）。
5. **QA2-06**：`CalendarResponse` 增 `timezone`，由 `aps.business.time-zone` 投影。
6. **QA2-07**：`RoutingNodeModes` 的 `FIXED` 缺 id 分支由 `BAD_REQUEST` 改 `UNPROCESSABLE_ENTITY`（`42200`）。
7. **QA2-10**：`normalizeLock` 增资源存在性校验（当前租户、`deleted=false`），缺失返回 `42200`；同步补契约 §24.5 第 2 条。
8. **QA2-11**：`freezePlannedTasks` 的 UPDATE 增 `lock_type='FROZEN'`。
9. **QA2-12**：`activate` 后读取 graph 再返回 `nodes/edges`。

> 落地状态（2026-11-04）：1–9 全部已修，逐条代码位置与实测证据见 §1.1/§1.2 判定列及 §5。契约前置已补齐——QA2-07 依据 `contract-freeze.md:1091`、QA2-10 依据 §24.5 第 2 条存在性条款（`:1663`）；QA2-08 仍待契约先行（`INHERIT` 携带 id 的语义未定义），保持**转后续**。

---

## 3. 本轮实际动作

### 3.1 文档

- 新增本文件，作为 17 条 P2 的唯一收敛台账。
- 未修改 `m3-security-scan.md`、`m2-qa-02-defects.md` 正文（保留原始证据与时间戳）。

### 3.2 配置

- 本轮（首轮核查）未改配置。P2-7 虽属配置类可修项，但涉及部署 profile 口径（`APS_SPRING_PROFILES` 默认 `dev`），留待确认后一次改，避免误关 dev 文档或对 prod 无效。
- 后续 2026-11-04 修复批次已按此口径落地：`application.yml` 默认关闭、`application-dev.yml` 显式打开（见 §1.1 P2-7 行证据）。

### 3.3 垃圾文件

已删除两个 Maven Help Plugin 生成的临时文件：

- `aps-service/aps-service-effective-pom`
- `aps-service/aps-service-effective-pom.xml`

删除前置校验：

- `git ls-files -- aps-service/aps-service-effective-pom aps-service/aps-service-effective-pom.xml` → 无输出（未被跟踪）
- 全仓检索 `effective-pom|effective_pom` → 无引用
- 文件头为 `Generated by Maven Help Plugin`

删除前 `git status`：

```text
?? aps-service/aps-service-effective-pom
?? aps-service/aps-service-effective-pom.xml
nothing added to commit but untracked files present
```

删除后 `git status`：

```text
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

### 3.4 验证边界

- 未运行单元测试：P2-1–P2-4 的“已修”判定基于当前源码 + 提交 `8fe9249` 的改动证据；未通过改测试期望值来迁就现状。
- 未修改任何 Java 业务代码、未触碰 OpenAPI YAML、未 commit/push。

---

## 4. 首轮建议优先修、本轮已落地的条目（历史记录）

1. **P2-8 限流键**：当前仍可由客户端轮换 `X-Tenant-Id` 放大额度，属可直接利用的暴力破解缓解削弱；修复是单文件小改，优先级高于其余 P2。→ 已在 2026-11-04 修复批次落地，见 §1.1 P2-8 行。
2. **QA2-11 自动冻结 `lock_type`**：明确违反冻结契约（`contract-freeze.md:1627`），会直接影响甘特锁标识与增量重排的冻结判定；一行 SQL 即可修，回归成本低。→ 已落地，见 §1.2 QA2-11 行。

---

## 5. 2026-11-04 修复批次收口与判定刷新

- 前置：修复批次已收口，`mvn install` 543 tests / 0 failures / 0 errors / 0 skipped。
- 本次刷新只改判定与证据，不改 `m3-security-scan.md`、`m2-qa-02-defects.md` 原文；原编号与原描述全部保留。
- 判定变更汇总（旧 → 新）：

| 编号 | 旧判定 | 新判定 | 依据（代码位置 / 实测证据） |
| --- | --- | --- | --- |
| P2-5 | 需修 | 转后续（代码已修，无 HTTP 取证入口） | `AuditValues` L19-71 嵌套与 JSON 脱敏；6 处直写均已 `redact`；单测通过，HTTP 无可观测入口 |
| P2-7 | 需修 | 已修 | `application.yml` L93-101 默认 false / `application-dev.yml` L32-37 true；dev 200、prod 全 404 |
| P2-8 | 需修 | 已修（sort 子项转后续、上传子项接受） | `RateLimitFilter.resolveKey` 改用 `TenantContext` + 凭据指纹；轮换 header 第 3 次 429/42900 |
| QA2-04 | 需修 | 已修 | `SolveTaskLifecycleService` L82 `PROGRESS_MIN_INTERVAL_MILLIS=1000`；10 帧间隔 1001–1511ms |
| QA2-05 | 需修 | 已修 | `ScheduleService` L400-406 删除落库后 DONE 帧；实测无 RUNNING+DONE 假帧 |
| QA2-06 | 需修 | 已修 | `CalendarResponse` L33 + `CalendarService` L78；实测 `"timezone":"Asia/Shanghai"` |
| QA2-07 | 需修 | 已修 | `RoutingNodeModes` L54-61 改 `UNPROCESSABLE_ENTITY`；FIXED 缺 id → 422/42200 |
| QA2-10 | 需修 | 已修 | `OrderService.normalizeLock` 叠加存在性校验；`RES-NOPE` → 422/42200 `锁定资源不存在: [RES-NOPE]` |
| QA2-11 | 需修 | 已修 | `OrderTaskFreezeRepository` L44 `lock_type='FROZEN'`；落库 `9001 \| FROZEN \| FROZEN \| 1` |
| QA2-12 | 需修 | 已修 | `RoutingController` L163-166 调 `getGraph`；activate 与 GET 均 `nodes=2, edges=1` |

- 未变更判定：P2-1–P2-4（已修）、P2-6（转已知限制）、QA2-08（转后续，契约先行）、QA2-09（转已知限制）。
- 注意：P2-8 主判定从“需修”改为“已修”的依据是限流键这一安全主项；其 sort 子项仍不一致，保留“转后续”，不因主判定翻转而视为已修。
