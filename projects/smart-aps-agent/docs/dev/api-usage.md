# API 使用说明（开发者文档）

> 适用范围：`/api/v1` 全部 REST 端点；契约源文件在 `docs/api/openapi/*.yaml`，冻结说明在 `docs/api/contract-freeze.md`。
> 本文按 2026-11-04 工作区代码核对，重点补充“契约文件未覆盖、但代码已实现”的行为。**不要只依据 OpenAPI 占位描述调用**。

## 1. 基础约定

| 项 | 值 |
|---|---|
| Base URL | `http://<host>:8080/api/v1`（默认端口见 `application.yml`） |
| Content-Type | JSON 端点 `application/json`；导入 `multipart/form-data`；导出 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| 认证 | `Authorization: Bearer <JWT>`（生产必须）；`dev` 模式可另用 `X-API-Key` |
| 链路追踪 | 请求可带 `X-Trace-Id`（8–64 位 `[A-Za-z0-9._-]`），响应头固定回写 `X-Trace-Id` |
| 租户 | **由 JWT claim `tenantId` 决定**；`X-Tenant-Id` 当前只写入日志 MDC，不决定数据过滤 |
| 统一外壳 | `{ "code": 0, "message": "success", "data": ..., "traceId": "..." }` |

## 2. 获取 Token

### 2.1 用户名 / 口令登录（推荐）

`POST /api/v1/auth/login`

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<初始口令>"}'
```

- 校验逻辑：bcrypt 比对；连续 5 次失败锁定 15 分钟（`AuthService`）。
- 成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "<JWT>",
    "tokenType": "Bearer",
    "expiresIn": 28800,
    "userId": "00000000-0000-0000-0000-0000000000a1"
  },
  "traceId": "…"
}
```

- JWT 为 HS256，有效期默认 28800 秒（8 小时），**无 refresh 端点**，到期重新登录。
- `/auth/login` 不受 `ApiSecurityFilter` 认证约束，但受全局限流约束。

### 2.2 dev 初始口令

V1 种子 `users.username='admin'` 的 `password_hash` 为空。仅当**同时满足**以下条件时启动会写入：

1. 激活 `dev` profile（`SPRING_PROFILES_ACTIVE=dev`）；
2. 环境变量 `APS_ADMIN_INIT_PASSWORD` 非空；
3. `admin.password_hash IS NULL`。

生产不会执行初始化。

### 2.3 dev API Key（可选）

需同时满足：`SPRING_PROFILES_ACTIVE=dev`、`aps.security.mode=dev`、`aps.security.dev.api-key-enabled=true`，且 `aps.security.api-keys.write/read` 配置了对应值。

```bash
curl -s http://localhost:8080/api/v1/orders \
  -H "X-API-Key: <read-key>"
```

- 写操作需要 `write` key；读操作可用 `read` key。
- API Key 认证**不设置租户**，数据落到默认租户 `00000000-0000-0000-0000-000000000001`；生产环境请用 JWT。

## 3. 统一响应与错误码

### 3.1 外壳

所有 Controller 返回 `Result<T>`：

```json
{ "code": 0, "message": "success", "data": { }, "traceId": "b0e0…" }
```

- 成功：`code=0`，`message="success"`，HTTP 200（导入预览等业务例外见各端点）。
- 失败：`code` 为 4xxxx/5xxxx，`data=null`，`message` 为对外文案；`5xxxx` 不回显内部细节。

### 3.2 错误码（来源：`ErrorCode`）

