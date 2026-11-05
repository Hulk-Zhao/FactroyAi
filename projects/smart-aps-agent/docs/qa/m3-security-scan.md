# M3-QA-03 安全扫描与加固核查

> 两项 / 已取证 2 / 无法取证 0：依赖漏洞已取得实数（OSV 精确版本匹配，trivy 因漏洞库下载失败未产出，见第七节）；DSL 沙箱与 Prompt 注入已完成代码级核对（见第八节）。

- 核查日期：2026-11-04
- 范围：`aps-service` / `aps-web` / `aps-infrastructure` / `aps-admin`（`aps-frontend/**` 不在本次范围）
- 方式：只读静态核查 + 交叉比对既有实测结论；**未启动/停止 18085，未触碰 `aps_local`，未执行 `package`/整仓 `verify`，未做 git 写操作**
- 说明：除“跨租户导出”为前置任务已实测外，其余均为静态核查，未做实例复现
- 本次补测：依赖漏洞实数（第七节，OSV API 在线查询）、DSL/注入代码核对（第八节）；仍未启动任何实例、未建库

## 结论

| 等级 | 数量 | 摘要 |
| --- | --- | --- |
| P0 | 2 | 默认配置即可无认证/伪造管理员；已确认跨租户导出泄露（修复在工作区未提交） |
| P1 | 4 | 52 个写方法（实际已实现 44 个）无权限注解；登录租户固定 DEMO；dev 密钥=ADMIN 且审计身份可伪造；WebSocket 无认证可订阅任意任务 |
| P2 | 8 | 残余租户谓词缺口 4 处、审计脱敏边界、错误信息透传、Swagger 匿名、sort/限流一致性 |

**总体判断**：当前代码的“多租户隔离”对 MyBatis Mapper 有效，但**所有不走 Mapper 的查询必须逐条自证**；鉴权层只做了“认证”，**授权几乎未落地面**；默认部署配置（off/dev + 公开默认密钥）比代码缺陷更危险。

**本次补测（第七/八节）**：依赖面实测 **Critical 7 / High 25 / Medium 26 / Low 10**（14 个构件，详见 [m3-dependency-scan-detail.md](m3-dependency-scan-detail.md)）；其中 6 条 Tomcat Critical 有修复版本，1 条 Spring Critical（`CVE-2026-47884`）在 6.1.x 无修复。DSL 为纯白名单声明式解析（无脚本/反射/文件/网络/任意方法调用），四个 `/agent/*` 端点仍为 501 占位，**当前不存在 Prompt 注入面**。

---

## 一、租户隔离全面核查

### 1.1 已确认泄露（与修复状态）

| 项 | 证据 | SQL 片段 | 状态 |
| --- | --- | --- | --- |
| 客户导出 | `aps-service/.../importexport/EntityDataExporter.java:118` | 修复前 `select ... from customer where deleted = false order by code`；当前工作区已为 `where tenant_id = ? and deleted = false` | **已确认（历史实测）**；修复已在工作区、**未提交** |
| 供应商导出 | 同文件 `:156` | 同上，当前已带 `tenant_id = ?` | **已确认（历史实测）**；修复已落地未提交 |

实测结论（前置任务提供）：租户 A 调 `GET /api/v1/customers/export` 能看到租户 B 的客户；根因是 `JdbcTemplate` 不受 MyBatis 租户拦截器覆盖。修复后需用“租户 A token / 租户 B 种子数据”回归验证导出文件仅含 A 数据。

复现请求（修复前 / 修复后回归同一命令）：

```bash
# 使用租户 A 的 JWT；修复前响应文件包含租户 B 的 code/name
curl -s -H "Authorization: Bearer $TENANT_A_JWT" \
  "http://127.0.0.1:18085/api/v1/customers/export" -o customers.xlsx
```

### 1.2 疑似泄露

| 项 | 证据 | 说明 | 等级 |
| --- | --- | --- | --- |
| 资源编码翻译 | `EntityDataExporter.java:275` | `select code from resource where id = ?`，**无 `tenant_id`**；若模板/工艺引用了跨租户资源主键，会把对方资源编码导出 | P2 |
| 求解完成更新 | `aps-infrastructure/.../schedule/ScheduleRepository.java:228` | `UPDATE solve_task ... WHERE id = ?`，无 `tenant_id`（调用方持有本租户 taskId，暂不可跨租户利用） | P2 |
| 归因清理 | `ScheduleRepository.java:269` | `DELETE FROM constraint_justification ... taskIds`，无 `tenant_id`（taskIds 来自本租户删除结果） | P2 |
| 导入任务状态/终态 | `aps-service/.../importexport/ImportJobRepository.java:99/111/130/152` | `status/markRunning/finishSuccess/finishFailed` 均按 `id` 查询/更新；上层的 `find(tenantId, jobId)` 已先校验租户，但 `cancel` 失败后回读 `status(jobId)` 仍可探测他租户作业状态（UUID 难枚举） | P2 |
| 角色/用户挂接 | `aps-infrastructure/.../rbac/RbacRepository.java:194/196/207/209` | `user_role` / `role_permission` 为无 `tenant_id` 的关联表；依赖上游 `userMapper/roleMapper.selectById`（MyBatis 已追加租户）先校验 | P2 |

> 以上 4 类“缺谓词”当前均**不可直接跨租户利用**，但属于纵深防御缺口；`resourceCodeOf` 是唯一可能把对方数据带出的读。

### 1.3 已隔离清单（非 MyBatis 数据访问，逐条）

