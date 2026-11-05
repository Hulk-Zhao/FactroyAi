# 用户操作手册（M3-QA-05）

> 适用对象：APS 计划员、工艺工程师、系统管理员。
> 适用制品：`aps-admin` fat jar（M3 线）。
> 唯一事实来源：`docs/api/contract-freeze.md`（C1–C41）、`docs/api/openapi/*.yaml`、`docs/api/conventions.md`。
> 本文不包含任何未在契约或代码中核实过的端点；无法确认的字段一律标注「未验证」。
> 编写日期：2026-11-04。

## 0. 阅读约定与状态图例

### 0.1 状态图例

| 标记 | 含义 | 使用建议 |
|---|---|---|
| ✅ 已实现 | OpenAPI `x-implementation-status: implemented`，且代码有对应控制器 | 可按本手册操作 |
| ◐ 部分实现 | OpenAPI `partial`：代码已落地，但**未做制品级 HTTP 验收** | 先在测试环境小流量验证 |
| ⚠ 状态漂移 | OpenAPI 仍标 `placeholder`，但代码已实现（`/auth/login`、`/users`、`/audit-logs`、`/sandboxes`、订单写操作等） | **不得对外承诺可用**；需制品级验收并把 yaml 翻牌后才算正式能力 |
| ⛔ 契约占位 | OpenAPI `placeholder` 且代码返回 `HTTP 501 + code=50100` | 不可用，本手册不提供操作步骤 |

> 判定依据：`docs/api/openapi/*.yaml` 的 `x-implementation-status` 与 `aps-web` 控制器映射。两者不一致时按更保守的一侧执行，并标记为「状态漂移」。

### 0.2 通用约定

- Base URL：`/api/v1`。
- 成功统一 HTTP 200，响应体为 `Result{code,message,data,traceId}`；`code=0` 表示成功，不能只看 HTTP 200（`docs/api/conventions.md` §3）。
- 分页响应的 `data` 为 `PageResult{items,page,size,total,totalPages}`；`size` 默认 20、上限 200（非分页结构如甘特、树、图不使用）。
- 时间格式为 ISO-8601；排程业务口径按 `APS_BUSINESS_TIME_ZONE`（默认 `Asia/Shanghai`），不要用浏览器本地时区判断。
- 幂等：请求头 `Idempotency-Key`，8–128 位 `[A-Za-z0-9._:-]`；发布、沙箱 apply、计划下发必填。
- 认证方式见 §1.1。

### 0.3 错误码（仅列契约表内确有定义的）

| code | HTTP | 含义（契约默认 message） | 本手册典型场景 |
|---|---|---|---|
| 40000 | 400 | 请求参数不合法 | 必填缺失、枚举/时间/UUID 非法、幂等键格式非法 |
| 40100 | 401 | 未认证或凭证已失效 | API Key/JWT 缺失、错误、过期 |
| 40300 | 403 | 无权访问该资源 | 读键调用写端点、角色/权限不足、历史日历只读 |
| 40400 | 404 | 资源不存在 | 物料编码/订单号/任务/计划不存在 |
| 40500 | 405 | 不支持的请求方法 | 方法不匹配 |
| 40900 | 409 | 幂等键冲突：相同请求正在处理中 | 重复 `Idempotency-Key`；业务唯一键重复（如物料 `code`） |
| 40901 | 409 | 资源状态冲突，请刷新后重试 | 乐观锁 `version` 不一致 |
| 40903 | 409 | 已发布的计划/任务不可回滚或覆盖 | 任务已 IN_PROGRESS/COMPLETED、计划回滚遇已执行任务 |
| 41500 | 415 | 不支持的 Content-Type | 非 JSON 请求体 |
| 42200 | 422 | 业务规则校验未通过 | 数量为负、状态流转非法、被引用不可删 |
| 42201 | 422 | 调整不可行 | 甘特调整触发行程/依赖/资源冲突 |
| 42900 | 429 | 请求过于频繁，请稍后重试 | 限流（默认 100 次/60 秒） |
| 50000 | 500 | 系统内部错误 | 未预期异常（细节仅日志） |
| 50010 | 500 | 求解引擎不可用 | Timefold/OR-Tools 异常 |
| 50100 | 501 | 接口尚未实现 | 契约占位端点 |
| 50300 | 503 | 依赖服务暂不可用 | 数据库/求解服务不可用 |

---

## 1. 登录与身份

### 1.1 认证现状（⚠ 状态漂移）

**目标**：获得可调用写操作的凭证，并确认当前身份。

**前置条件**：服务已启动且 `GET /api/v1/health` 返回 200；数据库已迁移（部署侧见 `deploy-ops.md`）。

**认证口径（`ApiSecurityFilter` + `ApiSecurityConfigurationValidator` 核实）**

| 模式 | 读端点（GET） | 写端点（POST/PUT/PATCH/DELETE） |
|---|---|---|
| `prod`（**缺省值**） | 需 `Authorization: Bearer <JWT>` | 同左；令牌缺少 `APS_USER` 角色返回 40300 |
| `dev`（仅 `dev` profile 可用） | 无凭证可匿名；带凭证则必须有效 | 需 JWT 或 `X-API-Key`；API Key 仅在 `aps.security.dev.api-key-enabled=true` 且已配置密钥时生效（写键可写、读键 40300） |
| `off`（**只能显式配置**） | 全放行 | 全放行 |

- 启动强校验：`aps.security.jwt.secret` 未配置、长度 <32 字节、或等于历史公开默认值时**直接拒绝启动**；`mode=dev` 但非 dev profile 也拒绝启动；配置了 API Key 但 `mode≠dev` 或非 dev profile 同样拒绝启动。
- 默认限流：100 次/60 秒，超限 `429 + 42900`。
- 登录失败 5 次锁定 15 分钟（代码核实）。
- JWT 有效期默认 28800 秒（8 小时），无 refresh；令牌需含 `APS_USER`。