| code | 枚举 | 默认 HTTP | 默认文案 |
|---:|---|---:|---|
| 0 | `SUCCESS` | 200 | success |
| 40000 | `BAD_REQUEST` | 400 | 请求参数不合法 |
| 40100 | `UNAUTHORIZED` | 401 | 未认证或凭证已失效 |
| 40300 | `FORBIDDEN` | 403 | 无权访问该资源 |
| 40400 | `NOT_FOUND` | 404 | 资源不存在 |
| 40500 | `METHOD_NOT_ALLOWED` | 405 | 不支持的请求方法 |
| 40900 | `IDEMPOTENCY_CONFLICT` | 409 | 幂等键冲突：相同请求正在处理中 |
| 40901 | `STATE_CONFLICT` | 409 | 资源状态冲突，请刷新后重试 |
| 40903 | `PUBLISHED_CONFLICT` | 409 | 已发布的计划/任务不可回滚或覆盖 |
| 41500 | `UNSUPPORTED_MEDIA_TYPE` | 415 | 不支持的 Content-Type |
| 42200 | `UNPROCESSABLE_ENTITY` | 422 | 业务规则校验未通过 |
| 42201 | `ADJUSTMENT_INFEASIBLE` | 422 | 调整不可行 |
| 42900 | `TOO_MANY_REQUESTS` | 429 | 请求过于频繁，请稍后重试 |
| 50000 | `INTERNAL_ERROR` | 500 | 系统内部错误 |
| 50010 | `SOLVER_NOT_AVAILABLE` | 500 | 求解引擎不可用 |
| 50100 | `NOT_IMPLEMENTED` | 501 | 接口尚未实现 |
| 50300 | `SERVICE_UNAVAILABLE` | 503 | 依赖服务暂不可用 |

补充说明：

- 参数校验失败由 `MethodArgumentNotValidException` 转 `400/40000`，`message` 形如 `字段: 原因; 字段: 原因`；缺失请求体/类型错误转 `400/40000`。
- 路由不存在转 `404/40400`，方法不支持转 `405/40500`，媒体类型不支持转 `415/41500`。
- 限流（默认 100 次/60 秒）由过滤器直接返回 HTTP 429，响应体为 `Result`，`code=42900`；计数维度是 **租户 ID + 调用方指纹**（API Key 哈希 / Bearer 哈希，缺失时用远端地址），不信任客户端自报的 `X-Tenant-Id`。
- **`42201` 在约束 DSL 的校验报告里另有一义**（DSL 语法错误，见 `docs/dev/constraint-dsl.md` §6）。它只出现在 `data.issues[].code`，不会作为外壳 `code` 返回；排产调整失败才使用外壳 `42201`。

## 4. 分页约定

- 查询参数：`page`（从 1 开始，默认 1）、`size`（默认 20，上限 200）、`sort`（`field,asc|desc`，多字段逗号分隔）。
- 越界自动归一化：`page<1→1`，`size<1→20`，`size>200→200`。
- 响应 `PageResult`：

```json
{ "items": [], "page": 1, "size": 20, "total": 0, "totalPages": 0 }
```

- `totalPages` 由构造器按 `(total + size - 1) / size` 重算，调用方不要信任传入值。

## 5. 幂等约定

有两个不同层次的幂等，**语义不同**：

### 5.1 全局请求幂等（`IdempotencyInterceptor`）

- 请求头：`Idempotency-Key`，长度 **8–128**。
- 作用范围：`/api/**` 的 `POST/PUT/PATCH/DELETE`，**排除** `/api/v1/plans/*/dispatch`。
- 首次通过后缓存键 24 小时；**重复键返回 `409/40900`，不会回放首次响应**。
- 存储为进程内 `InMemoryIdempotencyStore`，重启失效；多实例不共享。
- 键作用域使用 MDC 中的 `X-Tenant-Id`（缺省默认租户），与 JWT 租户不是同一来源。

### 5.2 求解任务幂等（`SolveTaskLifecycleService`）

- 同一 `Idempotency-Key` 提交 `POST /solve-tasks` 会返回**既有任务详情**（非 409）。
- 该键额外限制 **最长 64 位**；65–128 位能通过全局拦截器，但会在这里 `400/40000`。
- `solve_task` 上有租户 + 幂等键唯一约束，并发重复提交由数据库兜底。

### 5.3 派工幂等

`POST /api/v1/plans/{planId}/dispatch` 使用确定性 `dispatchId` + `notification_outbox` 唯一索引，重复调用返回既有结果，不使用全局 `Idempotency-Key` 语义。