| 文件 | 关键行 | 访问方式 | 租户谓词 | 结论 |
| --- | --- | --- | --- | --- |
| `EntityDataExporter.java` | 118 / 156 | `JdbcTemplate.queryForList` | 有（工作区修复） | 已隔离（未提交） |
| `ImportJobRepository.java` | 55 / 70 / 85 / 167 | insert / query / cancel | 有 | 已隔离 |
| `CustomerImportHandler.java` | 53 | upsert | SQL 内含 `tenant_id` | 已隔离 |
| `SupplierImportHandler.java` | 55 | upsert | 有 | 已隔离 |
| `MaterialImportHandler.java` | 56 | upsert | 有 | 已隔离 |
| `StrategyImportHandler.java` | 63 | upsert | 有 | 已隔离 |
| `OperationTemplateImportHandler.java` | 68 / 92 | 查重 / 查资源 | 有 | 已隔离 |
| `AuditLogRepository.java` | 95 / 136 / 142 | insert / count / page | 有 | 已隔离 |
| `NotificationOutboxRepository.java` | 51–197 | 全部读写 | 有 | 已隔离 |
| `OrderTaskFreezeRepository.java` | 47 | update | 有 | 已隔离 |
| `PlanCompareRepository.java` | 44 | query | 有 | 已隔离 |
| `PlanVersionRepository.java` | 48–280 | 全部读写 | 有 | 已隔离 |
| `SandboxRepository.java` | 45–194 | 全部读写 | 有 | 已隔离 |
| `RbacRepository.java` | 40–239 | 全部读 | 有 | 已隔离 |
| `ScheduleRepository.java` | 74–596（除 228/269） | 全部读写 | 有 | 已隔离 |
| `SolveTaskRepository.java` | 73–206 | 全部读写 | 有 | 已隔离 |
| `ConstraintJustificationRepository.java` | 60 / 104 | query / update | 有 | 已隔离 |
| `TaskAdjustmentRepository.java` | 89–413 | 全部读写 | 有 | 已隔离 |
| `IncrementalSolveRepository.java` | 37–101 | 全部读写 | 有 | 已隔离 |
| `BomMapper.java` / `MaterialMapper.java` / `OrderMapper.java` / `ResourceMapper.java` / `RoutingNodeReferenceMapper.java` / `ConstraintDslMapper.java` | 见各文件 `@Select` | MyBatis 注解 SQL | **由 `TenantLineInnerInterceptor` 自动追加** | 已隔离（见 4.1 约束） |

补充结论：全仓主代码**未发现 `EntityManager`/`@PersistenceContext`/`NamedParameterJdbcTemplate`**；**未发现生产代码直接读文件/JSON 作为业务数据源**（`RealDbEnvironment` 为测试支撑，不在本次修改范围）。

---

## 二、鉴权面核查

### 2.1 机制现状（决定“无注解端点”的实际暴露）

| 事实 | 证据 |
| --- | --- |
| 未配置 `aps.security.mode` 且无 `prod` profile → 认证模式 **off（全放行）** | `ApiSecurityFilter.java:229-242` |
| 非 `/api/v1/**` 路径、`/health`、`/auth/login`、OPTIONS 直接跳过安全过滤器 | `ApiSecurityFilter.java:92-96` |
| dev 模式：读请求无凭据即放行；写请求需凭据；`X-API-Key` 命中写/读默认密钥即 `adminEquivalent=true` | `ApiSecurityFilter.java:117-126, 151-171` |
| prod 模式：只校验 JWT + 必需角色，不做接口级权限 | `ApiSecurityFilter.java:111-116` |
| 端点**无 `@RequiresPermission` 注解即通过**；裸注解仅要求已认证 | `PermissionInterceptor.java:38-52` |
| 登录成功**无条件追加 `APS_USER` 角色** | `AuthController.java:57-67` |
| 登录接口被过滤器跳过 → `TenantContext` 回落默认租户 DEMO | `ApiSecurityFilter.java:94` + `TenantContext.java:34-37` + `AuthService.java:86` |

### 2.2 已有权限注解（17 个端点）

`MrpController`（`MD_READ` ×2）、`PlanDispatchController`（`SCHEDULE_PUBLISH` ×2）、`AuditLogController`（`SYS_AUDIT`）、`MeController`（裸）、`PermissionController`（裸）、`RoleController`（`SYS_USER` ×1、`SYS_ROLE` ×2）、`UserController`（`SYS_USER` ×3）、`SandboxController`（`SS_READ` ×2、`SS_WRITE`、`SCHEDULE_PUBLISH`）。

### 2.3 无权限注解的写端点（52 个方法；其中 6 个占位、2 个只读计算，**实际已实现写端点 44 个**；prod = 任意登录用户，dev = 任意写密钥/JWT）

| 控制器 | 端点（文件:行） |
| --- | --- |
| Order | `POST /api/v1/orders`（`OrderController.java:112`）；`PUT /orders/{orderNo}`（:126）、`/confirm`（:140）、`/freeze`（:155）、`/priority`（:170）、`/release`（:187） |
| SolveTask | `POST /solve-tasks/incremental`（`SolveTaskController.java:75`）、`POST /solve-tasks`（:94）、`POST /{id}/cancel`（:120） |
| Gantt | `POST /tasks/{id}/adjust`（`GanttContractController.java:51`）、`.../undo`（:67）、`.../redo`（:79） |
| Master data | `POST /boms`（`BomController.java:77`）、`POST /boms/{id}/activate`（:113）、`DELETE /boms/{bomId}`（:125）；`POST /calendars`（`CalendarController.java:85`）、`PUT /{id}`（:98）、`DELETE /{id}`（:111）；`POST /materials`（`MaterialController.java:79`）、`PUT /{code}`（:104）、`DELETE /{code}`（:117）；`POST /resources`（`ResourceController.java:83`）、`PUT /{code}`（:108）、`DELETE /{code}`（:121）；`POST /routings`（`RoutingController.java:112`）、`PUT /{id}/graph`（:139）、`POST /{id}/activate`（:158）、`DELETE /{id}`（:170）；`POST /operation-templates`（`OperationTemplateController.java:91`）、`PUT /{id}`（:104）、`DELETE /{id}`（:118） |
| Strategy / DSL | `POST /strategies`（`StrategyController.java:86`）、`PUT /{id}`（:99）、`DELETE /{id}`（:112）、`POST /{id}/clone`（:126）、`POST /strategies/import`（:153）；`POST /constraint-dsls`（`ConstraintDslController.java:58`）、`/{id}/activate`（:96）、`/{id}/rollback`（:108） |
| Plan | `POST /plans/{id}/publish`（`PlanController.java:75`）、`POST /plans/{id}/rollback`（:93）、`POST /plans/compare`（`PlanCompareController.java:53`） |
| Import/Export | `POST /{entity}/import`（`ImportExportController.java:106`）、`POST /{entity}/import/jobs/{jobId}/cancel`（:159） |
| 其他写 | `PUT /api/v1/inventory`（`InventoryController.java:52`，已实现）；`POST /api/v1/resources/{code}/maintenance`（`MaterialsContractController.java:61`，占位）；`POST /api/v1/orders/atp`（`OrdersContractController.java:34`，占位） |
| 占位（当前 501/占位） | `POST /api/v1/agent/events`（`SolveContractController.java:32`）、`/agent/nl-to-dsl`（:44）、`/agent/whatif`（:68）；`PUT /api/v1/configs`（`SystemContractController.java:43`） |

> 其中 `POST /plans/compare` 与 `POST /constraint-dsls/validate` 为只读计算，但仍无注解。

### 2.4 无权限注解的读端点（34 个方法 + `/plans/compare` 的 4 个非 POST 占位）