**操作步骤**

1. 调用 `POST /api/v1/auth/login`。请求体按代码为 `username` / `password`，**字段名未在 OpenAPI 冻结，标「未验证」**。
2. 从响应中取令牌（响应结构**未验证**；`/auth/login` 在 `system.yaml` 中仍标 `placeholder`）。
3. 调用 `GET /api/v1/me` 校验身份（该端点 OpenAPI 未收录，**结构未验证**）。

dev 模式若要用 `X-API-Key` 而非 JWT，必须同时满足：`spring.profiles.active=dev`、`aps.security.mode=dev`、`aps.security.dev.api-key-enabled=true`，且已配置 `APS_SECURITY_API_KEYS_WRITE`（缺一即忽略该请求头）。

**成功判据**

- 登录响应 `code=0` 且能取到令牌；若走 dev API Key，则需先满足上述三条件。
- `GET /me` 返回 `code=0`。
- 后续写操作不再返回 40100。

**常见错误**

| 错误 | 含义与处理 |
|---|---|
| 40100 | 未带/带错凭证；prod 检查 Bearer 与签名密钥 |
| 40300 | 凭证有效但角色不含 `APS_USER`，或 dev 用读键调用写端点 |
| 42900 | 触发限流，等待窗口结束或调大 `aps.security.rate-limit.*` |

> **不得写成可用的原因**：`/auth/login` 在 `docs/api/openapi/system.yaml` 中仍是 `placeholder`；尽管 `AuthController` 已实现，但未做制品级 HTTP 验收。生产环境若需登录，请先完成验收并把 `x-implementation-status` 翻为 `implemented`（本手册不改契约）。

### 1.2 用户、角色与权限（⚠ 状态漂移）

| 操作 | 端点 | 说明 |
|---|---|---|
| 用户列表 | `GET /api/v1/users` | OpenAPI 标 `placeholder`，代码已实现 |
| 新建用户 | `POST /api/v1/users` | 同上 |
| 修改用户 | `PATCH /api/v1/users/{userId}` | OpenAPI 未收录，**未验证** |
| 角色列表 | `GET /api/v1/roles` | 返回 `data={items:[...]}`（代码核实） |
| 新建角色 | `POST /api/v1/roles` | OpenAPI 未收录 |
| 绑定角色权限 | `PUT /api/v1/roles/{roleId}/permissions` | 权限变更生效上限 60 秒（进程内缓存 TTL） |
| 权限点列表 | `GET /api/v1/permissions` | `data` 为**裸数组**，不是 `{items}` |

- 权限点共 18 个（V1 种子）：`MD_READ/MD_WRITE`、`PR_READ/PR_WRITE`、`OM_READ/OM_WRITE/OM_ATP`、`ORDER_FREEZE`、`SS_READ/SS_WRITE`、`SE_SOLVE`、`SCHEDULE_ADJUST/SCHEDULE_PUBLISH`、`SYS_USER/SYS_ROLE/SYS_AUDIT`、`AGENT_EVENT/AGENT_CONFIRM`。
- 内置角色：`ADMIN`、`PLANNER`、`PROCESS_ENGINEER`、`SALES`、`VIEWER`。
- 用户/角色管理需 `SYS_USER` / `SYS_ROLE`；缺失返回 40300。

**成功判据**：`GET /permissions` 的 `data` 长度为 18；`GET /roles` 的 `data.items` 非空；目标用户登录后 `GET /me` 能看到新角色（缓存生效最多 60 秒）。

---

## 2. 主数据

> 本节全部为 ✅ 已实现（`materials.yaml` 标 `implemented`）。

### 2.1 物料

**目标**：维护物料主数据，供 BOM、订单、工艺路线引用。

**前置条件**：具备 `MD_WRITE`。

**操作步骤**

1. 查询：`GET /api/v1/materials?page=&size=`（筛选与排序参数以 `materials.yaml#/materials` 为准）。
2. 新建：`POST /api/v1/materials`，必填 `code`、`name`、`type`、`unit`。
   - `type` 取值：`FINISHED`（成品）、`SEMI`（半成品）、`RAW`（原材料）、`AUX`（辅料）、`PACK`（包装）。
3. 详情：`GET /api/v1/materials/{code}`（路径参数是**物料编码**，不是 UUID）。
4. 修改：`PUT /api/v1/materials/{code}`，请求体必须携带当前 `version`；`code` 不可修改。
5. 删除：`DELETE /api/v1/materials/{code}`（逻辑删除；被 BOM/订单引用时 42200）。

**成功判据**

- 新建/修改返回 `code=0`，`data.version` 从 1 开始递增。
- 列表 `total` 增加/不减少；删除后默认列表不再出现该编码。
- 修改后再次 GET，字段与请求一致。

**常见错误**：40000（必填缺失/枚举非法）、40400（编码不存在）、40900（编码重复）、40901（`version` 过期）、42200（被引用不可删）。

### 2.2 BOM

**目标**：维护父件与子件的用量关系，并激活唯一生产版本。

**操作步骤**

1. 列表：`GET /api/v1/boms`，可按 `parentMaterialCode`（精确）、`status` 过滤，条件间为 AND。
2. 新建：`POST /api/v1/boms`，必填 `parentMaterialCode`、`bomVersion`、`effectiveDate`、`items`；新版本默认为 `DRAFT`；`items` 至少 1 行，每行含 `componentMaterialCode`、`qty`、`scrapRate`（完整字段见 `materials.yaml#/components/schemas/BomCreateRequest`）。
3. 详情：`GET /api/v1/boms/{bomId}`（路径参数是 **BOM UUID**，不是父件编码）。
4. 树：`GET /api/v1/boms/{id}/tree`，默认展开 5 层，超出截断。
5. 激活：`POST /api/v1/boms/{id}/activate`（`Idempotency-Key` 可选，建议传以便重放）。同一父件只允许一个 `ACTIVE` 版本。
6. 删除：`DELETE /api/v1/boms/{bomId}`（逻辑删除头与明细）。

