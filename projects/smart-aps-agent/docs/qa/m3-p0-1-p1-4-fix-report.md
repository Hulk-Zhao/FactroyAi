# P0-1 / P1-4 安全修复报告

## 结论

- **P0-1 已修复**：源码不再内置公开 JWT/API Key 默认值；密钥缺失、过短、使用历史默认值、`dev` 模式未配 `dev` profile 均在启动期 fail-fast；`mode` 缺省为 `prod`，`off` 仅显式配置可开。
- **P1-4 已修复**：`/ws/solve-tasks/**` 的 CONNECT 强制 JWT，SUBSCRIBE 校验任务租户归属；STOMP 快照线程显式注入租户上下文；Origin 收敛为可配置白名单（默认本地前端）。
- **回归**：真库门禁补跑已闭环（`APS_TEST_DB_ENABLED=true` + 临时 PostgreSQL `55433`）：`aps-service` 319 / 0 / 0 / **0**，`aps-web` 53 / 0 / 0 / **0**；上游模块 `aps-common` 19、`aps-infrastructure` 29、`solver-api` 8、`solver-dsl` 19 同为 0 失败 / 0 跳过。

---

## P0-1 双向证据

### ① 不配密钥启动 → 启动失败（原文节选）

```text
INFO c.h.aps.web.filter.ApiSecurityFilter - API 安全模式生效: mode=prod，source=aps.security.mode，activeProfiles=[]
INFO c.h.a.w.c.ApiSecurityConfigurationValidator - 安全配置校验：mode=prod source=aps.security.mode activeProfiles=[]
WARN ... Exception encountered during context initialization - cancelling refresh attempt:
  org.springframework.beans.factory.BeanCreationException: Error creating bean with name
  'apiSecurityConfigurationValidator' ...:
  aps.security.jwt.secret 未配置：请显式设置 APS_SECURITY_JWT_SECRET（至少 32 字节）；
  源码不提供默认密钥，未配置将拒绝启动
ERROR o.springframework.boot.SpringApplication - Application run failed
```

### ② dev profile + 显式密钥 → 启动成功、登录得 token

```text
INFO ApiSecurityFilter - API 安全模式生效: mode=dev，source=aps.security.mode，activeProfiles=[dev]
INFO ApiSecurityConfigurationValidator - 安全配置校验通过：mode=dev jwtSecret=configured(39 bytes) devApiKeys=absent
INFO SolveProgressWebSocketConfiguration - WebSocket Origin 白名单: [http://localhost:5173, http://127.0.0.1:5173]
INFO c.hengce.aps.admin.ApsAdminApplication - Started ApsAdminApplication in 6.33 seconds
```

`POST /api/v1/auth/login` → HTTP 200，返回 `accessToken`（293 字符）；同一 token 访问 `GET /api/v1/permissions` → HTTP 200。

### ③ 旧默认密钥签发的 token → 被拒

```text
GET /api/v1/permissions  Authorization: Bearer <old-default-secret-token>
HTTP 401
{"code":40100,"message":"未认证或凭证已失效","data":null,"traceId":"..."}
```

同一端点换成本次登录 token → HTTP 200。

### ④ `mode` 未配置时的实际行为

显式置空 `--aps.security.mode=` 启动：

```text
API 安全模式生效: mode=prod，source=default(prod)，activeProfiles=[]
安全配置校验：mode=prod source=default(prod) activeProfiles=[]
```

结论：**缺省不是 `off`，而是 `prod`（安全模式开启）；`off` 只能显式配置。**

---

## P1-4 双向证据

### ① 匿名连接 WS → 被拒（ERROR 帧）

```text
CONNECT（无 Authorization）
← ERROR
message:WebSocket 连接未认证：请在 CONNECT 帧携带 Authorization: Bearer <JWT>
content-length:0
```

### ② A 租户 token 订阅 B 租户任务 → 被拒

```text
CONNECT → CONNECTED (user-name: 00000000-...-000000000301, tenant A)
SUBSCRIBE destination:/topic/solve-tasks/9902（任务 9902 属于 tenant B）
← ERROR
message:WebSocket 订阅被拒绝：任务不存在或不属于当前租户
```

### ③ 正常订阅 → 收到进度消息

```text
CONNECT → CONNECTED
SUBSCRIBE /topic/solve-tasks/9901
← MESSAGE
{"taskId":9901,"status":"RUNNING","progress":0.42,"phase":"SOLVING",
 "bestScore":"-123","elapsedMs":167800,"resultTaskId":null,
 "errorCode":null,"message":null}
```

### ④ Origin 白名单

```text
Origin: http://evil.example  → InvalidStatus: server rejected WebSocket connection: HTTP 403
Origin: http://localhost:5173 → CONNECTED（白名单内正常放行）
```