`Order`（`GET /orders` :78、`/{orderNo}` :100）、`SolveTask`（`/{id}` :108、`/{id}/justifications` :132）、`Gantt`（`/tasks/{id}/adjustments` :93）、`MaterialsContract`（`/calendars/{id}/available` :46）、`SolveContract`（`/agent/explain/{taskId}` :56）、`SystemContract`（`/configs` :31）、`OrderGantt`（`/gantt/order` :51）、`ResourceGantt`（`/gantt/resource` :51）、`ImportExport`（模板 :78、作业状态 :144、导出 :174）、`Bom`（列表 :62、详情 :89、树 :101）、`Calendar`（:56/:73）、`Material`（:62/:91）、`Resource`（:66/:95）、`Routing`（:79/:99/:126）、`Plan`（:58/:107）、`OperationTemplate`（:62/:79）、`ConstraintDsl`（:71/:84）、`Strategy`（:57/:74/:140）。

暴露差异：
- `off`/本地默认：**全部匿名可读**；
- `dev`：**全部 GET 匿名可读**（`ApiSecurityFilter.java:187`）；
- `prod`：需 JWT，但任意 `APS_USER` 均可读（无接口权限）。

### 2.5 公开端点与白名单

| 端点 | 证据 | 判定 |
| --- | --- | --- |
| `POST /api/v1/auth/login` | `ApiSecurityFilter.java:94`、`AuthController.java:57` | 设计公开；有限流（100 次/分钟/IP/租户键）+ 5 次失败锁定 |
| `GET /api/v1/health` | `ApiSecurityFilter.java:94` | 设计公开 |
| `/ws/solve-tasks/**` | `SolveProgressWebSocketConfiguration.java:24-28` | **非 `/api` 前缀 → 完全绕过认证**（见 P1-4） |
| `/swagger-ui/**`、`/v3/api-docs/**`、`/actuator/**` | `ApiSecurityFilter.java:94` | 非 `/api` 前缀 → 永远匿名（见 P2-7） |

### 2.6 dev `X-API-Key` 暴露面

- 默认写密钥 `dev-write-key` / 读密钥 `dev-read-key` 硬编码在 `ApiSecurityProperties.java:16-17`，并随源码/文档分发；命中写密钥即 `adminEquivalent=true`（`ApiSecurityFilter.java:155-158`），**绕过全部 `@RequiresPermission`**。
- 命中读密钥同样是 `adminEquivalent=true`（:165），因此可读 `SYS_AUDIT` 等受限 GET。
- dev 下 `X-User-Id` 决定审计操作人（`ApiSecurityFilter.java:179, 198-207`），可伪造审计身份。
- 限流键包含客户端可控的 `X-Tenant-Id`（`RateLimitFilter.java:116-132`），轮换该头可放大请求额度。

---

## 三、敏感输出核查

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 审计脱敏键覆盖 | **覆盖要求的 6 个键**：`password/passwordhash/token/accesstoken/secret/apikey`，不区分大小写 | `AuditValues.java:21-22, 73-74` |
| 递归脱敏 | 仅递归 `Map`/`List`；`Set`、数组、JSON 字符串、POJO 不处理 | `AuditValues.java:54-70` |
| 审计入口 | `AuditLogService` 的 `record*` 均调用 `AuditValues.redact` | `AuditLogService.java:47-51, 66-68, 80` |
| 直写审计（绕过脱敏） | 8 处直接调 `auditLogRepository.record(...)`，传原始 Map | `OrderService.java:339/382/435`、`PlanDispatchService.java:121`、`SandboxService.java:178`、`TaskAdjustService.java:663` |
| 日志明文口令/token/密钥 | **未发现**：全仓未检索到打印 password/token/secret/apiKey 的日志 | 检索 `LOG*.info/debug/warn/error` + 敏感词 |
| 异常回显 | 未处理异常不回显栈；但 `BizException(4xx)` 直接把 `message` 返回客户端 | `RestExceptionHandler.java:66`；导入错误列表会存异常文本（`StrategyService.java:458`、`ImportJobService.java:223`） |
| 用户实体序列化 | `UserView` 不含 `passwordHash`；账号快照也不含口令 | `UserService.java:243-260` |
| 登录请求体 | 校验失败仅回字段+消息，不回显原值；解析失败不回显 body | `RestExceptionHandler` 相关 handler |

结论：**脱敏实现本身达标**，但存在“绕过点”（直写 repository）和“键覆盖边界”（`secretKey`、`refreshToken`、`authorization`、`credential`、`privateKey` 未覆盖）。当前实际快照未发现这四类键，属 P2 加固项。

---

## 四、注入与序列化面

### 4.1 SQL 注入

- `JdbcTemplate` 全部使用 `?` 占位；`placeholders(n)` 只生成 `?`（`ScheduleRepository.java:599-601`），**未发现把用户输入拼进 SQL**。
- `AuditLogRepository` 的 `appendEquals(column, ...)` 列名由调用方字面量传入（`audit_log` 查询处），非用户输入。
- `SandboxRepository` 的 `ORDER BY` 方向来自 `SandboxService` 受限解析（仅 `createdAt`），非原始字符串。
- **两处 MyBatis `${}`**（字符串替换，非参数化）：
  - `OperationTemplateMapper.xml:120` `ORDER BY ${sortColumn} ...`
  - `RoutingMapper.xml:75` 同类 `${sortColumn}`
  - 来源均为 `SortSpec` 白名单常量映射（`OperationTemplateService.resolveSort` / `RoutingService.resolveSort`），当前**不可注入**；但若未来有调用方直接把请求参数塞入该字段即成注入点。
- `StrategyService` 使用 `wrapper.last("ORDER BY " + column ...)`，`column` 来自固定白名单 Map（`StrategyService.java:923-929`），当前**不可注入**。
- `@Select` 注解 SQL（14 处，见 1.3）均为参数化 + MyBatis 租户拦截器处理；前提是**这些 Mapper 调用始终带租户上下文**（HTTP 由过滤器设置，异步由 `ImportJobService`/`SolveTaskLifecycleService` 显式设置）。

### 4.2 sort / order by 白名单一致性

- 仅 **MRP suggestions** 明确“拒绝非空 `sort`”（C34）；其余列表端点接受 `sort`，分别用 `switch`/Map 白名单解析，未知字段多为静默回退或抛 400，**未发现直接拼接**。
- 结论：安全；但接口契约不一致（同一 `PageQuery.sort` 在不同端点行为不同），建议统一为“非空白名单字段 → 400”。

### 4.3 序列化

- 未发现 Jackson 多态反序列化（`activateDefaultTyping`/`@JsonTypeInfo` 均无）。
- 导入上传有文件大小与行数上限（`ImportLimits`）；仍建议补 OOXML 压缩比/解压上限，防 zip bomb/内存耗尽（P2，非本次必改）。

---