**成功判据**：激活后该 BOM `status=ACTIVE`，同父件旧版本变为非 ACTIVE；详情 `items` 行数与请求一致；树接口 `data` 可展开到 5 层。

**常见错误**：40900（同父件同版本重复）、40901（版本冲突）、42200（BOM 被订单/其他 BOM 引用不可删）、40400（BOM 不存在）。

### 2.3 资源

**目标**：维护产能资源，供日历、工艺路线、排程引用。

**操作步骤**

1. 列表：`GET /api/v1/resources`。`code`/`name` 为模糊匹配且彼此 OR；`type`、`groupId` 为精确匹配并与其他条件 AND。批量取数用 `codes=`（单次 ≤200，忽略分页）。
2. 新建：`POST /api/v1/resources`。`type` 取值 `PRIMARY/SECONDARY/HUMAN/TOOL`；`capacityMode` 取值 `UNIT_PER_HOUR/SECONDS_PER_UNIT/BATCH_SECONDS`。
3. 详情/修改/停用：`GET`、`PUT`、`DELETE /api/v1/resources/{code}`（路径参数为**资源编码**）。修改必须带 `version`。
4. 停用为软删除；被日历或任务引用返回 42200；重复停用幂等返回 200。

**成功判据**：`data.code` 与请求一致；修改后 `version+1`；停用后列表默认不返回，且关联日历/任务仍可查询到历史引用。

**常见错误**：40000、40400、40900、40901（版本冲突）、42200（仍被引用）。

### 2.4 日历

**目标**：维护工厂/资源/班次日历，定义可排程时间。

**操作步骤**

1. 列表：`GET /api/v1/calendars`；`name` 模糊，`type`/`resourceId`/`resourceGroupId` 精确且 AND。
2. 新建：`POST /api/v1/calendars`。`type` 取值 `FACTORY/RESOURCE/SHIFT`；`shifts` 为工厂墙钟时间，按日历时区解释；`holidays` 为节假日。
3. 详情/修改/删除：`GET`、`PUT`、`DELETE /api/v1/calendars/{id}`（路径参数为**日历 UUID**）。
4. 修改必须带 `version`；`calendarVersion` 不可改，新版本请走创建接口。
5. 历史日历（`effectiveTo < 今天`）只读，`PUT` 返回 40300；`effectiveTo` 为空表示长期有效。

**成功判据**：创建后 `GET /calendars/{id}` 能取到完整 `shifts`；修改后 `version+1`；历史日历修改被 40300 拒绝。

**常见错误**：40000、40300（历史日历只读）、40900（版本重复）、40901、42200。

### 2.5 供应商与客户

OpenAPI 未提供供应商/客户的 CRUD 端点，仅提供导入导出实体 `suppliers` / `customers`（见 §11）。**本节不提供直连 CRUD 操作路径**；直接调用不存在的 CRUD 端点属未验证行为。

---

## 3. 工艺

### 3.1 工序模板（✅ 已实现）

**目标**：维护可复用的工序定义，供工艺路线节点引用。

**操作步骤**

1. 列表：`GET /api/v1/operation-templates`；`code`/`name` 模糊且 OR，`resourceType` 精确且 AND。
2. 新建：`POST /api/v1/operation-templates`，`code` 与 `templateVersion` 由请求体指定；同 `code + templateVersion` 重复返回 40900。
3. 详情：`GET /api/v1/operation-templates/{id}`（路径为 **模板 UUID**；同一 code 可有多个版本，禁止按 code 寻址）。
4. 修改：`PUT /api/v1/operation-templates/{id}`，`code`/`templateVersion` 不可改，必须带 `version`。
5. 停用：`DELETE /api/v1/operation-templates/{id}`；被 `routing_node.operation_template_id` 引用返回 42200。

**成功判据**：列表能按 `resourceType` 过滤；详情返回完整模板；修改后 `version+1`；停用后不再出现在默认列表。

**常见错误**：40000、40400、40900、40901、42200。

### 3.2 工艺路线（⚠ 状态漂移 + 路径差异）

**契约中的路径**：`GET/POST /api/v1/routings`、`GET/PUT /api/v1/routings/{id}/graph`（`materials.yaml` 全标 `placeholder`）。

**代码实际另有**：`GET/PUT/DELETE /api/v1/routings/{id}`、`POST /api/v1/routings/{id}/activate`（OpenAPI 未收录，**未验证**）。

**目标**：维护产品工艺 DAG，供排程展开工序与依赖。

**操作步骤（契约路径，⚠ 未验收）**

1. 列表：`GET /api/v1/routings`，按 `productCode`、`version`（业务版本号）、`status` 过滤。
2. 新建：`POST /api/v1/routings`，工序节点构成 DAG，不允许环。
3. 读图：`GET /api/v1/routings/{id}/graph`。
4. 存图：`PUT /api/v1/routings/{id}/graph`，整体替换 `nodes/edges`；`seqNo` 路线内唯一，边用 `fromSeqNo/toSeqNo`，`dependType` 仅 `FS/SS/FF`；`primaryResourceId` 为资源 UUID；`version` 必填用于乐观锁。

**成功判据（按契约描述，未做制品级验证）**：保存后再次 GET 图与请求一致；有环/重复 seqNo 返回 42200；`version` 过期返回 40901。

**常见错误**：40000、40400、40901、42200。

> **不可当作可用的原因**：`routings` 四个操作在 OpenAPI 中均为 `placeholder`。代码控制器虽已存在，但路径集合与契约不完全一致，且无制品级验收记录。