## 6. Excel 导入 / 导出

### 6.1 导出 xlsx

两种等价写法（`ImportExportController` 同时保留）：

```bash
curl -s -o orders.xlsx \
  http://localhost:8080/api/v1/orders/export \
  -H "Authorization: Bearer $TOKEN"
```

```bash
curl -s -o orders.xlsx \
  http://localhost:8080/api/v1/export/orders \
  -H "Authorization: Bearer $TOKEN"
```

- 响应体为 xlsx 二进制，文件名由 `Content-Disposition` 给出；列顺序与导入模板一致。
- 支持的实体 slug（以 `ImportEntity` 为准）：`orders`、`materials`、`boms`、`routings`、`resources`、`calendars`、`operationTemplates`、`strategies`、`suppliers`、`customers`。注意 `operationTemplates` 为驼峰 slug；不支持单独导入 `bom-items`/`routing-edges`/`inventory`。
- **注意**：导出/模板端点当前只做认证，没有 `@RequiresPermission` 注解；生产接入需确认是否补权限。

### 6.2 导入（预览 → 执行 → 异步）

```bash
# 1) 下载模板（当前真实路径：/{entity}/import/template）
curl -s -o materials-template.xlsx \
  http://localhost:8080/api/v1/materials/import/template \
  -H "Authorization: Bearer $TOKEN"

# 2) 预览
curl -s -X POST "http://localhost:8080/api/v1/materials/import?mode=preview" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Import-Token: 550e8400-e29b-41d4-a716-446655440000" \
  -F "file=@materials.xlsx"

# 3) 确认执行（同一 X-Import-Token）
curl -s -X POST "http://localhost:8080/api/v1/materials/import?mode=execute" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Import-Token: 550e8400-e29b-41d4-a716-446655440000" \
  -F "file=@materials.xlsx"
```

- 权限 `MD_WRITE`；`mode` 支持 `preview`（默认）/`execute`/`async`；`async` 返回 202 + `ImportJobView`，可用 `GET /{entity}/import/jobs/{jobId}` 轮询。
- `X-Import-Token` 可选但**强烈建议**：用于预览/执行配对与重复上传回放，长度 8–128。
- 导入按“行级部分成功”返回 `ImportResult`（成功/失败行数、错误明细）；`execute` 仍然逐行汇总结果。

## 7. 可直接复制的 curl 示例

以下示例假设：已按种子脚本初始化数据库（`scripts/seed-m1-demo.ps1`）。种子订单默认 `DRAFT`，排产前需确认。

```bash
BASE=http://localhost:8080/api/v1
TOKEN="<登录返回的 accessToken>"
```

### 示例 1：登录

```bash
curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<APS_ADMIN_INIT_PASSWORD>"}'
```

### 示例 2：新建订单（写权限 `OM_WRITE`）

`materialCode` 必须是已存在物料；`scripts/seed-m1-demo.ps1` 写入的成品编码为 `MAT-FG-001`（`MAT-FG-002` 为无 ACTIVE BOM 的负例，不可排产）。种子订单已占用 `SO-20261104-0001..0009`，示例改用未占用的单号。

```bash
curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-so-20261104-0101" \
  -d '{
    "orderNo": "SO-20261104-0101",
    "source": "MANUAL",
    "materialCode": "MAT-FG-001",
    "qty": 10,
    "dueDate": "2026-11-20",
    "priority": "NORMAL"
  }'
```

### 示例 3：分页查询订单

```bash
curl -s "$BASE/orders?page=1&size=20&sort=dueDate,asc&status=CONFIRMED" \
  -H "Authorization: Bearer $TOKEN"
```

### 示例 4：确认订单后提交求解