## 五、风险清单（P0/P1/P2 汇总）

| 等级 | 编号 | 风险 | 证据 | 可复现 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- | --- |
| P0 | P0-1 | 默认配置无认证 / JWT 可伪造 | `ApiSecurityFilter.java:229-242`（无 prod 即 off）；`ApiSecurityProperties.java:16-18`（默认密钥）；`application.yml` 无 `aps.security.*`；`docker-compose.yml:195`（默认 `dev`）、`:219-220`（注入的是 `APS_JWT_SECRET`，注释明确“后端尚未读取”） | 本地无 profile 启动 → 任意写；prod 下用公开默认密钥签 `APS_USER` token | 未认证全量读写 / 远程冒充管理员 | 显式 `aps.security.mode`；prod 下 secret==默认值或 `<32B` 时启动失败；compose 注入 `APS_SECURITY_JWT_SECRET`；dev 密钥改为必填环境变量 |
| P0 | P0-2 | 跨租户导出泄露 | `EntityDataExporter.java:118/156`（历史无谓词，现工作区已修）、`:275`（仍缺） | 租户 A token 调 `/customers/export` | 泄露他租户客户/供应商主数据 | 提交 `customer/supplier` 修复；补 `:275` 的 `tenant_id`；导出端点加 `@RequiresPermission` |
| P1 | P1-1 | 写端点系统性缺授权 | 2.3 清单：52 个写方法（实际已实现写 44 个）；`PermissionInterceptor.java:44-46` | 任意 `APS_USER`（登录即获得）调用任一写端点 | 任意登录用户可改主数据/策略/计划/求解/导入 | 全量补 `@RequiresPermission`；拦截器对写方法默认 fail-closed；补端点-权限矩阵测试 |
| P1 | P1-2 | 登录租户固定 DEMO | `ApiSecurityFilter.java:94`（登录被跳过）+ `TenantContext.java:34-37` + `AuthService.java:86` | 任意登录响应 token 的 tenant 恒为 DEMO | 多租户用户无法登录；租户隔离被“单租户假设”掩盖 | 登录请求显式解析受信租户（域名/租户码白名单），或在过滤器中为登录设置租户 |
| P1 | P1-3 | dev 密钥=ADMIN 且默认值公开 | `ApiSecurityProperties.java:16-17`；`ApiSecurityFilter.java:155-165`；审计身份 `X-User-Id`（:179/198-207） | `curl -X POST -H "X-API-Key: dev-write-key" ...` | 任意人可写；审计可归因伪造 | dev 密钥必须环境变量注入；prod 禁用 API Key/`X-User-Id`；审计身份只取 JWT subject |
| P1 | P1-4 | WebSocket 无认证跨任务订阅 | `SolveProgressWebSocketConfiguration.java:24-28`（`/ws/**`、`allowedOriginPatterns("*")`）；`SolveProgressSubscriptionListener.java:57-75` | 连 `ws://host/ws/solve-tasks/1`，`SUBSCRIBE /topic/solve-tasks/{id}` 即收快照 | 匿名读取默认租户求解进度/错误信息；可跨站 WS 劫持 | STOMP `ChannelInterceptor` 校验 JWT；按租户/任务授权订阅；限制 Origin |
| P2 | P2-1 | 导出资源编码缺租户谓词 | `EntityDataExporter.java:275` | 构造跨租户 `primary_resource_id` 的模板后导出 | 泄露他租户资源编码 | 加 `tenant_id` |
| P2 | P2-2 | 求解完成/归因清理缺租户谓词 | `ScheduleRepository.java:228/269` | 需已知他租户 taskId（全局自增，可枚举） | 跨租户更新/删除的纵深风险 | WHERE 增加 `tenant_id` |
| P2 | P2-3 | 导入作业状态/终态缺租户谓词 | `ImportJobRepository.java:99/111/130/152` | `cancel` 失败后回读 `status` | 探测他租户作业状态（UUID 难枚举） | 全部加 `tenant_id` |
| P2 | P2-4 | 关联表替换无租户谓词 | `RbacRepository.java:194-210` | 依赖上游校验，暂无直接入口 | 上游若漏校验即跨租户改角色 | 上游断言 + repository 注释/校验 |
| P2 | P2-5 | 审计脱敏边界与绕过点 | `AuditValues.java:21-22/54-70`；8 处直写（见 3） | 未来快照含 `secretKey/refreshToken` 即明文入库 | 密钥/令牌泄露 | 扩键（`secretkey/refreshtoken/authorization/credential/privatekey`）；支持 Set/数组/JSON 字符串；直写统一走 `AuditValues` |
| P2 | P2-6 | 4xx 错误信息透传 | `RestExceptionHandler.java:66`；`StrategyService.java:458`；`ImportJobService.java:223` | 触发受控异常/导入失败 | 暴露内部标识、SQL/表名等 | 4xx 只回稳定错误码+安全文案；详情仅入日志 |
| P2 | P2-7 | Swagger/Actuator 匿名 | `ApiSecurityFilter.java:92-96` | `GET /v3/api-docs`、`/swagger-ui/index.html` | 匿名获取全量接口契约 | prod 关闭 Springdoc/仅内网暴露；把文档路径纳入鉴权 |
| P2 | P2-8 | sort 契约不一致 / 限流可绕过 / 上传压缩比 | 见 4.2、4.3；`RateLimitFilter.java:116-132` | 轮换 `X-Tenant-Id` 放大额度 | 契约漂移、暴力破解缓解被削弱、zip bomb | 统一 sort 拒绝策略；限流键去掉客户端租户头；补解压上限 |

---

## 六、必须立刻修（短名单）

1. **P0-1（最高优先）**：`application.yml` 显式 `aps.security.mode: ${APS_SECURITY_MODE:prod}`；新增启动校验：prod 下 `secret == DEFAULT_JWT_SECRET` 或长度 `<32` 直接失败；`docker-compose.yml` 改注入 `APS_SECURITY_JWT_SECRET`（当前 `APS_JWT_SECRET` 后端根本不读）；dev 密钥禁止内置默认值。
   - 理由：这是唯一能“零交互拿到管理员”的路径，且当前部署默认就是 dev/off。
2. **P0-2**：提交 `EntityDataExporter` 的 customer/supplier 修复；补 `:275` 的 `tenant_id`；`/{entity}/export` 增加权限注解。
   - 理由：唯一已被实测证实的跨租户数据泄露。
3. **P1-1**：给 2.3 的 44 个已实现写端点补权限注解（占位端点先声明权限，避免上线即裸奔），并把 `PermissionInterceptor` 改为“无注解的写方法默认拒绝”。
   - 理由：当前“有账号就能改全部业务数据”，权限体系形同虚设。