### 3.3 约束 DSL（◐ 部分实现）

**目标**：以 YAML DSL 表达额外约束，校验、版本化、激活与回滚。

**操作步骤**

1. 只校验不落库：`POST /api/v1/constraint-dsls/validate`，返回 `valid` 与逐条问题（422xx）。
2. 创建版本：`POST /api/v1/constraint-dsls`（校验通过后写入 `DRAFT`，YAML 规范化为 `constraints` 数组）。
3. 版本列表：`GET /api/v1/constraint-dsls?code=`（版本号倒序）。
4. 单版本：`GET /api/v1/constraint-dsls/{id}`。
5. 激活：`POST /api/v1/constraint-dsls/{id}/activate`。
6. 回滚：`POST /api/v1/constraint-dsls/{id}/rollback`。

**成功判据**：`validate` 返回 `valid=true` 且问题列表为空；创建后版本号递增；激活后列表中出现当前激活版本。

**常见错误**：40000（YAML 不可解析/结构非法）、40400、40901、42200。

> 状态为 `partial`：代码已落地，**未做制品级 HTTP 验收**。

### 3.4 策略（◐ / ⚠ 混合）

| 操作 | 端点 | 契约状态 |
|---|---|---|
| 策略列表 | `GET /api/v1/strategies` | ⚠ `placeholder`（代码已实现） |
| 新建策略 | `POST /api/v1/strategies` | ⚠ |
| 克隆策略 | `POST /api/v1/strategies/{id}/clone` | ⚠ |
| 策略详情 | `GET /api/v1/strategies/{id}` | ◐ `partial` |
| 更新策略 | `PUT /api/v1/strategies/{id}` | ◐ |
| 导出策略 | `GET /api/v1/strategies/{id}/export` | ◐ |
| 导入策略 | `POST /api/v1/strategies/import`（`Content-Type: application/json`） | ◐ |
| 删除策略 | `DELETE /api/v1/strategies/{id}` | OpenAPI 未收录，**未验证** |

**目标**：维护求解策略（权重、规则开关），供求解任务引用 `strategyId`。

**操作步骤**：先 `GET /strategies` 确认现有策略；`POST /strategies` 新建；`GET /strategies/{id}` 查看详情；导出后可按 `POST /strategies/import` 在另一环境导入。

**成功判据**：新建返回策略 ID；详情字段与创建请求一致；导出 JSON 可被 import 接受。**具体字段集未在手册固化，以 `orders.yaml` schema 为准。**

**常见错误**：40000、40400、40900、40901、42200。

---

## 4. 订单

### 4.1 订单查询（✅ 已实现）

**目标**：查看订单池与交期，为求解选择输入。

**操作步骤**

1. `GET /api/v1/orders`，筛选参数：`status`、`orderNo`、`materialCode`、`customerId`、`dueDateFrom`、`dueDateTo`（条件间 AND）。
2. `GET /api/v1/orders/{orderNo}` 查看单条详情。

**成功判据**：`data.items` 含目标订单；详情的 `orderNo` 与路径一致；`status` 属于 `DRAFT/CONFIRMED/RELEASED/SCHEDULED/IN_PROGRESS/COMPLETED/CANCELLED`。

**常见错误**：40000（时间格式非法）、40400（订单号不存在）。

### 4.2 订单写操作（⚠ 状态漂移）

| 操作 | 端点 | 契约状态 |
|---|---|---|
| 新建订单 | `POST /api/v1/orders` | ⚠ `placeholder`（代码已实现） |
| 更新订单 | `PUT /api/v1/orders/{orderNo}` | ⚠ |
| 确认订单 | `PUT /api/v1/orders/{orderNo}/confirm` | ⚠ |
| 冻结订单 | `PUT /api/v1/orders/{orderNo}/freeze` | ⚠ |
| 调整优先级 | `PUT /api/v1/orders/{orderNo}/priority` | ⚠ |
| 释放订单 | `PUT /api/v1/orders/{orderNo}/release` | ⚠ |

**操作步骤**

1. 新建：`POST /api/v1/orders`，必填 `orderNo`、`materialCode`、`qty`、`dueDate`；默认 `DRAFT` / `UNFROZEN` / `NORMAL` / `MANUAL`。
2. 更新：`PUT /api/v1/orders/{orderNo}`（以路径订单号寻址）。
3. 确认：`PUT /orders/{orderNo}/confirm`，使订单可参与排产。
4. 冻结：`PUT /orders/{orderNo}/freeze`，`freezeStatus` 取值 `UNFROZEN`、`LOCKED_TIME_WINDOW`、`LOCKED_RESOURCE`、`LOCKED_BOTH`（冻结原因字段**未验证**）。
5. 优先级：`PUT /orders/{orderNo}/priority`（优先级取值见 `enums.md`：`NORMAL` 等）。
6. 释放：`PUT /orders/{orderNo}/release`，可从 `CONFIRMED` / `SCHEDULED` 转 `RELEASED`。

**成功判据**：创建后 `GET /orders/{orderNo}` 可查到且状态为 `DRAFT`；确认后状态为 `CONFIRMED`；冻结后 `freezeStatus` 为请求值；释放后状态为 `RELEASED`。

**常见错误**：40000、40400、40900（订单号重复）、40901、42200（状态流转非法/数量为负）。

> **注意**：这些写操作在 `orders.yaml` 中仍标 `placeholder`。代码虽已实现，但**不得对外承诺可用**，需验收后翻牌。

### 4.3 未实现的订单端点（⛔）

| 端点 | 状态 |
|---|---|
| `PUT /api/v1/orders/{orderNo}/start` | ⛔ 占位，返回 501/50100 |
| `POST /api/v1/orders/atp` | ⛔ 占位，返回 501/50100（ATP 归 v0.5 条件卡） |

---