---

## 改动文件

新增：

- `aps-web/src/main/java/com/hengce/aps/web/config/ApiSecurityConfigurationValidator.java`（114 行，启动 fail-fast）
- `aps-web/src/main/java/com/hengce/aps/web/security/StompAuthPrincipal.java`（22 行）
- `aps-web/src/main/java/com/hengce/aps/web/security/SolveProgressWebSocketAuthInterceptor.java`（157 行）

修改：

- `aps-web/.../config/ApiSecurityProperties.java`：移除 JWT/API Key 默认值，新增 `resolvedMode()` 与 `websocket.allowed-origins`
- `aps-web/.../filter/ApiSecurityFilter.java`：缺省 `prod`，启动打印 mode + 来源；dev 拒绝分支补写 401，消除“忽略凭据后静默 200 空包”
- `scripts/local-verify.ps1`：dev 实例统一以启动参数开启 `aps.security.dev.api-key-enabled` 并传入写密钥
- `scripts/verify-se5-justification.ps1`、`scripts/run-m2-perf.ps1`、`scripts/qa/m2-qa-02-p1-regress.ps1`：`X-API-Key` 默认值切到非历史密钥，支持 `APS_DEV_API_KEY` 覆盖
- `docs/guide/deploy-ops.md`：补充 dev 脚本开关的启用方式与默认关闭说明
- `aps-web/.../filter/RateLimitFilter.java`：与认证模式默认对齐
- `aps-web/.../config/SolveProgressWebSocketConfiguration.java`：Origin 白名单 + 入站拦截器
- `aps-web/.../controller/solve/SolveProgressSubscriptionListener.java`：订阅快照显式设置/清理租户上下文
- `aps-admin/src/main/resources/application.yml`：新增 `aps.security.*`（`APS_SECURITY_MODE` / `APS_SECURITY_JWT_SECRET` 等）
- `.env.example`、`deploy/docker-compose.yml`：变量名对齐后端，补齐 mode 与 WS Origin
- `scripts/stack-up.*`、`scripts/stack-bundle.*`：必填变量名从 `APS_JWT_SECRET` 切到 `APS_SECURITY_MODE` / `APS_SECURITY_JWT_SECRET`
- `aps-web/src/test/.../SolveProgressStompTest.java`、`SolveProgressStompTestApplication.java`：带 token 连接，新增匿名/跨租户拒绝用例

`git diff --stat`（仅本任务路径）：

```text
 .env.example                                       |  18 +-
 aps-admin/src/main/resources/application.yml       |  31 ++
 .../aps/web/config/ApiSecurityProperties.java      | 140 ++++++++-
 .../SolveProgressWebSocketConfiguration.java       |  60 +++-
 .../solve/SolveProgressSubscriptionListener.java   |  30 +-
 .../hengce/aps/web/filter/ApiSecurityFilter.java   | 314 +++++++++++----------
 .../com/hengce/aps/web/filter/RateLimitFilter.java |  19 +-
 .../aps/web/solve/SolveProgressStompTest.java       |  97 ++++++-
 .../solve/SolveProgressStompTestApplication.java   |   6 +-
 deploy/docker-compose.yml                          |  10 +-
 scripts/stack-bundle.ps1                           |   2 +-
 scripts/stack-bundle.sh                            |   2 +-
 scripts/stack-up.ps1                               |   5 +-
 scripts/stack-up.sh                                |   4 +-
 14 files changed, 530 insertions(+), 208 deletions(-)
```

> 说明：`ApiSecurityFilter.java` / `ApiSecurityProperties.java` 工作区在本任务开始前已有其他流的未提交改动，上面 diff 含那部分；本任务新增的 3 个文件为 untracked，未计入 `git diff --stat`。

---

## 回归实数（真库门禁补跑，Skipped=0）

环境：临时 PostgreSQL 实例 `127.0.0.1:55433`（避开本机既有 `55432` 实例），
`APS_TEST_DB_ENABLED=true`、`APS_TEST_DB_NAME=aps_test_gate`；`RealDbEnvironment` 自建空库，
Flyway 按 `filesystem:../db/migration` 自动迁移到最新版（schema version 12）。

`mvn -pl aps-service -am test`：

```text
[INFO] Tests run:  19, Failures: 0, Errors: 0, Skipped: 0   (aps-common)
[INFO] Tests run:  29, Failures: 0, Errors: 0, Skipped: 0   (aps-infrastructure)
[INFO] Tests run:   8, Failures: 0, Errors: 0, Skipped: 0   (solver-api)
[INFO] Tests run:  19, Failures: 0, Errors: 0, Skipped: 0   (solver-dsl)
[INFO] Tests run: 319, Failures: 0, Errors: 0, Skipped: 0   (aps-service)
BUILD SUCCESS
```

