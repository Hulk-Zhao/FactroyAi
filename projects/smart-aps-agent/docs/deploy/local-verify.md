# 无 Docker 本地验证环境（local-verify）

本机没有 Docker（`com.docker.service` 未启动且无提权），因此用 **便携 PostgreSQL + 本地 JVM/Node** 搭一套可复现的验证环境，替代「本机无 Docker，所以未验证」的空白。
本文记录环境变量、端口、命令、已执行验证与真实结果，以及当前代码下仍然失败的项与原因。

- 工作区：`d:\tools\smart-aps-agent`（脚本与本文档在仓库内）
- 工具链（工作区外）：`D:\tools\_aps-toolchain`（Temurin JDK 21.0.13、Maven 3.9.16、Node 26.13.0、PostgreSQL 15.12 便携二进制）
- 本地数据（工作区外）：`D:\tools\_aps-localdata`（PG 数据目录、后端/回归/前端日志、jar 快照）
- 所有下载经 clash 代理 `127.0.0.1:7890`；未修改系统环境变量、未全局安装任何组件

## 1. 端口与账号

| 项 | 值 |
| --- | --- |
| PostgreSQL | `127.0.0.1:55432`（用户 `aps` / `aps_dev_pwd`） |
| 数据库 | `aps_local`（应用 Flyway 迁移）、`aps_local_2`（psql 复现） |
| 后端默认 | `18080`（dev profile）；前端冒烟时切到 `8080`（vite 代理固定 8080） |
| 前端 dev | `5173`（冒烟用 `5181` 避开并发会话占用的 5177） |

后端数据源覆盖全部走环境变量（不修改 `application*.yml`）：