## 5. 求解

### 5.1 提交求解（✅ 已实现）

**目标**：对选中订单执行排程求解，得到任务快照。

**前置条件**：订单已 `CONFIRMED`；目标沙箱存在（默认 `BASELINE`，UUID `00000000-0000-0000-0000-000000000401`）。

**操作步骤**

1. `POST /api/v1/solve-tasks`。请求体关键字段：
   - `orderIds`：参与求解的订单 ID 集合；
   - `sandboxId`：目标沙箱；
   - `strategyId` / `constraintDslId`：可选，指定策略与约束 DSL；
   - `engine`：`TIMEFOLD` / `ORTOOLS` / `RULE_BASED`；
   - `parameters`：`timeLimitSeconds`（默认 60）、`unimprovedSecondsSpentLimit`、`moveThreadCount`；
   - `Idempotency-Key`：可选。
2. 从响应取 `solveTaskId`。
3. 轮询 `GET /api/v1/solve-tasks/{id}`，直到 `status` 进入终态。

**成功判据**

- 立即返回 `code=0` 且含 `solveTaskId`；`status` 可能为 `PENDING` 或 `RUNNING`（**不要假设同步完成**）。
- 终态为 `SOLVED` 时 `tasks` 返回该沙箱当前任务快照，`scoreHard`/`scoreSoft` 可读。
- 终态为 `TIMEOUT` / `FAILED` / `CANCELLED` 时，`errorCode`/`errorMessage` 有值。
- `metrics` 内 `null` 值会**原样输出**（不是被省略）。

**常见错误**：40000（订单 ID 非法/为空）、40300（无 `SE_SOLVE`）、40400（沙箱/策略不存在）、40903（增量场景含已执行任务）、42200（齐套/业务校验失败）、50010（引擎异常）、42900（限流）。

### 5.2 取消、归因、增量（◐ 部分实现）

| 操作 | 端点 | 状态 |
|---|---|---|
| 取消求解 | `POST /api/v1/solve-tasks/{id}/cancel` | ◐ |
| 归因列表 | `GET /api/v1/solve-tasks/{id}/justifications` | ✅ |
| 增量重排 | `POST /api/v1/solve-tasks/incremental` | ◐ |

- 取消仅对 `PENDING`/`RUNNING` 有意义；已终态再取消按状态冲突处理（40901）。
- 归因返回结构化 justification（C19）。
- 增量重排复用基准解配置，写新的 `solve_task` 与新 `DRAFT` 计划版本；场景含已执行任务返回 40903，齐套未通过返回 42200。

### 5.3 求解生命周期与重试

- 状态：`PENDING → RUNNING → SOLVED / FAILED / TIMEOUT / CANCELLED`。
- 重试仅针对 `ENGINE_ERROR`、`TIMEOUT`、`SOLVER_UNAVAILABLE`；首次 + 1 次重试（`APS_SOLVE_RETRY_MAX_RETRIES`，默认 1），固定间隔 30 秒（`APS_SOLVE_RETRY_DELAY_SECONDS`）。`retryCount` 在详情中可读。
- 重试耗尽后失败返回 50010。

---

## 6. 甘特

**目标**：按订单或资源视角查看已排任务的时间分布。

**前置条件**：求解已产出任务；或存在历史计划快照。

**操作步骤**

1. 订单视角：`GET /api/v1/gantt/order?from=<ISO>&to=<ISO>&sandboxId=<UUID>`。
2. 资源视角：`GET /api/v1/gantt/resource?from=<ISO>&to=<ISO>&sandboxId=<UUID>`。
3. `sandboxId` 缺省为 `BASELINE`；`from`/`to` 为必填时间窗。

**成功判据**

- 订单视角返回 `orderGroups` 与 `tasks`；
- 资源视角返回 `resourceGroups` 与 `resources`；
- 同一 `sandboxId` 下两种视角的任务集合一致。

**常见错误**：40000（时间窗格式/缺失）、40400（沙箱不存在）、40300（无 `SS_READ`）。

> 两个甘特端点在 `gantt.yaml` 中均标 `implemented`。返回结构以 `gantt.yaml` schema 为准；非分页结构，不使用 `PageResult`。

---

## 7. 人工调整（含 SPLIT / MERGE，◐ 部分实现）

**目标**：在甘特上平移、锁定、冻结、拆分、合并任务，或撤销/重做最近一次调整。

**前置条件**

- 目标任务存在且 `status` 不是 `IN_PROGRESS`/`COMPLETED`（已执行任务返回 40903）。
- 具备 `SCHEDULE_ADJUST`。
- 已从甘特接口拿到任务的当前 `version`（乐观锁）。

**操作步骤**

1. 先 `GET /api/v1/gantt/order` 或 `/gantt/resource` 取任务 `id` 与 `version`。
2. 调用 `POST /api/v1/tasks/{id}/adjust`，请求头 `Idempotency-Key` **必填**。请求体：
   - `operation`：`MOVE` / `LOCK` / `FREEZE` / `UNFREEZE` / `SPLIT` / `MERGE`；
   - `version`：必填，与当前任务版本一致；
   - `dryRun`：可选，`true` 只校验与返回预览，不落库；
   - `reason`：自由文本（`FREEZE` 必须带）。
3. **SPLIT**：提供 `splitQuantities` 数组，长度 2–10，元素之和必须等于原任务数量；源任务保留第 1 段，其余新建任务按比例连续切分。
4. **MERGE**：提供 `mergeTaskIds` 数组，长度 2–10，必须包含路径任务；要求同资源、同工序、资源内连续；目标保留最早开始，数量求和，其余任务软删。
5. 撤销：`POST /api/v1/tasks/adjustments/{adjustmentId}/undo`；重做：`POST /api/v1/tasks/adjustments/{adjustmentId}/redo`。
6. 历史：`GET /api/v1/tasks/{id}/adjustments`。