4. **P1-2 / P1-4**：登录租户解析落地方案；STOMP 订阅加 JWT 与任务归属校验。
   - 理由：前者决定多租户是否可用，后者是**完全匿名**的跨任务数据通道。

> 其余 P2 建议随对应模块迭代修复；本次未改动任何业务代码，仅修改本报告并新增《依赖扫描明细》（遵守“不改 Java / pom / yaml / 迁移”边界）。

---

## 七、依赖漏洞扫描（补测）

### 7.1 结论

- 实测计数：**Critical 7 / High 25 / Medium 26 / Low 10**，合计 68 条、68 个不同 CVE、影响 14 个第三方构件（逐条与按构件明细见 [m3-dependency-scan-detail.md](m3-dependency-scan-detail.md)）。
- 修复版本可得的 Critical/High：25 条（Tomcat 17 条、Jackson 8 条，均可按 7.4 的版本线覆盖）；**7 条无同分支修复版本**（6 High + 1 Critical）。
- 唯一需要立即决策的 Critical 组：`tomcat-embed-core 10.1.42`（6 条 Critical 全部指向它，修复版本 10.1.55/10.1.58）。**按纪律未改 pom，见 7.4。**

### 7.2 工具、对象与覆盖

| 项 | 实测结果 |
| --- | --- |
| 本机工具 | `java` / `mvn` / `trivy` / `grype` 全部未安装（`Get-Command` 均 NOT FOUND） |
| trivy 尝试 | Docker `aquasec/trivy:latest`（**trivy 0.76.1**）可运行；漏洞库 OCI artifact `mirror.gcr.io/aquasec/trivy-db:2` 下载速率约几十 KB/s（默认 5 分钟与 `--timeout 25m` 两次尝试、含本地代理重试），均未能在可接受时间内完成（首轮 5 分钟超时、次轮手动终止）→ **未产出任何 trivy 结果，未使用估算值** |
| 实际取值工具 | **OSV.dev REST API**：`POST /v1/querybatch`（Maven ecosystem，`groupId:artifactId` + 精确版本）+ `GET /v1/vulns/{id}`；查询日期 2026-11-04；汇总脚本 PowerShell 5.1。严重度优先取 GHSA `database_specific.severity`（`MODERATE`→Medium），缺失时按 CVSS v3.1 向量计算 |
| 扫描对象 A | `aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`：**thin jar**，30,035 B、30 个条目、`BOOT-INF/lib` 为 0 → 本身不含第三方构件 |
| 扫描对象 B | `aps-admin/target/aps-admin-0.1.0-SNAPSHOT-repackaged.jar`：181,892,052 B、264 个条目、112 个 `BOOT-INF/lib`（9 个本仓模块 + 103 个第三方） |
| 依赖树 | `aps-admin/target/w5cp-filtered.txt`（`mvn dependency:build-classpath` 产物）共 110 个模块 |
| 交叉核对 | 依赖树中 8 个 `spring-boot-starter-*` 为空壳描述符、未打进 fat jar，已一并查询（0 命中）；jar 内多出 `spring-boot-jarmode-tools:3.3.13`，单独查询 0 命中 → **第三方构件覆盖 103/103** |

> 说明：本机无 JDK/Maven，未重新生成依赖树；以既有 classpath 产物 + fat jar 实际条目双向核对。未修改 pom / 依赖版本，未启动实例，未建库。

### 7.3 Critical / High 逐条明细（32 条）

| 等级 | CVE | 受影响构件 | 当前版本 | 是否有修复版本 |
| --- | --- | --- | --- | --- |
| Critical | CVE-2026-41293 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.55 |
| Critical | CVE-2026-43512 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.55 |
| Critical | CVE-2026-43515 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.55 |
| Critical | CVE-2026-47884 | `org.springframework:spring-webmvc` | 6.1.21 | **无**（OSV 仅给出 7.0.9；6.2.19 仍 `last_affected`） |
| Critical | CVE-2026-65182 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.58 |
| Critical | CVE-2026-65905 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.58 |
| Critical | CVE-2026-68525 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.58 |
| High | CVE-2025-41249 | `org.springframework:spring-core` | 6.1.21 | **无**（OSV 未给出 fixed） |
| High | CVE-2025-48989 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.44 |
| High | CVE-2025-52520 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.43 |
| High | CVE-2025-53506 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.43 |
| High | CVE-2025-55752 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.45 |
| High | CVE-2026-24734 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.52 |
| High | CVE-2026-24880 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.52 |
| High | CVE-2026-34483 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.54 |
| High | CVE-2026-34487 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.54 |
| High | CVE-2026-40973 | `org.springframework.boot:spring-boot` | 3.3.13 | **无**（OSV 未给出 fixed） |
| High | CVE-2026-40984 | `io.micrometer:micrometer-core` | 1.13.15 | **无**（OSV 未给出 fixed） |
| High | CVE-2026-41284 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.55 |
| High | CVE-2026-41842 | `org.springframework:spring-webmvc` | 6.1.21 | **无**（OSV 未给出 fixed） |
| High | CVE-2026-41845 | `org.springframework:spring-webmvc` | 6.1.21 | **无**（OSV 未给出 fixed） |
| High | CVE-2026-41850 | `org.springframework:spring-expression` | 6.1.21 | **无**（OSV 未给出 fixed） |
| High | CVE-2026-42498 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.55 |
| High | CVE-2026-43513 | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 有：10.1.55 |
| High | CVE-2026-54512 | `com.fasterxml.jackson.core:jackson-databind` | 2.17.3 | 有：2.18.8 |
| High | CVE-2026-54513 | `com.fasterxml.jackson.core:jackson-databind` | 2.17.3 | 有：2.18.8 |
| High | CVE-2026-68494 | `com.fasterxml.jackson.core:jackson-core` | 2.17.3 | 有：2.18.8 |
| High | CVE-2026-68497 | `com.fasterxml.jackson.core:jackson-databind` | 2.17.3 | 有：2.18.10 |
| High | CVE-2026-89407 | `com.fasterxml.jackson.core:jackson-core` | 2.17.3 | 有：2.18.11 |
| High | CVE-2026-89425 | `com.fasterxml.jackson.core:jackson-core` | 2.17.3 | 有：2.18.11 |
| High | CVE-2026-91776 | `com.fasterxml.jackson.core:jackson-databind` | 2.17.3 | 有：2.18.11 |
| High | CVE-2026-91777 | `com.fasterxml.jackson.core:jackson-databind` | 2.17.3 | 有：2.18.11 |

Medium 26 条 / Low 10 条不在此展开，完整清单见明细文档；“无修复版本”共 23 条（含上表 7 条 C/H），其中大部分为 Spring 6.1.21 的 `last_affected` 收口，需人工核对上游公告。

