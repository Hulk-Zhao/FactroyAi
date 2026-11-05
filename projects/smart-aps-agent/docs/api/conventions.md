# API 契约约定（P0-08）

> 生效版本：v0.1（MVP）。状态来源：PRD 第 9.1 节接口清单、`docs/db/schema.md`。
> 机器可读契约：[docs/api/openapi/](openapi/README.md)。Swagger UI：`/swagger-ui.html`。

## 1. 基础约定

| 项 | 约定 |
|---|---|
| 基础路径 | `/api/v1`，版本号只出现在路径，不放在请求头 |
| 媒体类型 | 请求/响应均为 `application/json; charset=utf-8` |
| 字段命名 | 驼峰 `camelCase`，由数据库 `snake_case` 映射而来（如 `order_no` → `orderNo`），不在 API 暴露表结构 |
| 标识 | 主数据用业务编码（`material.code` / `resource.code`）；订单用 `order_no`；BOM/工艺/场景/用户用 UUID；排产任务用 BIGSERIAL `id` |
| 空值 | 未赋值的可空字段返回 `null`；统一返回体的 `data` 字段始终存在 |
| 时间 | 时间点用 ISO-8601 UTC（`2026-11-04T08:00:00Z`），对应 `TIMESTAMPTZ`；纯日期用 `yyyy-MM-dd`（`DATE`） |
| 时区 | 服务端存储与传输一律 UTC；前端按用户时区展示，不做服务端本地化 |
| 链路追踪 | 响应头必带 `X-Trace-Id`，与响应体 `traceId` 一致 |

## 2. 统一返回体

所有 JSON 接口（含错误）返回同一个信封：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "traceId": "56a8a6223cc647898dc11403037d8477"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | integer | `0` 成功；`4xxxx` 客户端错误；`5xxxx` 服务端错误 |
| `message` | string | 面向用户的提示，不包含堆栈、类名、SQL 或内部路径 |
| `data` | any | 成功时为业务数据；失败时为 `null` |
| `traceId` | string | 本次请求链路标识，同时写入响应头 `X-Trace-Id` |

成功统一 HTTP 200（不额外使用 201/204），错误使用下表中的 HTTP 语义码。

## 3. 错误码

| code | HTTP | message 默认值 | 典型场景 |
|---|---|---|---|
| 0 | 200 | success | 成功 |
| 40000 | 400 | 请求参数不合法 | 校验失败、JSON 不可解析、类型不匹配、幂等键格式非法 |
| 40100 | 401 | 未认证或凭证已失效 | 令牌/API Key 缺失、错误或过期 |
| 40300 | 403 | 无权访问该资源 | 角色或租户越权 |
| 40400 | 404 | 资源不存在 | 订单/物料/任务不存在，路由不存在 |
| 40500 | 405 | 不支持的请求方法 | 方法不匹配 |
| 40900 | 409 | 幂等键冲突：相同请求正在处理中 | 重复 `Idempotency-Key` |
| 40901 | 409 | 资源状态冲突，请刷新后重试 | 乐观锁版本冲突、任务状态不可变更 |
| 40903 | 409 | 已发布的计划/任务不可回滚或覆盖 | 仅用于“已执行进度不可倒带”，同一语义、多入口、不得拆码：增量覆盖（C21，场景级）、任务调整（C22，任务级）、计划回滚（C24，场景级）；发布幂等与版本演进发布不得返回本码 |
| 41500 | 415 | 不支持的 Content-Type | 非 JSON 请求体 |
| 42200 | 422 | 业务规则校验未通过 | 语义校验失败（如数量为负、状态流转非法） |
| 42201 | 422 | 调整不可行 | 甘特调整触发行程/依赖/资源冲突（C22） |
| 42900 | 429 | 请求过于频繁，请稍后重试 | 超过限流阈值 |
| 50000 | 500 | 系统内部错误 | 未预期异常 |
| 50010 | 500 | 求解引擎不可用 | Timefold/OR-Tools 异常 |
| 50100 | 501 | 接口尚未实现 | P0 占位契约端点 |
| 50300 | 503 | 依赖服务暂不可用 | 数据库/求解服务不可用 |

规则：

1. `code` 是业务判断依据，HTTP 状态码只作传输语义，不要仅凭 HTTP 200 判断成功；
2. 4xxxx 提示可直接展示给用户；5xxxx 只展示默认信息，细节仅进日志；
3. 新增错误码必须落在对应段内并登记到 `ErrorCode` 与本表。
4. **参数格式与资源存在性的分界（2026-11-04 冻结）**：非法 UUID、非法时间/枚举/类型、请求体必填缺失或值不合法 → **`400/40000`**；格式合法但**路径**指向的资源不存在 → **`404/40400`**。请求体批量（如 `orderNos[]`）中任一元素不存在，按 **`400/40000`** 拒绝整个请求并逐字点名，**不使用 `40400`**——批量语义不是路径寻址。

## 4. 分页

请求（GET 列表）：

| 参数 | 类型 | 默认 | 约束 | 说明 |
|---|---|---|---|---|
| `page` | integer | 1 | ≥1 | 页码，从 1 开始 |
| `size` | integer | 20 | 1–200 | 超过 200 按 200 处理 |
| `sort` | string | 空 | — | `field,asc\|desc`，多字段逗号分隔，如 `dueDate,asc,priority,desc` |