**成功判据**

- 响应 `data` 为 `TaskAdjustResult{adjustmentId,applied,dryRun,task,conflicts,createdTaskIds,removedTaskIds}`。
- `dryRun=true` 时 `applied=true`、`dryRun=true`、`adjustmentId=null`，数据库无新增调整记录。
- 正式执行后 `applied=true`，`task.version` 递增；SPLIT 的 `createdTaskIds` 非空；MERGE 的 `removedTaskIds` 非空。
- 再次拉甘特，时间/数量与结果一致。

**常见错误**

| code | 场景 |
|---|---|
| 40000 | 缺 `version`、缺幂等键、`splitQuantities` 长度或和非法 |
| 40400 | 任务不存在 |
| 40901 | `version` 过期（他人已调整） |
| 40903 | 任务已 IN_PROGRESS/COMPLETED，或已执行进度不可倒带 |
| 42200 | MERGE 不满足同资源/同工序/连续，或参数语义非法 |
| 42201 | 调整不可行（行程/依赖/资源冲突，message 会列出订单约束与任务约束） |

> 状态为 `partial`：四个端点在 `gantt.yaml` 中标 `partial`，代码已落地但**未做制品级 HTTP 验收**。

---

## 8. 计划发布、回滚、对比与下发

### 8.1 计划列表与详情

| 操作 | 端点 | 状态 |
|---|---|---|
| 计划列表 | `GET /api/v1/plans?sandboxId=<UUID>` | ✅（`sandboxId` **必填**） |
| 计划详情 | `GET /api/v1/plans/{id}` | ◐ |

**成功判据**：列表 `items` 含目标的 `planId`、`versionNo`、`status`（`DRAFT/REVIEW/PUBLISHED/ARCHIVED`）；详情能取到版本与任务快照。

**常见错误**：40000（缺 `sandboxId`）、40400（计划不存在）。

### 8.2 发布（✅ 已实现）

**目标**：把 `DRAFT`/`REVIEW` 计划固化为不可变 `PUBLISHED` 快照。

**操作步骤**

1. 确认 `GET /plans/{id}` 的 `status` 为 `DRAFT` 或 `REVIEW`。
2. `POST /api/v1/plans/{id}/publish`，请求头 `Idempotency-Key` 必填。

**成功判据**：`status=PUBLISHED`；同一 `Idempotency-Key` 重放返回首次结果且 HTTP 200（不是 40903）。

**常见错误**：40000（缺幂等键）、40400、40903（计划不可发布/已归档）、40901。

### 8.3 回滚（✅ 已实现）

**目标**：以历史版本内容新建一个 `PUBLISHED` 版本，而不是原地覆盖。

**操作步骤**

1. `POST /api/v1/plans/{id}/rollback`，请求体 `targetVersionNo` 必填（目标历史版本号）。
2. 确认返回的新计划版本号。

**成功判据**：返回新的 `planId`/版本，内容与被回滚版本一致；旧版本保留。

**常见错误**：40000（缺 `targetVersionNo`）、40400、40903（存在 `IN_PROGRESS`/`COMPLETED` 任务，已执行进度不可倒带）。

### 8.4 多计划对比（✅ 已实现）

**操作步骤**：`POST /api/v1/plans/compare`，请求体 `planIds` 为 2–3 个计划 ID。

**成功判据**：返回 `items`、`recommendedPlanId`；`tieBreak` 固定为 `"SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC"`。

**常见错误**：40000（数量不在 2–3）、40400。

### 8.5 计划下发（⚠ 未验证）

| 操作 | 端点 | 说明 |
|---|---|---|
| 下发 | `POST /api/v1/plans/{id}/dispatch` | `Idempotency-Key` 必填；`targets` 1–50；`channel` 取 `EMAIL/SMS/FEISHU` |
| 下发记录 | `GET /api/v1/plans/{id}/dispatches` | 分页 |

- **契约 C33 有定义，但两个端点未被 6 个 OpenAPI 文件收录**，因此标「未验证」。
- 行为按契约：只写通知 outbox（`QUEUED`），**不真实发送**；调度器默认关闭（见 `deploy-ops.md`）。
- 成功判据（契约口径）：返回 `dispatchId`；记录状态为 `QUEUED`；不会出现真实邮件/短信/飞书消息。

---

## 9. 沙箱（⚠ 状态漂移）

**目标**：在 `WHAT_IF` 沙箱中做假设性排产，比较差异后应用到正式计划。

**操作步骤**

1. 列表：`GET /api/v1/sandboxes`，可选 `type`；默认排除 `ARCHIVED`。返回 `SandboxSummaryView{id,name,type,parentSandboxId,expiresAt,version,createdAt}`。
2. 新建：`POST /api/v1/sandboxes`，`name` 长度 1–128，`type` 默认 `WHAT_IF`。
3. 应用：`POST /api/v1/sandboxes/{id}/apply`，`Idempotency-Key` 必填，需 `SCHEDULE_PUBLISH`；返回 `SandboxApplyResultView{sandbox,plan}`。
4. 对比：`GET /api/v1/sandboxes/{id}/compare?targetId=<UUID>`，返回 `SandboxCompareView{sandboxId,targetId,dueDateDeltaMinutes,utilizationDelta,setupCountDelta,addedTaskCount,removedTaskCount,movedTaskCount,changedTasks,truncated}`。

**前置条件**：`BASELINE` 沙箱存在（V1 种子 UUID `00000000-0000-0000-0000-000000000401`）。

**成功判据**：新建后列表出现该沙箱；apply 后生成本文计划（`plan` 字段非空）；compare 的 `added/removed/moved` 计数与甘特差异一致；`truncated=true` 表示差异明细被截断，需缩小时间窗后重查。