`mvn -pl aps-web -am test`（上游模块同上，仅列差异模块）：

```text
[INFO] Tests run: 319, Failures: 0, Errors: 0, Skipped: 0   (aps-service)
[INFO] Tests run:  53, Failures: 0, Errors: 0, Skipped: 0   (aps-web)
BUILD SUCCESS
```

真库用例均真实执行，非跳过：`SolveTaskRetryPartialRealDbIntegrationTest` 3/0/0/0、
`SolveTaskRepositoryRealDbTest` 4/0/0/0、`CalendarRealDbIntegrationTest` 5/0/0/0、
`RbacRepositoryTenantIsolationRealDbTest` 2/0/0/0、`ScheduleRepositoryTenantIsolationRealDbTest` 2/0/0/0 等。
全 reactor `Skipped` 均为 0，旧版“45 skipped”结果已作废、不再作为通过证据。

清理：测试后 `DROP DATABASE aps_test_gate` 与 `aps_dev_apikey`；`pg_database` 由 3（`postgres` / `template0` / `template1`）回到 3，净变化 0。
临时实例已 `pg_ctl stop`，端口 `55433`、`18096` 均为 FREE；`18085` 既有实例（PID 30312）仍在监听，未触碰；未读写 `aps_local`，未做 git 写命令、未跑 `package`、无新迁移。

---

## 附带修复：dev 脚本 API Key 开关

P0 收紧后 `aps.security.dev.api-key-enabled` 默认关闭，依赖 `X-API-Key` 的脚本会失效。调用点清单：

| 文件:行 | 使用的头 |
| --- | --- |
| `scripts/verify-se5-justification.ps1:34,70` | `X-API-Key`（POST 写端点） |
| `scripts/run-m2-perf.ps1:43,81` | `X-API-Key`（POST 写端点 + 轮询） |
| `scripts/qa/m2-qa-02-p1-regress.ps1:21,40` | `X-API-Key`（POST 写端点） |
| `scripts/local-verify.ps1:131` | 启动参数传入写密钥（非调用点） |
| `.env.example:72-73`、`deploy/docker-compose.yml:223-224` | 只定义密钥变量，不含开关配置 |
| `docs/guide/deploy-ops.md` §2.2 | 开关说明 |

**只选一种启用方式：启动参数。** 在 `scripts/local-verify.ps1` 启动 dev 后端时传
`-Daps.security.mode=dev -Daps.security.dev.api-key-enabled=true -Daps.security.api-keys.write=<dev 写密钥>`。
理由：`deploy/docker-compose.dev.yml` 只起 PostgreSQL/Redis/MinIO，不包含后端服务，写开关在那里是死配置；
`application-dev.yml` 默认值保持关闭，不在第二处重复配置。

实测（dev 实例 `18096` + 临时库 `aps_dev_apikey`，`POST /api/v1/materials` + `X-API-Key`）：

```text
开关开启：HTTP 200  X-Trace-Id: b5bc3f440d9b4bcca109d1ccad0de653
开关移除：HTTP 401  X-Trace-Id: a632672674b2456cb6cc0835096da652
          {"code":40100,"message":"未认证或凭证已失效","data":null,...}
```

对照中同时修正 `ApiSecurityFilter` 的 dev 拒绝分支：原先忽略凭据后直接返回，响应为 200 空包（`Content-Length: 0`），现已统一落 401；prod 路径未改。

---

## 仍未覆盖的风险

1. **WebSocket 始终要求 JWT**，包括显式 `off` 模式；`off` 只影响 HTTP 过滤器，WS 匿名仍会被拒（符合 P1-4，但本地纯 WS 演示也需登录）。
2. **`.env` 未改**：本地/部署若沿用旧 `APS_JWT_SECRET`，compose 与 `stack-up` 会直接报缺新变量；需按 `.env.example` 迁移。
3. **dev API Key 仍无默认密钥**：脚本已统一由 `scripts/local-verify.ps1` 传开关与默认写密钥 `aps-dev-write-key-local-only`；若不经该脚本启动 dev 实例，仍需手动传 `-Daps.security.dev.api-key-enabled=true` 与 `-Daps.security.api-keys.write`。
4. **WS 只信任 JWT 签名/角色**，未二次查询用户/租户是否禁用或过期；与现有 HTTP JWT 链路一致。
5. **Origin 默认只含 5173**；18091 等前端部署必须显式配置 `APS_SECURITY_WS_ALLOWED_ORIGINS`。
6. **WS 仅支持 CONNECT 帧的 `Authorization: Bearer`**，不支持 Cookie/查询参数令牌；前端需相应改造。
7. 内存 Broker + 单实例；跨实例广播与租户隔离仍需后续 Broker 方案，当前依赖订阅端鉴权拦截。