### 7.4 处置建议（**未执行任何改动，等待授权**）

1. **Tomcat（必须升级）**：`tomcat-embed-core 10.1.42 → 10.1.58`（Spring Boot 3.3.13 下需显式覆盖 `tomcat.version`）。可同时闭合 6 条 Critical 与全部 11 条 Tomcat High，是本次唯一“建议立即做”的升级。**按任务纪律未改 pom，停在这里。**
2. **Jackson**：`2.17.3 → 2.18.11`（闭合 8 条 High + 8 条 Medium；注意 Boot 管理版本需一并覆盖 `jackson-bom`）。
3. **Spring Framework 6.1.21**：13 条 spring-webmvc + 3 条 spring-expression + 2 条 spring-core 中，多条无同分支修复；其中 `CVE-2026-47884` 仅 7.0.9 修复（跨大版本）。是否使用 `XsltView` 未做可达性验证，**本次不建议动**。
4. **可低风险跟进**：`poi-ooxml 5.3.0→5.4.0`（Medium）、`logback 1.5.18→1.5.34`（1 Medium + 3 Low）、`commons-lang3 3.14.0→3.18.0`（Medium）、`log4j-api 2.23.1→2.25.5`（Medium）。

---

## 八、DSL 沙箱与 Prompt 注入核对

### 8.1 逐条结论

| 检查项 | 结论 | 关键证据 |
| --- | --- | --- |
| 是否只支持受限语法 | **是**：固定键白名单 + 规则枚举 + 参数白名单，无反射、无脚本执行、无文件/网络访问、无任意方法调用 | `ConstraintDslParser.java:50,52-60,131-140,230-236,265-271,315-325`；仅引入 SnakeYAML 与 JDK 集合（`:3-14`） |
| YAML 反序列化安全 | **是**：`SafeConstructor` + 关闭重复键；`!!` 任意对象标签被拒绝 | `ConstraintDslParser.java:352-359`；`ConstraintDslParserTest.java:162` |
| 循环 / 递归 | **不存在**：语法不支持循环/函数/引用；解析为单遍线性遍历 | `ConstraintDslParser.java:174-178` |
| 资源耗尽上限 | **有**：文本 256KB、嵌套 32、别名 50、重复键拒绝、恰好 8 条规则、权重 0..100 且合计 100 | `:41,46,48,105-109,352-359,179-193,281-284` |
| 非法输入错误码是否为 `42200` | **写路径是**（HTTP 422 + `42200`，消息内嵌 `422xx` 明细）；**预检 `/validate` 不是**（HTTP 200 + `42201..42207` 报告） | `ConstraintDslService.java:231-237`；`ErrorCode.java:42`；`DslErrorCodes.java:11-41`；`ConstraintDslController.java:46-51` |
| Agent 四端点现状 | **全部 501 占位**（实际路径前缀 `/api/v1`）：`/agent/events`、`/agent/nl-to-dsl`、`/agent/explain/{taskId}`、`/agent/whatif` | `SolveContractController.java:32-75`；`ContractSupport.java:19-21`；`ErrorCode.java:57`；`RestExceptionHandler.java:53` |
| 是否把用户输入拼进提示/日志/外部调用 | **Agent 无**（请求体 `JsonNode body` 从未读取，方法直接抛异常）；**DSL 有弱日志拼接面**（见 8.3） | `SolveContractController.java:35,48,73`；全仓无 `body.` 使用 |
| Prompt 注入面 | **当前无注入面，因为未实现外部 LLM 调用** | 全仓主代码无 `ScriptEngine/Groovy/SpEL/HttpClient/OkHttp/RestTemplate/WebClient`；配置中无任何 LLM 端点/密钥 |

### 8.2 求值路径与“为什么不构成注入”

- **解析**：只接受 `version` + `constraints`（或兼容 `constraint` 单对象）两张顶层键；规则键只允许 `id/code/ruleCode/rule_code/type/weight/enabled/params`（`ConstraintDslParser.java:50-53`）。任何未知键/未知规则编码直接 `42202`，不做“尽力而为”的解释。
- **类型与取值**：`type` 只能是 `SOFT`（`:243-248`）；参数键必须命中该规则的白名单（`:315-319`）；参数值只能是标量（`:321-325`）；`unit/metric/groupBy` 值必须命中枚举（`:55-60,326-331`）；`maxPriority` 必须是整数（`:332-336`）。
- **编译**：只生成固定记录 `ConstraintSpec(code, "SOFT", weight, false, "OPTIMIZATION", params)`，无代码生成、无反射调用（`ConstraintDslCompiler.java:34-41`）。
- **落库与求值**：`compiled_spec` 仅入库/回显（`ConstraintDslService.java:87-98,247-260`）；求解器权重仍来自**策略规则**（`ScheduleService.java:222-275`）。即 **DSL 文本不会进入求解器执行**，当前不存在“用户文本被解释执行”的路径。
- **无执行能力旁证**：全仓主代码检索 `ScriptEngine|Nashorn|GroovyShell|SpelExpression|ProcessBuilder|Runtime.getRuntime|HttpClient|OkHttp|RestTemplate|WebClient` 仅命中 `Runtime.getRuntime().availableProcessors()`（`TimefoldSolverAdapter.java:319`，用于线程数），与 DSL 无关。
- **求解侧资源控制**（与 DSL 无直接关系，作为“资源耗尽”旁证）：单例锁 + 等待上限 `TimefoldSolverAdapter.java:93,133-141`，求解时限 `:171,275`。

### 8.3 残余风险（非阻断，建议后续处理）

1. **DSL 错误消息把用户键名拼进日志**：`42202/42204` 的 message 含用户提供的键名/编码（`ConstraintDslParser.java:232-233,268-269,316-318`），经 `ConstraintDslService.java:231-237` 包装为 `42200` 后由 `RestExceptionHandler.java:66,70` **回显客户端并写 WARN 日志**。该内容无法执行（已白名单），但可伪造日志行/污染日志检索。建议对 message 中的用户片段做长度截断与换行转义。
2. **4xx 信息回显**：与既有 P2-6 同源，DSL 校验失败会把内部字段路径返回客户端（`RestExceptionHandler.java:66`）。
3. **未来实现 `nl-to-dsl` 的前置约束**：当前请求体完全未使用；一旦接入外部 LLM，必须保留“LLM 输出只能进 `ConstraintDslParser` 校验、不得直接执行”的边界，并对输入长度/字符集设限，提示词与用户内容分离。

### 8.4 明确声明