**常见错误**：40000（`name` 超长/缺幂等键）、40400、40903、40901、40300（无 `SCHEDULE_PUBLISH`）。

> **不可当作可用的原因**：四个沙箱端点在 `orders.yaml` 中仍标 `placeholder`；代码控制器已存在，但未做制品级 HTTP 验收。

---

## 10. 审计日志（⚠ 状态漂移）

**目标**：按动作、资源、操作人、时间检索不可篡改的审计记录。

**前置条件**：具备 `SYS_AUDIT`（缺失返回 40300）。

**操作步骤**

1. `GET /api/v1/audit-logs`。
2. 过滤参数：`action`（事件类型）、`resourceType`、`resourceId`、`userId`、`from`、`to`（ISO-8601 `OffsetDateTime`）；区间为 `[from, to)`，条件间 AND。
3. 排序：`sort` 仅支持 `createdAt,asc|desc`。

**成功判据**：`data.items` 非空且每条含动作、资源、操作人、时间；`from`/`to` 边界与预期一致（`to` 时刻的记录不返回）。

**常见错误**：40000（时间格式非法/排序字段非法）、40300。

> `system.yaml` 中 `/audit-logs` 仍标 `placeholder`，代码控制器已实现（`AuditLogController`），属状态漂移。

---

## 11. 导入导出（◐ 部分实现）

**支持实体（10 个 slug）**：`materials`、`boms`、`resources`、`calendars`、`operationTemplates`、`routings`、`orders`、`strategies`、`suppliers`、`customers`。

### 11.1 下载模板

`GET /api/v1/{entity}/import/template`（兼容 `GET /api/v1/import/{entity}/template`）。

**成功判据**：返回文件流，列头与实体字段一致。

### 11.2 导入

`POST /api/v1/{entity}/import`（兼容 `POST /api/v1/import/{entity}`），`multipart/form-data`：

- 字段 `file`（Excel）；
- `mode`：`preview` / `execute` / `async`；
- 请求头 `X-Import-Token`：8–128 位，必填。

**限额**：文件 ≤ 5MB、≤ 5000 行、最多返回 200 条错误、预览最多 20 行。

**操作步骤**

1. 先 `mode=preview` 上传，检查 `errors` 与 `preview`。
2. 修正后 `mode=execute` 提交，返回 `ImportResult{entity,mode,totalRows,validRows,invalidRows,inserted,updated,skipped,committed,replayed,errors,preview}`。
3. 大文件用 `mode=async`：返回 **HTTP 202**，`data` 为裸 `ImportJobView`；用 `GET /{entity}/import/jobs/{jobId}` 轮询；必要时 `POST /{entity}/import/jobs/{jobId}/cancel`（202）。

**成功判据**

- `preview`：`committed=false`，库内数据无变化。
- `execute`：`committed=true`，`inserted+updated` 与文件有效行一致。
- `async`：`ImportJobView.status` 从 `PENDING`/`RUNNING` 到 `SUCCEEDED`；`processedRows` 递增至 `totalRows`。

**常见错误**：40000（文件超限/行数超限/令牌长度非法/实体 slug 非法）、40300、40400、40900（`X-Import-Token` 重放冲突）、42200（必填列缺失/类型非法）。

### 11.3 导出

`GET /api/v1/{entity}/export`（兼容 `GET /api/v1/export/{entity}`）。**成功判据**：文件流可打开，列头与模板一致，行数与列表一致。

> **状态冲突**：`import-export.yaml` 中模板/导入/导出三个操作标 `partial`，但 `materials.yaml` 中 `/materials/import` 单独标 `placeholder`。**当 `entity=materials` 时导入状态未定，按未验证处理**；其余实体按 `partial` 小流量验证。
> 异步任务两个端点（job 查询/取消）未被 OpenAPI 收录，**未验证**。

---

## 12. 已知限制

1. **500 单 / 60 秒必然超时**：默认 `timeLimitSeconds=60` 下返回 `TIMEOUT`（修复后观测）；500 单 300 秒仍为 `TIMEOUT`（`-6929hard`）。500 单 60s 已移出 M2 门禁，不得作为验收或交付承诺。
2. **容量上限 ≤ 50 单 / 300 任务**：50 单 60s 可 `SOLVED`（约 61.3s，落库 300 行）；8 单稳定 `SOLVED`（10.8–12.1s，48 行）。超过该规模请拆分批次。
3. **单进程求解并发度 1**：单工作线程保证每场景最多 1 个 `RUNNING`；排队容量 32（`APS_SOLVE_QUEUE_CAPACITY`），超出提交会被拒绝而非排队。
4. **`stepCount` / `unimprovedStepCount` 不再输出**：M3 起从 `metrics` 中剔除，不得在 UI 或报表里依赖这两个键；后续如要进度类指标，必须新增独立键名（如 `moveEvaluationCount`）。
5. **`metrics` 中的 `null` 必须原样保留**：实现按 `@JsonInclude(ALWAYS, content=ALWAYS)` 输出，前端不得把 `null` 当作缺失字段做默认值填充或隐藏。
6. **异步导入的 `PENDING` 在进程重启后滞留**：异步 job 无崩溃恢复机制，重启后仍为 `PENDING` 的任务不会自动继续；需要重新提交或调用取消。
7. **`LocalDbRegressionHarness` 仅手工执行**：不进入 CI，不自动运行；发布前需人工在本地库执行并留存结果。
8. **`/permissions` 是裸数组，`/roles` 是 `{items}`**：两处响应包装不一致。前端不能写通用列表解包逻辑，需分别处理（`data` 数组 vs `data.items`）。
9. **`GET /api/v1/health` 只表示进程存活**：不检查数据库，`200` 不等于可服务（详见 `deploy-ops.md`）。
10. **OpenAPI 状态漂移**：`/auth/login`、`/users`、`/audit-logs`、`/sandboxes`、订单写操作、策略部分操作在 yaml 中仍为 `placeholder`/`partial`，但代码已实现；在翻牌前不得对外承诺可用。
11. **限流为单实例固定窗口**：100 次/60 秒按进程计数，多实例部署时实际总配额为「实例数 × 100」。