```bash
# 4.1 确认（重复确认幂等）
curl -s -X PUT "$BASE/orders/SO-20261104-0001/confirm" \
  -H "Authorization: Bearer $TOKEN"

# 4.2 提交求解；orderNos 为空表示场景内全部 CONFIRMED 订单
curl -s -X POST "$BASE/solve-tasks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: solve-demo-20261104" \
  -d '{
    "taskNo": "ST-20261104-001",
    "engine": "timefold",
    "orderNos": ["SO-20261104-0001"],
    "parameters": { "timeoutSeconds": 20 }
  }'
```

- 成功返回 `SolveTaskDetail`；异步开启时初始 `status=PENDING/RUNNING`，用 `GET /solve-tasks/{taskId}` 轮询，或订阅 STOMP `/topic/solve-tasks/{taskId}`。
- `parameters` 支持的键：`timeoutSeconds`（默认 60）、`moveThreadCount`（0=引擎默认）、`acceptor`（默认 `LATE_ACCEPTANCE`）、`randomSeed`、`maxSteps`、`unimprovedSecondsSpentLimit`（默认 10）、`direction`（`FORWARD/BACKWARD/BOTTLENECK`）、`useFrozenAssignments`（默认 true）、`softWeights`（8 条软约束权重；传了 `strategyId` 时由策略权重覆盖）。
- 无 `strategyId`、策略权重不合规（非 8 条或合计≠100）、或未传 `softWeights` 时，Timefold **关闭全部软约束，仅保留硬约束**。

### 示例 5：导出订单 xlsx

```bash
curl -s -o orders.xlsx \
  "$BASE/orders/export" \
  -H "Authorization: Bearer $TOKEN"
file orders.xlsx
```

## 8. 常用端点索引（已实现）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/auth/login` | 匿名 | 登录签发 JWT |
| GET | `/health` | 匿名 | 健康检查 |
| GET | `/orders` | 认证 | 分页/筛选 |
| POST | `/orders` | `OM_WRITE` | 新建 DRAFT 订单 |
| PUT | `/orders/{orderNo}/confirm` | `OM_WRITE` | DRAFT→CONFIRMED，幂等 |
| PUT | `/orders/{orderNo}/release` | `OM_WRITE` | CONFIRMED/SCHEDULED→RELEASED |
| POST | `/solve-tasks` | `SE_SOLVE` | 提交求解 |
| GET | `/solve-tasks/{id}` | 认证 | 进度事实源 |
| POST | `/solve-tasks/{id}/cancel` | `SE_SOLVE` | 终态取消幂等 |
| GET | `/solve-tasks/{id}/justifications` | 认证 | 约束归因 |
| POST | `/constraint-dsls/validate` | `SS_READ` | 校验 DSL（不会落库） |
| POST | `/constraint-dsls` | `SS_WRITE` | 创建 DSL 草稿 |
| POST | `/constraint-dsls/{id}/activate` | `SS_WRITE` | 激活版本 |
| POST | `/constraint-dsls/{id}/rollback` | `SS_WRITE` | 回滚到目标版本 |
| GET | `/orders/export` | 认证 | 导出 xlsx（无细粒度权限） |
| POST | `/orders/import?mode=preview\|execute` | 认证 | Excel 导入 |

> 其余主数据（物料/BOM/工艺/资源/日历/工序模板/库存）、计划版本、甘特调整、MRP、审计、用户角色接口均已实现，路径与 `docs/api/openapi/*.yaml` 对齐；`/agent/*`、`/nl/*`、`/what-if/*` 等仍为 `501/50100` 占位端点。

## 9. 调用注意事项

1. **不要假设同步**：`POST /solve-tasks` 的响应结构恒为 `SolveTaskDetail`，执行方式受 `aps.solve.async-enabled` 影响；以 `status` 判断终态。
2. **`strategyId` 才影响软约束**：不传策略时只施加硬约束；约束 DSL 当前不参与求解（详见架构文档 §5.2）。
3. **租户靠 JWT**：多租户调用不要依赖 `X-Tenant-Id`。
4. **幂等键不等于请求 ID**：全局层重复键返回 409，求解层重复键返回既有任务；按端点选择预期。
5. **导出鉴权**：导出/模板仅认证、无权限注解，网关层需自行收敛。