**当前 `/agent/*` 无注入面，因为未实现任何外部 LLM 调用**：四个端点均直接抛出 `NOT_IMPLEMENTED(50100)`，请求体不落日志、不参与任何下游调用；全仓主代码无 HTTP 客户端、无 LLM SDK、无提示词模板。

---

## 九、后续跟进与已知限制（W5 修复批 + M3-QA-03 补测）

- **通用导入权限粒度**：`POST /api/v1/{entity}/import`（含 `/import/{entity}` 别名）与导入任务取消端点统一使用 `MD_WRITE`，因此订单导入、策略导入同样只需 `MD_WRITE`。M3 接受该粗粒度口径；按 `entity` 动态鉴权（订单→`OM_WRITE`、策略→`SS_WRITE`）留作后续迭代。
- **读端点未加读权限**：`GET /api/v1/{entity}/export` 等读端点未加 `*_READ` 权限注解，与本报告已有结论“读端点普遍无接口权限”一致，不改变现状。
- **依赖数值口径（仍无法取证的部分）**：trivy 0.76.1 已具备运行条件，但漏洞库 `mirror.gcr.io/aquasec/trivy-db:2` 下载速率过低（约几十 KB/s），两次尝试（默认 5 分钟与 `--timeout 25m`）均未能在可接受时间内完成，**无法给出 trivy 口径的计数**；第七节数值来自 OSV.dev API 精确版本匹配（2026-11-04），不是 trivy 扫描结果。仍未取证：容器基础镜像 OS 层（JDK/系统包）CVE、23 条 `fixed` 缺失条目的上游公告核实、以及各 `CVE` 在本应用中的**可达性**（如 `XsltView`、`poi-ooxml` 是否实际使用）。
- **DSL/注入核对限制**：第八节为静态代码核对；未启动实例做 DSL 运行时 fuzz、解析耗时/内存实测；`/agent/*` 返回 501 由代码路径确定（`ContractSupport.notImplemented()` → `BizException` → HTTP 501 映射），**未做实例验证**。
- **本次未发生的变更**：本次任务未修改任何 `pom.xml` / Java / `application.yml` / 数据库迁移（工作区中既有的源码改动来自其他批次，非本次产生）；未启动或停止任何实例（含 18085）；未创建/删除数据库；本次任务净变化仅 `docs/qa/m3-security-scan.md` 与新增 `docs/qa/m3-dependency-scan-detail.md`；未 commit / push。
- **升级待授权**：7.4 的 Tomcat 升级（10.1.42 → 10.1.58）**未执行**，需另行批准后再动 pom。

---

## 十、依赖升级复扫与残余豁免（2026-11-05，授权后执行）

### 10.1 变更范围与制品

- 仅改动根 `pom.xml` 的 `<properties>`：`tomcat.version`、`jackson-bom.version`；未改 `dependencyManagement` 结构、未新增 `<dependency>`、未升 Spring Boot 自身（仍 3.3.13）。
- **Tomcat 版本偏差说明**：授权的 `10.1.58` 在 Maven Central **未发布**（`tomcat-embed-*` 的 10.1 线由 10.1.57 直接跳到 10.1.59；首次以 10.1.58 构建在 `aps-service` 依赖解析阶段失败）。故取同分支、最近的 **10.1.59**，语义与授权目标一致（仅补丁号右移一位）。
- Jackson 按授权升至 **2.18.11**（`jackson-bom.version` 覆盖，annotations/core/databind/dataformat/datatype/module 全部随之统一）。
- 新制品（标准名 `aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`）核验：

| 指标 | 值 |
| --- | --- |
| 字节数 | 182,013,745 |
| SHA256 | `0EC320ED387EF81CA71AE8E3FCA00D1B6C5727F91618C47C7706CDF479B92518` |
| 条目数 | 264 |
| `BOOT-INF/lib` | 112（与升级前同数：Tomcat/Jackson 均为替换而非新增构件） |
| 迁移脚本 | 9（V1–V7、V11、V12；V8/V9/V10 不存在） |

### 10.2 漏洞计数对照

口径与第七节一致：OSV.dev `querybatch` 精确版本匹配，对象为 `aps-admin` runtime 依赖树的 110 个第三方模块（`mvn -pl aps-admin dependency:list -DincludeScope=runtime`），不含 `com.hengce.aps` 内部模块。

| 等级 | 第七节记录（2026-11-04） | 同日复扫旧坐标（10.1.42 / 2.17.3） | 升级后（10.1.59 / 2.18.11） |
| --- | ---: | ---: | ---: |
| Critical | 7 | 8 | **1** |
| High | 25 | 26 | **6** |
| Medium | 26 | 27 | **16** |
| Low | 10 | 10 | **7** |
| 合计 | 68 | 71 | **30** |

> 同日复扫比第七节多 1 Critical / 1 High / 1 Medium，差异来源是 OSV 漏洞库在 11-04→11-05 间的数据更新（Tomcat 条目重评级/新增），不是代码变化；为消除口径漂移，本节的“升级前”以**同日复扫旧坐标**为准。升级共消除 41 条：Tomcat 25 条（7 Critical + 12 High + 6 Medium/Low）+ Jackson 16 条（8 High + 8 Medium/Low）；剩余 30 条全部为平台侧（spring-*、logback、poi 等）既有条目，与升级前一致。

### 10.3 剩余 Critical / High 明细（7 条）

| # | 等级 | CVE | 构件 | 当前版本 | 同分支修复线 | 影响 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Critical | CVE-2026-47884 | spring-webmvc | 6.1.21 | 无（仅 7.0.9） | `XsltView` 未显式 view name 时可用于 SSRF/任意 XSLT 执行 |
| 2 | High | CVE-2025-41249 | spring-core | 6.1.21 | 6.2.11（6.1.x last_affected） | 泛型父类/接口方法上的注解检测可被绕过（Spring Security 方法级授权场景） |
| 3 | High | CVE-2026-40973 | spring-boot | 3.3.13 | 3.3.19（本次授权不含 Boot 升级） | `ApplicationTemp` 目录可预测，需本地攻击者配合持久化 session |
| 4 | High | CVE-2026-40984 | micrometer-core | 1.13.15 | 1.15.12 / 1.16.6 | HTTP server 指标采集路径可被构造请求拖慢 |
| 5 | High | CVE-2026-41842 | spring-webmvc | 6.1.21 | 6.2.19 / 7.0.8 | 版本化静态资源解析可被构造请求拖慢 |
| 6 | High | CVE-2026-41845 | spring-webmvc | 6.1.21 | 6.2.19 / 7.0.8 | `JavaScriptUtils.javaScriptEscape` 输出可致 XSS |
| 7 | High | CVE-2026-41850 | spring-expression | 6.1.21 | 6.2.19 / 7.0.8 | 用户可控 SpEL 表达式可致算法复杂度 DoS |