| 变量 | 值 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` |
| `DB_HOST` / `DB_PORT` | `127.0.0.1` / `55432` |
| `DB_NAME` | `aps_local` |
| `DB_USER` / `DB_PASSWORD` | `aps` / `aps_dev_pwd` |

## 2. 交付资产

| 路径 | 作用 |
| --- | --- |
| `scripts/local-db-up.ps1` | 启动便携 PostgreSQL；幂等（已运行则跳过）、数据目录缺失则 initdb、端口冲突先检查再中止、自动建库 |
| `scripts/local-db-down.ps1` | 停止 PostgreSQL（fast）；默认保留数据目录，`-RemoveData` 才删除（需确认） |
| `scripts/local-verify.ps1` | 串起「迁移 → 后端冒烟 → BD-01 真库回归 → 前端冒烟」，支持 `-SkipFrontend` / `-SkipBackend` / `-SkipRegression` / `-KeepRunning` / `-StopDb` |
| `aps-admin/src/test/java/com/hengce/aps/verify/**` | **仅测试用**真库回归 harness（`LocalDbRegressionHarness` 等），不进入生产包 |
| `docs/deploy/local-verify.md` | 本文档 |

## 3. 快速开始

```powershell
# 0) 一次性准备：工具链与便携 PG（已就绪可跳过）
#    PG 便携包：https://get.enterprisedb.com/postgresql/postgresql-15.12-1-windows-x64-binaries.zip
#    解压到 D:\tools\_aps-toolchain\pgsql（解压后含 bin\postgres.exe）

# 1) 启动数据库（幂等）
.\scripts\local-db-up.ps1

# 2) 构建后端 fat jar（注意并发会话可能覆盖 target 产物）
$env:JAVA_HOME='D:\tools\_aps-toolchain\jdk-21.0.13+9'
$env:PATH="$env:JAVA_HOME\bin;D:\tools\_aps-toolchain\apache-maven-3.9.16\bin;$env:PATH"
$env:MAVEN_OPTS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890'
mvn -B -DskipTests package

# 3) 一键验证（含前端）
.\scripts\local-verify.ps1

# 仅后端 + 回归：
.\scripts\local-verify.ps1 -SkipFrontend

# 4) 收尾（默认保留数据目录）
.\scripts\local-db-down.ps1
```

## 3.1 认证与限流默认模式（M2 C27）

**默认值：`off`（不挡请求）。** 未显式配置 `aps.security.mode` 且未启用 `prod` profile 时，`ApiSecurityFilter` 与 `RateLimitFilter` 均按 `off` 执行：不校验凭证、不计限流，本地 demo 与回归脚本（`demo-m1.ps1`、`local-verify.ps1`，均不带任何 key）可直接跑通。

| 场景 | 生效模式 | 行为 |
| --- | --- | --- |
| `prod` profile（`SPRING_PROFILES_ACTIVE=prod`） | `prod` | 全模块强制 JWT（HS256，需 `APS_USER` 角色）；缺失/无效 401，角色不足 403 |
| 显式 `APS_SECURITY_MODE=dev` | `dev` | 仅写端点（POST/PUT/PATCH/DELETE）强制认证：写密钥 200、读密钥 403、无凭证 401；GET 放行 |
| 未配置（默认）或 `APS_SECURITY_MODE=off` | `off` | 全放行，限流也关闭 |

开启认证（本地验证用）：

```powershell
$env:APS_SECURITY_MODE = 'dev'
# 写密钥 dev-write-key / 读密钥 dev-read-key（APS_SECURITY_API_KEYS_WRITE / _READ 可覆盖）
```

开启限流（显式 `dev`/`prod` 后默认 100 req/min，`APS_SECURITY_RATE_LIMIT_CAPACITY` 可调）：
`off` 模式下限流同样不生效，避免默认环境被 429 误伤。

### 3.1.1 审计操作人注入（created_by / updated_by / published_by）

认证通过后由 `ApiSecurityFilter` 写入 `AuditContext`，`ApsMetaObjectHandler` 据此填充审计列；请求结束（finally）清理 ThreadLocal。

| 模式 | 操作人来源 | 行为 |
| --- | --- | --- |
| `prod` | JWT `payload.sub` | 扩展点已留，M3 令牌签发后接入；当前不伪造 |
| `dev` | `X-User-Id` 头优先，缺省 `aps.audit.dev-user-id` | 头必须是合法 UUID，非法直接 **400/40000**；两者都缺省则不注入 |
| `off` | 无 | **不注入**，`created_by/updated_by/published_by` 保持 NULL（不假装有账号） |

```powershell
# 仅开发态：指定操作人（生产禁用，生产走 JWT subject）
$env:APS_AUDIT_DEV_USER_ID = '11111111-1111-1111-1111-111111111111'
# 或单请求头覆盖
# -H "X-User-Id: 22222222-2222-2222-2222-222222222222"
```

`aps.audit.dev-user-id` 默认空值；**仅用于开发态**，部署到生产必须保持为空并依赖 JWT。

## 4. 实际操作过的命令（摘要）

```powershell
# 数据库
D:\tools\_aps-toolchain\pgsql\bin\initdb.exe -D D:\tools\_aps-localdata\pg -U aps --pwfile=... -E UTF8 -A scram-sha-256
D:\tools\_aps-toolchain\pgsql\bin\pg_ctl.exe -D D:\tools\_aps-localdata\pg -l D:\tools\_aps-localdata\pg.log -o "-p 55432 -c listen_addresses=127.0.0.1" start
D:\tools\_aps-toolchain\pgsql\bin\createdb.exe -h 127.0.0.1 -p 55432 -U aps aps_local
D:\tools\_aps-toolchain\pgsql\bin\createdb.exe -h 127.0.0.1 -p 55432 -U aps aps_local_2

# 第二库：直接跑 V1（可重复跑通的基线）
D:\tools\_aps-toolchain\pgsql\bin\psql.exe -h 127.0.0.1 -p 55432 -U aps -d aps_local_2 -v ON_ERROR_STOP=1 -f db/migration/V1__init.sql

# 应用侧 Flyway（启动即迁移）
java -jar aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar --server.port=18080   # SPRING_PROFILES_ACTIVE=dev + DB_* 环境变量
```

## 5. 已验证项与结果

### 5.1 Flyway / 数据库对象

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 应用侧 Flyway 迁移 | **PASS** | `flyway_schema_history`: `version=1 / description=init / success=true`，`installed_on=2026-11-04 01:30:30.416502`，耗时 615ms；日志 `Successfully validated 1 migration` |
| `aps_local_2` 用 psql 复现 V1 | **PASS** | `psql -v ON_ERROR_STOP=1 -f db/migration/V1__init.sql` 退出码 0 |
| 表数量 | 29（含 `flyway_schema_history`）/ 第二库 28 | `information_schema.tables` |
| 索引数量 | 97 / 第二库 95（差异即 Flyway 自身 2 个索引） | `pg_indexes` |
| 外键数量 | 63 / 63 | `information_schema.table_constraints` |
| CHECK 约束 | 392 / 384（差异为 Flyway 表） | 同上 |
| 扩展 | **PASS** | `pgcrypto(1.3)`、`pg_trgm(1.6)` 均存在 |

> 与 `docs/db/README.md` 的「28 张业务表 + flyway_schema_history」一致。

### 5.2 BD-01 真库回归（`LocalDbRegressionHarness`，13/13 PASS）

运行方式：test-scope 类直接复用生产 `ApsAdminApplication` 装配与 MyBatis-Plus 拦截器链，连 `aps_local`；
Mapper 位于生产扫描根包 `com.hengce.aps.infrastructure.persistence.mapper` 下，但**只存在于 `src/test`**。

| 断言 | 结果 | 证据（真实 SQL / JDBC） |
| --- | --- | --- |
| 忽略表 `tenant` / `role_permission` / `user_role` 不注入 `tenant_id` | PASS | `SELECT COUNT(*) AS total FROM tenant` / `... role_permission` / `... user_role` 均无 `tenant_id`，计数 2 / 46 / 1 |
| 默认租户 INSERT 自动补 `tenant_id` | PASS | 未显式赋值，落库 `tenant_id=00000000-0000-0000-0000-000000000001` |
| 审计填充 | PASS | `created_at/updated_at/created_by` 均自动填充（`created_by=...0301`） |
| 时间列类型 | PASS | `created_at/updated_at` 均为 `timestamp with time zone`（TIMESTAMPTZ） |
| SELECT 租户隔离 | PASS | `... FROM material WHERE deleted=false AND (id=?) AND tenant_id='...0002'`，默认租户行不可见（count=0） |
| 显式租户 INSERT | PASS | 落库 `tenant_id=...0002` |
| UPDATE 租户过滤 | PASS | 默认租户改第二租户行：`rows=0`；第二租户改自身：`rows=1`；SQL 末尾追加 `AND tenant_id=...` |
| DELETE 租户过滤 | PASS | 跨租户删除 `rows=0`，行仍 `deleted=false` |
| 乐观锁 | PASS | 首次 `UPDATE ... AND version=?`：`rows=1`，`version 1→2`；用过期 `version=1` 再更新：`rows=0` |
| 逻辑删除 + 编码复用 | PASS | 删除 SQL 为 `UPDATE material SET deleted=true WHERE ...`；删除后查询不可见；同 `code` 重新 INSERT 成功（部分唯一索引生效） |
| JSONB 往返 | PASS | 写入前 `jsonb_typeof(ext_attrs)=object`；`Types.OTHER` 写入后读回 `{color=black, brand=TI, tags=[rohs, reach], weightKg=0.35}` |
| 审计 UPDATE 刷新 | PASS | `updated_at` 由 `2026-11-03T17:41:56.574153Z` → `...596324Z`，`created_at` 不变 |

### 5.3 后端启动冒烟

**PASS（基于 01:27 打包快照，当时未包含并发会话的日历/物料 Controller）**

| 请求 | 结果 | traceId |
| --- | --- | --- |
| `GET /api/v1/health` | 200 | `a632525d49a74ed0a6f07f22a2c26d7a` |
| `GET /v3/api-docs/materials` | 200 | `eaeec9b6c5e8402da16d93ab966b29c1` |
| `GET /v3/api-docs/solve` | 200 | `8d8539c69f1444ce8fbdd1c9d746aad3` |
| `GET /v3/api-docs/gantt` | 200 | `0f07f09e18194188b98a2ba145dc5d28` |
| `GET /api/v1/orders` | 200 | `c92f055dedf843ed9afb665224d9f6a9` |
| `GET /api/v1/materials` | 501（契约占位 `接口尚未实现`） | `ee6adf6943954b0393c8da073f4b48f9` |
| `GET /api/v1/operation-templates` | 404（BD-06 未落地时） | `49f56ea08b814ad1afeba670f41ef240` |

**FAIL（当前 HEAD 组合代码无法启动）**，两个独立阻塞见第 6 节。用 `--spring.main.sources` 注入测试侧 UUID TypeHandler 后，UUID 问题被绕过，但随即被路由冲突拦下，说明二者都是真实代码问题而非环境问题。

### 5.4 前端

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `npm run build` | **PASS** | 退出码 0；vite build 完成（仅有 chunk 体积提示） |
| `npm ci` | **FAIL（并发占用）** | `EPERM: unlink ...\node_modules\@rolldown\binding-win32-x64-msvc\rolldown-binding.win32-x64-msvc.node`；同机其他会话的 node 进程正占用 `node_modules` |
| `npm run dev`（5181，Mock 关闭，代理到 8080 本地后端） | **PASS** | 首页 `http://localhost:5181/` 200；`vite ready in 272ms` |
| 首页 / `/orders` / `/master-data/materials` 渲染 | **PASS** | 浏览器自动化：三页均非错误页，可见「系统总览」「订单管理」「物料管理」主体与表格/表单 |
| 控制台无报错 | **FAIL** | 首页 1 条 `net::ERR_ABORTED http://localhost:5181/`；/orders 2 条（含 `[api] server error {code:50100, message:接口尚未实现}`）；/master-data/materials 3 条（2 条 50100） |

50100 来自后端契约占位接口（`/api/v1/materials` 在 01:27 快照中返回 501），不是前端构建缺陷；`ERR_ABORTED` 需后续单独定位。

## 6. 失败项与原因（均为事实证据，未改动他人代码）

1. **生产侧缺少 UUID TypeHandler（阻塞启动）**
   - 现象：解析 `aps-infrastructure` 的 `mapper/calendar/CalendarMapper.xml` 时报
     `Type handler was null on parameter mapping for property 'resourceId' ... javaType (java.util.UUID)`。
   - 原因：MyBatis 3.5.16 核心**未内置** UUID TypeHandler；`BaseEntity.id` 为 `Serializable`，且 `paramType=UUID` 的 XML/注解参数无法解析。
   - 影响：不仅 Calendar，任何以 `UUID` 作为参数类型或 `Serializable id` 作为主键的 Mapper 在 MyBatis 装配阶段都会失败；BD-01 主键写入同样受影响（回归中需在 test 侧补注册 `VerifyUuidTypeHandler` 才能继续）。
   - 建议（非本任务改动）：在 `aps-infrastructure` 注册全局 UUID TypeHandler（`ConfigurationCustomizer` 或 `typeHandlersPackage`）。

2. **路由重复映射（阻塞启动）**
   - 现象：`Ambiguous mapping. Cannot map 'materialController' method ... PUT [/api/v1/materials/{code}]`，已存在 `materialsContractController#updateMaterial`。
   - 原因：并发会话落地的 `MaterialController` 与契约占位 `MaterialsContractController` 同时映射同一路径；需要由对应 owner 处理（移除占位或收敛路径）。

3. **`MaterialContractMappingCleaner` 强依赖 MVC 基础设施**
   - 现象：非 Web 上下文（回归 harness 用 `WebApplicationType.NONE`）启动失败：
     `No qualifying bean of type 'RequestMappingHandlerMapping'`。
   - 影响：真库回归只能针对「不包含该类的代码快照」运行；后续若要在最新代码上跑，需要让 cleaner 具备 `@ConditionalOnWebApplication` 或无 MVC 时的回退。

4. **`npm ci` 被并发会话的 node 进程占用阻塞**（`EPERM unlink`），未删除/覆盖他人 `node_modules`，改用现有依赖完成 build 与 dev 渲染验证。

5. **前端控制台存在错误**：`net::ERR_ABORTED` 与后端 501 占位接口报错（见 5.4）。

> 另：验证期间后端 `target` 产物曾被并发构建覆盖为 thin jar（18KB），导致 `java -jar` 报 `NoClassDefFoundError`；已改为重新打包并复制快照到 `D:\tools\_aps-localdata\aps-admin-verify.jar` 后验证，避免构建竞态。

## 7. 已知限制

- 未覆盖 Redis、MinIO、Docker Compose、多副本/集群与生产网络拓扑；这些能力在本仓库当前阶段尚未进入启动链路。
- 数据库为单实例便携 PG、单库单租户数据；未验证多租户并发、连接池压测、迁移回滚（V1 无 down 脚本）。
- 后端最新 HEAD 因第 6 节 1、2 项无法启动，因此 `traceId` 冒烟证据来自 01:27 快照；API 契约文档端点均 200。
- 前端 `npm ci` 未成功执行（环境竞态），`npm run build` 与 dev 渲染基于已有 `node_modules`；控制台报错未清零。
- 回归 harness 的 UUID TypeHandler 为 test 侧补注册，仅用于验证拦截器/填充语义，不代表生产已修复。

## 8. 清理方式

```powershell
# 停止后端 / 前端：结束对应端口进程（脚本收尾会自动停止后端）
Get-NetTCPConnection -LocalPort 18080,8080,5173 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

# 停止数据库（保留数据目录）
.\scripts\local-db-down.ps1

# 如需彻底删除本地数据（PG 数据目录 + 日志 + jar 快照）
.\scripts\local-db-down.ps1 -RemoveData
Remove-Item -Recurse -Force D:\tools\_aps-localdata
```

数据目录 `D:\tools\_aps-localdata\pg` 默认保留，下次 `local-db-up.ps1` 直接复用（含已迁移的 `aps_local`）。