---

## 附录 A：已核实端点清单

### A.1 `implemented`（36 个）

| 域 | 端点 | 数量 |
|---|---|---|
| 系统 | `GET /health` | 1 |
| 物料 | `GET/POST /materials`、`GET/PUT/DELETE /materials/{code}` | 5 |
| BOM | `GET/POST /boms`、`GET /boms/{bomId}`、`GET /boms/{id}/tree`、`POST /boms/{id}/activate`、`DELETE /boms/{bomId}` | 6 |
| 资源 | `GET/POST /resources`、`GET/PUT/DELETE /resources/{code}` | 4 |
| 日历 | `GET/POST /calendars`、`GET/PUT/DELETE /calendars/{id}` | 4 |
| 工序模板 | `GET/POST /operation-templates`、`GET/PUT/DELETE /operation-templates/{id}` | 5 |
| 求解 | `POST /solve-tasks`、`GET /solve-tasks/{id}`、`GET /solve-tasks/{id}/justifications` | 3 |
| 甘特 | `GET /gantt/order`、`GET /gantt/resource` | 2 |
| 计划 | `GET /plans`、`POST /plans/{id}/publish`、`POST /plans/{id}/rollback`、`POST /plans/compare` | 4 |

### A.2 `partial`（20 个）

| 域 | 端点 | 数量 |
|---|---|---|
| 求解 | `POST /solve-tasks/{id}/cancel`、`POST /solve-tasks/incremental` | 2 |
| 约束 DSL | `POST /constraint-dsls/validate`、`POST /constraint-dsls`、`GET /constraint-dsls`、`GET /constraint-dsls/{id}`、`POST /constraint-dsls/{id}/activate`、`POST /constraint-dsls/{id}/rollback` | 6 |
| 甘特/计划 | `POST /tasks/{id}/adjust`、`POST /tasks/adjustments/{id}/undo`、`POST /tasks/adjustments/{id}/redo`、`GET /tasks/{id}/adjustments`、`GET /plans/{id}` | 5 |
| 策略 | `GET/PUT /strategies/{id}`、`GET /strategies/{id}/export`、`POST /strategies/import` | 4 |
| 导入导出 | `GET /{entity}/import/template`、`POST /{entity}/import`、`GET /{entity}/export` | 3 |

### A.3 代码已实现、OpenAPI 未收录（14 个，未验证）

| 端点 | 备注 |
|---|---|
| `GET /me` | 当前身份 |
| `PATCH /users/{userId}` | 修改用户 |
| `GET/POST /roles` | 角色列表/新建 |
| `PUT /roles/{roleId}/permissions` | 角色授权 |
| `GET /permissions` | 裸数组 |
| `POST /plans/{id}/dispatch` | 契约 C33 |
| `GET /plans/{id}/dispatches` | 契约 C33 |
| `GET /{entity}/import/jobs/{jobId}` | 异步导入 |
| `POST /{entity}/import/jobs/{jobId}/cancel` | 异步导入 |
| `GET/DELETE /routings/{id}`、`POST /routings/{id}/activate` | 工艺路线 |
| `DELETE /strategies/{id}` | 策略删除 |

---

## 附录 B：未验证 / placeholder 清单

### B.1 `placeholder`（⛔ 不可用，35 个；其中 25 个代码确有实现属状态漂移，10 个为真占位）

| 域 | 端点 | 依据 |
|---|---|---|
| 系统 | `GET /configs`、`PUT /configs` | `SystemContractController` 抛 `notImplemented()` |
| 物料 | `POST /materials/import` | `materials.yaml` 标 `placeholder`（与通用导入冲突，未验证） |
| 资源 | `POST /resources/{code}/maintenance` | `MaterialsContractController` 占位 |
| 日历 | `GET /calendars/{id}/available` | `MaterialsContractController` 占位 |
| 工艺路线 | `GET/POST /routings`、`GET/PUT /routings/{id}/graph` | `materials.yaml` 全标 `placeholder` |
| MRP | `PUT /inventory`、`POST /mrp/analysis`、`GET /mrp/suggestions` | `materials.yaml` 标 `placeholder` |
| 订单 | `POST /orders`、`PUT /orders/{orderNo}`、`.../confirm`、`.../freeze`、`.../priority`、`.../release`、`.../start`、`POST /orders/atp` | `orders.yaml` 标 `placeholder`（前 6 个为状态漂移） |
| 策略 | `GET/POST /strategies`、`POST /strategies/{id}/clone` | `orders.yaml` 标 `placeholder`（状态漂移） |
| 沙箱 | `GET/POST /sandboxes`、`POST /sandboxes/{id}/apply`、`GET /sandboxes/{id}/compare` | `orders.yaml` 标 `placeholder`（状态漂移） |
| 系统 | `POST /auth/login`、`GET/POST /users`、`GET /audit-logs` | `system.yaml` 标 `placeholder`（状态漂移） |
| Agent | `POST /agent/events`、`POST /agent/nl-to-dsl`、`GET /agent/explain/{taskId}`、`POST /agent/whatif` | `solve.yaml` 标 `placeholder`（代码无实现） |

### B.2 统计口径

- OpenAPI 6 个文件共 **91 个操作**：`implemented` 36、`partial` 20、`placeholder` 35。
- 另有 **14 个**代码已实现但 OpenAPI 未收录的端点，全部标「未验证」。
- 本手册正文只对 `implemented` 给出完整操作路径；`partial` 给出路径并标注「未做制品级验收」；`placeholder` 不写成可用，仅在附录列出。