`data` 使用统一分页结构：

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "total": 45,
  "totalPages": 3
}
```

约束：`size` 默认 20、上限 200；`total` 为 64 位整数；非分页列表（甘特图、树、图数据）不使用该结构。

## 5. 幂等

| 项 | 约定 |
|---|---|
| 请求头 | `Idempotency-Key`，8–128 位 `[A-Za-z0-9._:-]` |
| 适用范围 | `POST` / `PUT` / `PATCH` / `DELETE`；GET 不拦截 |
| 生效方式 | 首次请求占用键；相同键重复提交返回 `409 + code=40900` |
| 作用域 | `tenantId + method + path + key`，不同接口/租户互不影响 |
| 保留期 | 24 小时（MVP 进程内存储，重启后失效） |
| 失败重试 | 响应状态 ≥400 时自动释放键，允许客户端用同一键重试 |
| 缺省 | 未携带该头时按非幂等请求处理（兼容期）；生产写接口应携带 |

> MVP 使用 `InMemoryIdempotencyStore`，仅单实例有效。多实例部署前须替换为 Redis 实现（接口 `IdempotencyStore` 不变）。

## 6. 认证与租户

- `Authorization: Bearer <JWT>`（`bearerAuth`）或 `X-API-Key: <key>`（`apiKeyAuth`）。
- 租户：`X-Tenant-Id`；未传时使用 DEMO 租户 `00000000-0000-0000-0000-000000000001`。
- **M2 状态（C27）**：按 profile 分级启用，不再是“全部未启用”。
  - `prod`：所有模块强制 JWT（HS256）；缺失/过期 `40100`，签名有效但缺少 `APS_USER` 角色 `40300`。
  - `dev`（默认非 prod）：仅写端点（POST/PUT/PATCH/DELETE）强制认证；`X-API-Key` 直通（写密钥 200，读密钥 `40300`，缺失/错误 `40100`）；GET 放行。
  - `off`：`APS_SECURITY_MODE=off` 全放行，仅本地排障/回归。
  - 校验顺序：TraceId → 认证 → 限流 → 幂等 → Controller。
  - 登录页与令牌签发属 M3-FE-02，M2 的 `/auth/login` 保持 `50100`；联调使用 API Key。
  - 租户过滤由基础设施层强制追加；RBAC 角色权限属 M3。
- 密码、令牌、API Key 禁止出现在日志与审计 `before_value/after_value` 中，脱敏规则见
  [docs/observability.md](../observability.md)。

## 7. 限流

| 项 | 约定 |
|---|---|
| 阈值 | 100 req/min/user（按用户 + 租户维度） |
| 响应头 | `X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset`（UTC 秒级时间戳） |
| 超限 | HTTP 429 + `code=42900` |
| M2 状态 | **已启用**（C27，单实例内存令牌桶）；`off` 模式默认关闭，多实例需换 Redis |
| 计数维度 | `tenantId + 调用方标识`（JWT `sub` 或 API Key 指纹）；认证失败不消耗额度 |

## 8. traceId 与可观测性

- 入口读取 `X-Trace-Id`，仅接受 `[A-Za-z0-9._-]{8,64}`；缺失或非法时由服务端生成。
- 响应头与响应体 `traceId` 同值；异步任务与求解回调用 `TraceContext.wrap(...)` 透传。
- 日志字段（`traceId`/`tenantId`/`durationMs`）与脱敏要求见 [docs/observability.md](../observability.md)。

## 9. OpenAPI 分组

| 分组 | 路径前缀 | 内容 | 静态契约 |
|---|---|---|---|
| `materials` | `/materials` `/boms` `/resources` `/calendars` `/mrp` | 物料与主数据 | [materials.yaml](openapi/materials.yaml) |
| `process` | `/operation-templates` `/routings` | 工序模版、工艺路线 | [materials.yaml](openapi/materials.yaml)（静态契约暂共用，待拆 `process.yaml`） |
| `orders` | `/orders` `/strategies` `/sandboxes` | 订单、策略、场景 | [orders.yaml](openapi/orders.yaml) |
| `solve` | `/solve-tasks` `/agent` | 求解任务、Agent | [solve.yaml](openapi/solve.yaml) |
| `gantt` | `/gantt` `/tasks` `/plans` | 甘特图、调整、发布/回滚 | [gantt.yaml](openapi/gantt.yaml) |
| `system` | `/health` `/auth` `/users` `/audit-logs` `/configs` | 系统管理 | [system.yaml](openapi/system.yaml) |

**`OpenApiConfiguration` 分组规则（照抄）**：

```java
// process：工序模版 + 工艺路线
GroupedOpenApi.builder()
        .group("process")
        .pathsToMatch("/api/v1/operation-templates/**", "/api/v1/routings/**")
        .build();

// materials：必须移除 "/api/v1/routings/**"，避免与 process 重复
GroupedOpenApi.builder()
        .group("materials")
        .pathsToMatch("/api/v1/materials/**", "/api/v1/boms/**", "/api/v1/resources/**",
                "/api/v1/calendars/**", "/api/v1/mrp/**")
        .build();
```

- 分组名固定为 `process`；`/v3/api-docs/process` 必须能看到 `/operation-templates/**` 与 `/routings/**`。
- `materials` 分组不得再包含 `/routings/**`；否则同一端点在两个分组重复出现。
- 运行时文档：`/v3/api-docs/{group}`；聚合文档：`/v3/api-docs`；UI：`/swagger-ui.html`。
- 每个操作在描述中标注 `状态：已实现` 或 `状态：占位`；占位端点当前返回 `501 + code=50100`，**不可当作可用功能**。
- 静态 YAML 为评审基线，代码注解为运行时视图；两者冲突时以本文件 + YAML 为准并回补注解。

## 10. 兼容与变更

- 路径或字段删除、重命名、类型收窄均属破坏性变更，必须升级 `/api/v2`。
- 新增可选字段、新增端点、新增错误码为兼容变更。
- 弃用字段在 YAML 中标记 `deprecated: true`，至少保留一个 MVP 迭代。
- 契约变更须同步更新 `docs/api/openapi/*.yaml`、Swagger 注解与 `docs/db/schema.md` 的命名映射。