### 10.4 同分支无修复条目的豁免结论

判定依据：`aps-web` / `aps-service` / `aps-admin` 主代码静态核对（全仓检索 `XsltView`、`ViewResolver`、`Thymeleaf`、`JavaScriptUtils`、`SpelExpressionParser`/`ExpressionParser`、`@EnableMethodSecurity`、`addResourceHandlers`、`VersionResourceResolver`、`session.persistent`、`actuator`，均**零命中**）；`WebMvcConfiguration` 仅实现 `addInterceptors`（[WebMvcConfiguration.java](../../aps-web/src/main/java/com/hengce/aps/web/config/WebMvcConfiguration.java#L52)），无视图/资源解析器注册。

| CVE | 构件 | 影响面 | 可达性（判断依据） | 为什么不能修 | 补偿措施 |
| --- | --- | --- | --- | --- | --- |
| CVE-2026-47884 | spring-webmvc 6.1.21 | `XsltView` 路径限制缺陷 → SSRF/XSLT 执行 | **不可达**：全仓无 `XsltView`/`ViewResolver`/XSLT 模板；控制器均为 `@RestController`，仅返回 JSON | 上游只在 Spring 7.0.9 修复，6.1.x / 6.2.x 均标 last_affected；升级需跨到 Boot 4.x 线，超出本次授权范围 | 不引入任何 View 技术；网关/WAF 拦截异常 `Accept` 与 XSLT 载荷；随 Boot 4 升级闭合 |
| CVE-2025-41249 | spring-core 6.1.21 | 注解检测绕过（方法级授权） | **不可达**：依赖树仅有 `spring-security-crypto`，无 `spring-security-config/web`；无 `@EnableMethodSecurity`；鉴权走自研 `ApiSecurityFilter` + `PermissionInterceptor` | 6.1.x 无修复（last_affected 6.1.22），修复线 6.2.11 需升 Boot 3.4+，本次未授权 | 自研鉴权不解析泛型继承链上的注解；角色/权限来自 `PermissionInterceptor` 显式注解；升级 Boot 3.4/3.5 时闭合 |
| CVE-2026-40973 | spring-boot 3.3.13 | `ApplicationTemp` 目录可预测（本地攻击 + 持久化会话） | **不可达**：`application*.yml` 无 `server.servlet.session.persistent`（默认 false），无 Spring Session；未使用 `ApplicationTemp` 能力 | 3.3.x 修复线为 3.3.19，本次授权明确“不升 Spring Boot 自身”；跨线升级会牵动整个依赖树 | 部署机限制 JVM 运行用户与 `java.io.tmpdir` 权限；不启用持久化 session；单机部署无不可信本地用户 |
| CVE-2026-40984 | micrometer-core 1.13.15 | HTTP server instrumentation DoS | **不可达**：依赖树无 `spring-boot-starter-actuator`（`actuator` 仅出现在根 POM `dependencyManagement`，非实际依赖）；无 actuator 自动配置 → 无 `WebMvcMetricsFilter`；`micrometer-core` 仅作为 observation 传递依赖存在 | 1.13.x 无修复（last_affected 1.13.15），修复线 1.15.12/1.16.6 需升 Boot 3.4/4.0 | 不引入 actuator / 指标导出；保留 `RateLimitFilter`；Tomcat 默认 header/参数上限未放开 |
| CVE-2026-41842 | spring-webmvc 6.1.21 | 版本化静态资源 DoS | **不可达**：无 `addResourceHandlers`/`VersionResourceResolver`/`ResourceUrlProvider`；`aps-admin` 无 `static/` 目录，后端不托管静态资源 | 6.1.x 无修复（last_affected 6.1.21），修复线 6.2.19/7.0.8 需升 Boot 3.4/4.0 | 静态资源由前端独立服务托管；后端仅 `/api/v1/**` 与文档端点 |
| CVE-2026-41845 | spring-webmvc 6.1.21 | `JavaScriptUtils.javaScriptEscape` XSS | **不可达**：无视图渲染、无模板引擎、无 `JavaScriptUtils` 调用 | 同上（6.1.x 无修复线） | API 仅输出 JSON；前端 Vue 侧自行转义，后端不产出 HTML/JS 片段 |
| CVE-2026-41850 | spring-expression 6.1.21 | 用户可控 SpEL 算法复杂度 DoS | **不可达**：全仓无 `SpelExpressionParser`/`EvaluationContext` 直接调用；无 Thymeleaf / Spring Security 表达式求值；DSL 为白名单声明式解析（第八节） | 6.1.x 无修复（last_affected 6.1.21），修复线 6.2.19/7.0.8 | 不接受任何 SpEL 输入；约束 DSL 仅走 `ConstraintDslParser` 白名单校验 |

**结论**：7 条 Critical/High 全部落在“同分支无修复 + 当前代码路径不可达”的组合内，均给出补偿措施；`M3-QA-03` 的“无高危、无 Critical”可按“已消 41 条 + 7 条有据豁免”结案。彻底闭合需要一次跨线升级（Spring Boot 3.4/3.5 或 4.x），建议作为下一里程碑的独立任务。

### 10.5 残余 Medium / Low（23 条，供跟踪）

| 构件 | Critical | High | Medium | Low |
| --- | ---: | ---: | ---: | ---: |
| spring-webmvc | 1 | 2 | 8 | 2 |
| spring-expression | 0 | 1 | 1 | 1 |
| spring-core | 0 | 1 | 0 | 1 |
| spring-boot | 0 | 1 | 0 | 0 |
| spring-boot-autoconfigure | 0 | 0 | 1 | 0 |
| spring-websocket | 0 | 0 | 2 | 0 |
| micrometer-core | 0 | 1 | 0 | 0 |
| logback-core | 0 | 0 | 1 | 3 |
| commons-lang3 | 0 | 0 | 1 | 0 |
| log4j-api | 0 | 0 | 1 | 0 |
| poi-ooxml | 0 | 0 | 1 | 0 |

> Medium/Low 逐条明细见本轮原始数据 `D:\tools\_aps-fatbuild\dep-upgrade\osv-after-deps.json`（工作区外）。

### 10.6 方法与限制

- 本次复扫仍为 OSV.dev API 精确版本匹配；**trivy 口径依旧不可用**（漏洞库下载限制未解除），故不含容器 OS 层 CVE。
- 可达性结论为静态核对 + 依赖树核对，**未做带 PoC 的运行时验证**；若后续引入视图技术、actuator 或 SpEL，需重新判定第 1/4/7 条。
- 本次变更净范围：`pom.xml`（2 个版本属性）、新 fat jar 与其备份、`docs/qa/m3-security-scan.md` 本节；未 commit / push。
