# 部署与运维手册（M3-QA-05）

> 适用制品：`aps-admin` fat jar（`aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`）。
> 事实来源：`aps-admin/src/main/resources/application.yml`、`application-dev.yml`、`deploy/docker-compose.yml`、`deploy/backend/Dockerfile`、`ApiSecurityProperties`、`db/migration/*.sql`、`docs/testing/m2-build-incidents.md`、`docs/perf/m2-qa-01-perf.md`。
> 编写日期：2026-11-04。任何标注「未验证」的条目，均未在制品级 HTTP/部署环境验证。

## 1. 部署拓扑与技术前提

```
宿主机
├── aps-frontend    nginx-unprivileged:1.31-alpine，静态 dist，/api 反代到 backend:8080
├── aps-backend     eclipse-temurin:21-jre-jammy，非 root(1001)，只读挂载 fat jar 到 /app/app.jar
├── postgres        postgres:15，数据卷，`POSTGRES_INITDB_ARGS=--data-checksums`
├── redis           redis:7，仅基础设施；后端尚未引入客户端
└── minio           pgsty/minio，对象存储预留；`aps.storage.minio.*` 尚未被后端读取
```

- Java 运行时：JRE 21；镜像默认 `JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"`。
- 后端端口 8080；优雅停机 `server.shutdown=graceful`，`timeout-per-shutdown-phase=30s`。
- 镜像**不含 jar**：compose 以只读方式把宿主机制品挂到 `/app/app.jar`，因此容器内运行的 jar 与仓库制品逐字节一致，可直接比对 SHA256。
- 单体应用，无内置集群/分布式求解；多实例部署需自行解决限流、求解队列与通知 outbox 的竞争（见 §10）。

## 2. 环境变量清单

> 说明：`DB_*` 与 `APS_*` 是 `application*.yml` 中的 `${}` 占位符；`SPRING_*` 是 Spring Boot 标准覆盖；compose 中的 `POSTGRES_*`/`MINIO_*`/`REDIS_*` 供基础设施容器使用。

### 2.1 数据库与基础运行

| 变量 | 来源 | 默认值 | 说明 |
|---|---|---|---|
| `DB_HOST` | `application.yml`、`application-dev.yml` | `localhost` | 数据库主机 |
| `DB_PORT` | 同上 | `5432` | 数据库端口 |
| `DB_NAME` | 同上 | `aps` | 数据库名 |
| `DB_USER` | 同上 | `aps` | 数据库用户 |
| `DB_PASSWORD` | `application.yml` | 空 | **生产必须显式注入**，否则连接失败 |
| `DB_PASSWORD` | `application-dev.yml` | `aps_dev_pwd` | dev 默认值，仅开发 |
| `SPRING_PROFILES_ACTIVE` | compose `APS_SPRING_PROFILES` | compose 默认 `dev` | 决定 Flyway 与调度器默认值 |
| `SPRING_DATASOURCE_URL` | compose | `jdbc:postgresql://postgres:5432/<DB>` | 优先级高于 yml |
| `SPRING_DATASOURCE_USERNAME` | compose | `POSTGRES_USER` | 同上 |
| `SPRING_DATASOURCE_PASSWORD` | compose | `POSTGRES_PASSWORD` | 同上 |
| `SPRING_FLYWAY_ENABLED` | compose `APS_FLYWAY_ENABLED` | `true` | 覆盖 `application.yml` 的 `enabled:false` |
| `TZ` | compose / 镜像 | `Asia/Shanghai` | 容器时区 |

### 2.2 安全与认证（`aps.security.*`）

| 变量 | 属性名 | 默认值 | 说明 |
|---|---|---|---|
| `APS_SECURITY_MODE` | `aps.security.mode` | `prod` | `prod`/`dev`/`off`；`off` 只能显式配置；`dev` 仅限 dev profile |
| `APS_SECURITY_API_KEYS_WRITE` | `aps.security.api-keys.write` | 空（禁用） | 仅 dev profile + dev 模式；留空表示只用 JWT |
| `APS_SECURITY_API_KEYS_READ` | `aps.security.api-keys.read` | 空（禁用） | 读密钥，写端点返回 40300 |
| `APS_SECURITY_JWT_SECRET` | `aps.security.jwt.secret` | 空 | **无默认值；未配置、短于 32 字节、或等于历史公开默认值 → 启动失败** |
| `APS_SECURITY_WS_ALLOWED_ORIGINS` | `aps.security.websocket.allowed-origins` | `http://localhost:5173,http://127.0.0.1:5173` | STOMP Origin 白名单，逗号分隔 |
| `APS_SECURITY_DEV_API_KEY_ENABLED` | `aps.security.dev.api-key-enabled` | `false` | **默认关闭**；仅 dev profile + `mode=dev` 生效，不打开则 `X-API-Key` 被忽略。本项目依赖 `X-API-Key` 的 dev 脚本（`verify-se5-justification.ps1`、`run-m2-perf.ps1`、`qa/m2-qa-02-p1-regress.ps1`）统一由 `scripts/local-verify.ps1` 以启动参数 `-Daps.security.dev.api-key-enabled=true` 开启，并同时以 `-Daps.security.api-keys.write` 传入默认 dev 写密钥；`application-dev.yml` 的默认值保持关闭，不在第二处重复配置 |
| `APS_SECURITY_DEV_USER_ID_HEADER_ENABLED` | `aps.security.dev.user-id-header-enabled` | `false` | 仅 dev profile 生效；允许 `X-User-Id` 覆盖审计操作人 |
| `APS_SECURITY_JWT_REQUIRED_ROLE` | `aps.security.jwt.required-role` | `APS_USER` | 令牌必须包含的角色 |
| `APS_SECURITY_JWT_TTL_SECONDS` | `aps.security.jwt.ttl-seconds` | `28800` | 令牌有效期（8 小时，无 refresh） |
| `APS_SECURITY_RATE_LIMIT_ENABLED` | `aps.security.rate-limit.enabled` | `true` | 单实例固定窗口限流 |
| `APS_SECURITY_RATE_LIMIT_CAPACITY` | `aps.security.rate-limit.capacity` | `100` | 每窗口请求数 |
| `APS_SECURITY_RATE_LIMIT_WINDOW_SECONDS` | `aps.security.rate-limit.window-seconds` | `60` | 窗口长度 |
| `APS_ADMIN_INIT_PASSWORD` | 代码常量 `AdminPasswordBootstrap.PASSWORD_ENV_KEY` | 无 | **仅 dev profile 生效**；prod 不初始化 |
| `APS_AUDIT_DEV_USER_ID` | `aps.audit.dev-user-id` | 空 | 仅 dev 用于填充审计操作人 |

> 环境变量命名按 Spring Boot 松散绑定：点换下划线、连字符换下划线、全大写。若某项未生效，可用 `SPRING_APPLICATION_JSON` 或命令行 `--aps.security.api-keys.write=...` 覆盖（两种写法未做制品级验证）。

### 2.3 求解与业务运行时

| 变量 | 属性名 | 默认值 | 说明 |
|---|---|---|---|
| `APS_SOLVE_PROGRESS_PUSH` | `aps.solve.progress-push` | `stomp` | `stomp` 实时推送；`none` 只缓存快照，前端回退轮询 |
| `APS_SOLVE_FALLBACK_ENABLED` | `aps.solve.fallback-enabled` | `true` | 引擎异常/超时/不可用降级开关 |
| `APS_SOLVE_QUEUE_CAPACITY` | `aps.solve.queue-capacity` | `32` | 有界队列容量；单工作线程 |
| `APS_SOLVE_TIMEOUT_GRACE_SECONDS` | `aps.solve.timeout-grace-seconds` | `5` | 超时看门狗宽限秒数 |
| `APS_SOLVE_RETRY_MAX_RETRIES` | `aps.solve.retry.max-retries` | `1` | 首次 + 1 次重试 = 最多 2 次尝试 |
| `APS_SOLVE_RETRY_DELAY_SECONDS` | `aps.solve.retry.delay-seconds` | `30` | 固定重试间隔 |
| `APS_BUSINESS_TIME_ZONE` | `aps.business.time-zone` | `Asia/Shanghai` | 交期截止点与 compare 口径 |

### 2.4 通知调度器（`aps.notification.scheduler.*`）

| 键 | 默认（`application.yml`） | dev（`application-dev.yml`） | 环境变量 |
|---|---|---|---|
| `aps.notification.scheduler.enabled` | `false` | `true` | **无 `${}` 占位符**，不能用环境变量直接覆盖 |
| `aps.notification.scheduler.interval-ms` | `30000` | `30000` | **无 `${}` 占位符** |

- 要改这两项，只能用 profile 文件、`SPRING_APPLICATION_JSON` 或命令行参数（例如 `--aps.notification.scheduler.enabled=true`）。
- 调度器只驱动 outbox 轮询；下发本身按契约**只写 outbox（`QUEUED`）**，不真实发送邮件/短信/飞书。

### 2.5 编排层专用（compose）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `aps` / `aps` / 必填 | 必填项缺失时 compose 启动失败 |
| `POSTGRES_PASSWORD` | 必填 | 同步注入 `DB_PASSWORD` 与 `SPRING_DATASOURCE_PASSWORD` |
| `REDIS_PASSWORD` | 必填 | 仅基础设施；后端尚未读取 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `MINIO_BUCKET` | `apsminio` / 必填 / `aps-attachments` | 后端尚未读取 `APS_MINIO_*` |
| `APS_BACKEND_JAR` | `../aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar` | 宿主机只读挂载路径 |
| `APS_BACKEND_PORT` / `APS_FRONTEND_PORT` | `8080` / `80` | 宿主机端口映射 |
| `APS_SPRING_PROFILES` | `dev` | 写入 `SPRING_PROFILES_ACTIVE` |
| `APS_FLYWAY_ENABLED` | `true` | 写入 `SPRING_FLYWAY_ENABLED` |
| `APS_BACKEND_IMAGE` / `APS_FRONTEND_IMAGE` | `aps-stack/aps-backend:local` / `nginxinc/nginx-unprivileged:1.31-alpine` | 镜像名 |
| `APS_SECURITY_MODE` | **必填**（`${...:?}`） | compose 缺失时直接报错并提示复制 `.env.example` |
| `APS_SECURITY_JWT_SECRET` | **必填**（`${...:?}`） | ≥32 字节；compose 缺失即报错，绕过则应用启动失败 |
| `APS_SECURITY_JWT_TTL_SECONDS` | `28800` | 秒；无 refresh |
| `APS_SECURITY_API_KEYS_WRITE` / `APS_SECURITY_API_KEYS_READ` | 空 | 仅 `mode=dev` + dev profile 可用 |
| `APS_SECURITY_WS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | 生产必须改为实际前端域名 |

> **注意**：旧的 `docs/deploy/docker-compose.md`、`docs/deploy/local-dev.md` 仍写 `APS_JWT_SECRET` / `APS_JWT_EXPIRE_MINUTES`，已与当前代码不符（当前 compose 注入 `APS_SECURITY_*`，后端读取 `APS_SECURITY_JWT_SECRET` 秒级 `APS_SECURITY_JWT_TTL_SECONDS`）。按旧文档配置会直接启动失败。

## 3. Flyway 迁移现状

### 3.1 版本清单

| 版本 | 文件 | 内容 |
|---|---|---|
| V1 | `V1__init.sql` | 基线：扩展、租户、RBAC、客户/供应商、物料分类/物料、BOM、资源、日历、工艺路线、工序模板、订单、库存相关基础、计划版本、任务、审计等 |
| V2 | `V2__inventory.sql` | 库存与在途 |
| V3 | `V3__plan_version.sql` | 计划版本 |
| V4 | `V4__constraint_dsl.sql` | 约束 DSL |
| V5 | `V5__task_lock.sql` | 任务锁（`lock_type/locked_from/locked_to/locked_resource_ids`） |
| V6 | `V6__solve_task_idempotency.sql` | 求解任务幂等 |
| V7 | `V7__constraint_justification_score.sql` | 归因与评分 |
| V8–V10 | **无文件（空号）** | 不存在对应 SQL，属版本号预留 |
| V11 | `V11__notification_outbox.sql` | 通知 outbox |
| V12 | `V12__import_job.sql` | 异步导入任务 |

- 仓库现有 **9 个**迁移脚本：V1–V7、V11、V12。
- 迁移位置：`classpath:db/migration`。由 `aps-admin/pom.xml` 把**仓库根** `db/migration` 复制到 `target/classes/db/migration`，再打进 fat jar 的 `BOOT-INF/classes/db/migration`。
- `deploy/backend/Dockerfile` **没有** `COPY db/migration`；compose 注释「迁移脚本已由 aps-admin/Dockerfile 的 COPY 打进镜像」与实际不符，以 pom 复制为准。

### 3.2 关键配置

| 配置 | 值 | 影响 |
|---|---|---|
| `spring.flyway.enabled` | 基础 `false`；dev `true`；compose `SPRING_FLYWAY_ENABLED=true` | 非 dev 且不用 compose 时**不会自动迁移** |
| `baseline-on-migrate` | `false` | 非空库首次接入不会被自动 baseline |
| `validate-on-migrate` | `true` | 校验和不一致会阻断启动 |
| `out-of-order` | `false` | 不允许乱序版本 |
| `clean-disabled` | `true` | 禁止 `flyway clean` 清库 |
| `encoding` | `UTF-8` | 中文注释安全 |

### 3.3 已核实的制品与迁移事实

- M2 末次制品实测：`BOOT-INF/lib` **112 条**、条目 259、大小 **181,561,836 B**、SHA256 `0D9C99F8…2A18`，`db/migration` 为 **V1–V7 共 7 个**。
- V11/V12 已存在于仓库，但**未在制品级验证是否已打进 fat jar**（标「未验证」）。部署前必须执行 §5 的 `jar tf ... | Select-String 'BOOT-INF/classes/db/migration/'` 确认。
- V3 历史上出现过纯注释漂移（`1198800526` → `946499021`），已用 `flyway repair` 处理；**已应用的迁移禁止再改，连注释也不行**，否则所有实例启动被 `checksum mismatch` 阻断。
- compose 注释中的「29 张表 / 28 张表」口径已过期，请以实际迁移结果为准（未在本手册固化表数）。

---

## 4. 启动前检查清单

按顺序逐项确认，任一不通过不要启动。

| # | 检查项 | 判定方法 | 不通过的处理 |
|---|---|---|---|
| 1 | 运行时版本 | `java -version` 为 21 | 安装 JRE 21 |
| 2 | 数据库可达 | 从部署机 `psql`/JDBC 能连 `DB_HOST:DB_PORT/DB_NAME` | 检查网络、`pg_hba`、密码 |
| 3 | 迁移开关 | 使用 compose 时 `SPRING_FLYWAY_ENABLED=true`；非 compose 且非 dev 时必须显式设 `--spring.flyway.enabled=true` | 否则表结构不会创建 |
| 4 | 迁移版本 | 空库可直接迁移；已有库确认 `flyway_schema_history` 最高版本与制品内脚本兼容 | 版本不一致先做备份，禁止手改历史迁移 |
| 5 | 认证模式与密钥 | `APS_SECURITY_MODE` 显式设置；`APS_SECURITY_JWT_SECRET` ≥32 字节且非历史默认值；`mode=dev` 必须同时 `SPRING_PROFILES_ACTIVE=dev` | 启动校验会直接拒绝启动 |
| 6 | admin 口令 | dev：设置 `APS_ADMIN_INIT_PASSWORD`；**prod：`AdminPasswordBootstrap` 不执行**，需直接向 `users.password_hash` 写入 bcrypt | 否则 admin 无口令，登录必然失败 |
| 7 | 时区 | 容器 `TZ=Asia/Shanghai`，且 `APS_BUSINESS_TIME_ZONE` 与业务口径一致 | 交期/甘特口径会漂移 |
| 8 | 制品完整 | §5 的四项校验全通过 | 取回完整 fat jar，禁止用瘦包启动 |
| 9 | 端口 | 宿主机 `APS_BACKEND_PORT`、`APS_FRONTEND_PORT` 未被占用 | 改端口或释放 |
| 10 | 打包互斥 | 当前没有实例在运行该 jar，且无并发 `mvn package` | Windows 下运行中 jar 被覆盖会变瘦包 |
| 11 | 调度器决策 | 明确 `aps.notification.scheduler.enabled` 是否需要打开；prod 默认 `false` | 打开才会消费 outbox |
| 12 | 磁盘 | 预留 ≥ 2GB（jar ≈181MB + 日志/数据增长） | 扩容 |

## 5. 制品校验

### 5.1 正常制品特征

| 指标 | 正常值 | 异常特征 |
|---|---|---|
| 文件大小 | ≈181MB（M2 末次 181,561,836 B） | 约 25KB 即瘦包 |
| `BOOT-INF/lib` 条目 | **112 条**（M2 末次实测） | 0 条即瘦包 |
| 总条目 | ≈259 | 25 条左右即瘦包 |
| SHA256 | 与 `MANIFEST.txt` 的 `jar_sha256` 一致 | 不一致说明制品被替换 |

> 大小会随版本小幅变化（例如新增 V11/V12 会把 `db/migration` 打进去），因此**不要只比大小**；核心判据是 `BOOT-INF/lib` 非 0 且接近 112、`BOOT-INF/classes/db/migration` 存在。

### 5.2 校验命令（PowerShell）

```powershell
$jar = "d:\tools\smart-aps-agent\aps-admin\target\aps-admin-0.1.0-SNAPSHOT.jar"

# 1) 大小与时间
Get-Item $jar | Select-Object Length, LastWriteTime

# 2) SHA256
(Get-FileHash $jar -Algorithm SHA256).Hash

# 3) 依赖条目数（正常 112）
(jar tf $jar | Select-String 'BOOT-INF/lib/' | Measure-Object).Count

# 4) 迁移脚本是否在包内
jar tf $jar | Select-String 'BOOT-INF/classes/db/migration/'
```

### 5.3 离线包清单

`scripts/stack-bundle.ps1` / `stack-bundle.sh` 会生成 `MANIFEST.txt`，至少包含 `jar_size` 与 `jar_sha256`（大写十六进制）。交付与部署双方必须比对这两个值；镜像 tar 的校验以离线包内实际文件为准（**未在本手册固化镜像 digest**）。

## 6. 启动与健康检查

### 6.1 启动

```powershell
# 方式一：整栈（脚本会先校验 jar 大小与 BOOT-INF/lib，瘦包直接中止）
./scripts/stack-up.ps1            # 或 -SkipBuild

# 方式二：仅后端（已有数据库与迁移）
java $env:JAVA_OPTS -jar aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=dev `
  --aps.security.mode=dev
```

- compose 的 `depends_on` 要求 postgres/redis/minio 均 `service_healthy` 后才启动后端。
- `stop_grace_period: 30s`，与 `server.shutdown=graceful` 对齐；停止时请等待进程退出，不要 `docker kill`。

### 6.2 健康检查

`GET /api/v1/health` 返回：

```json
{"code":0,"message":"success","data":{"status":"UP","service":"aps-admin"},"traceId":"..."}
```

- **这是 liveness，不是 readiness**：`HealthController` 不查询数据库。数据库断开时它仍可能返回 200。
- compose healthcheck：`curl -fsS http://127.0.0.1:8080/api/v1/health`，`interval=10s`、`timeout=5s`、`retries=12`、`start_period=90s`（首次启动要给 Flyway 留时间）。
- 判断「真正就绪」需同时满足：
  1. 日志出现 `Successfully validated N migrations`；
  2. `Current version of schema "public": X` 与制品内最高版本一致；
  3. `Started ApsAdminApplication in ... seconds`；
  4. 一个真实读写接口成功（例如 `GET /api/v1/materials?page=1&size=1`）。

### 6.3 停止

```powershell
./scripts/stack-down.ps1
```

停止顺序：前端 → 后端 → 基础设施；后端等待优雅停机完成。

## 7. 日志

### 7.1 输出位置与格式

- **只输出到 stdout，不写本地滚动文件**（`logback-spring.xml` 明确「容器环境统一输出到 stdout，由平台侧收集」）。
- 非 `prod`：单行文本，格式含 `traceId`、`tenantId`、线程、logger、消息。
- `prod`：JSON（LogstashEncoder），`customFields` 含 `service=aps-admin`、`env=prod`，MDC 输出 `traceId`、`tenantId`、`userId`、`durationMs`。
- 容器日志轮转：`json-file`，`max-size: 10m`、`max-file: 3`（每容器最多约 30MB）。

### 7.2 查看与排查

```powershell
docker logs aps-stack-backend --since 30m
docker logs aps-stack-backend --since 30m | Select-String 'ERROR|Exception|ValidateException'
```

- 请求链路：客户端传 `X-Trace-Id`，服务端透传/生成 `traceId`，响应体也有 `traceId`；用它在日志中串起一次请求。
- 原生（非容器）运行没有内置文件日志，需自行重定向 stdout（脚本未固化该行为）。

## 8. 备份与回滚

### 8.1 备份

```powershell
# 逻辑备份（自定义格式，支持并行恢复）
pg_dump -h <DB_HOST> -p <DB_PORT> -U <DB_USER> -Fc -d <DB_NAME> `
  -f "backup_aps_$(Get-Date -Format yyyyMMdd_HHmmss).dump"
```

- 备份时机：发布前、执行 Flyway 迁移前、批量导入前。
- 同时备份：当前运行的 fat jar、`.env`、`deploy/docker-compose.yml`、`MANIFEST.txt`。

### 8.2 回滚

| 场景 | 步骤 |
|---|---|
| 仅应用回滚 | 停后端 → 换回旧 jar → 校验 §5 → 启动 → 验证 §6.2 |
| 数据库回滚 | 停后端 → `pg_restore -h ... -U ... -d <新库> backup.dump` → 指向新库启动 |
| 迁移失败 | 不要手改已应用 SQL；先恢复备份，或仅在确认差异为文本时用 `flyway repair` |

```powershell
pg_restore -h <DB_HOST> -p <DB_PORT> -U <DB_USER> -d <DB_NAME> --clean --if-exists backup_xxx.dump
```

- Flyway 社区版**没有 undo**；回滚数据库只能靠备份/恢复。
- 兼容性提示：新版本 schema 通常可被旧 jar 读取（多出的表/列一般无影响）；但旧库缺新表时新 jar 会启动失败。**先升库后升应用**或**整体回滚**，不要只回滚一半。
- 沙箱 apply / 计划发布是不可变快照，回滚应用不会回滚业务数据；需要业务回滚请走计划回滚接口（新建 PUBLISHED 版本）。

## 9. 常见故障排查

| 现象 | 可能原因 | 排查 | 处理 |
|---|---|---|---|
| 启动即退出，日志 `ValidateException` / checksum mismatch | 已应用迁移被修改，或制品内迁移版本与库不一致 | 对比 `flyway_schema_history` 与 `BOOT-INF/classes/db/migration` | 恢复原文件或按规则 `repair`；禁止手改历史迁移 |
| 启动后 404 或表不存在 | 非 dev 且未开启 Flyway | 查看启动日志是否有 `Flyway` 输出 | 设 `SPRING_FLYWAY_ENABLED=true` 或 `--spring.flyway.enabled=true` |
| 启动失败：`aps.security.jwt.secret 未配置` 或 `长度不足` | 未设置 `APS_SECURITY_JWT_SECRET`，或短于 32 字节 | 看启动日志第一段 `IllegalStateException` | 注入 ≥32 字节随机密钥 |
| dev 下带 `X-API-Key` 仍 40100 | 未开启 `aps.security.dev.api-key-enabled`，或密钥为空/非 dev profile | 日志出现「dev API Key 未启用或非 dev profile，已忽略该请求头」 | 三条件齐备：dev profile + `mode=dev` + dev 开关与密钥 |
| 所有写请求 40100 | 未带 `X-API-Key` / `APS_SECURITY_MODE=prod` 却用 API Key | 检查 `aps.security.mode` 与请求头 | prod 改用 Bearer JWT；dev 用写密钥 |
| 写请求 40300，GET 正常 | dev 模式用了读密钥 | 检查 `APS_SECURITY_API_KEYS_READ/WRITE` | 换写密钥，或调整角色/权限 |
| admin 登录失败、口令为空 | prod 不执行 `APS_ADMIN_INIT_PASSWORD` 初始化 | 查 `users.password_hash` | 受控写入 bcrypt 哈希；不要在生产启用 dev profile 兜底 |
| `/health` 200 但接口全挂 | 磁盘上的 jar 已被覆盖成瘦包，进程仍用内存旧类 | `Get-Item` 大小、`jar tf` lib 条数 | 停服 → 换回完整制品 → 重启 |
| `50300` / 连接池超时 | 数据库不可达或连接耗尽 | 日志 `HikariPool`、`connection-timeout` | 恢复数据库；检查 `maximum-pool-size`（dev 10） |
| 大量 `42900` | 限流触发 | 查 `aps.security.rate-limit.*` | 调大容量/窗口，或对批量操作分批 |
| 求解一直 `RUNNING` 或 `TIMEOUT` | 规模超容量、时限过短 | 看 `solveTaskId` 详情与 `metrics` | 按 ≤50 单/300 任务分批；时限给足 |
| 求解 `FAILED` 且 `errorCode=50010` | 引擎异常/重试耗尽 | 看 `retryCount` 与异常日志 | 检查引擎依赖与内存；必要时重提 |
| 下发记录一直是 `QUEUED` | 调度器关闭（prod 默认） | 查 `aps.notification.scheduler.enabled` | 按需开启；注意本版本只写 outbox，不真实发送 |
| 异步导入任务重启后仍 `PENDING` | 无崩溃恢复 | 查 `import_job` 状态 | 重新提交或取消，不能等待自动恢复 |
| 日历/交期时间偏移 | 容器时区或 `APS_BUSINESS_TIME_ZONE` 不一致 | `date`、日志时间戳 | 统一 `TZ` 与业务时区 |
| 中文乱码 | 编码未固定 | 启动参数 | 保持 `-Dfile.encoding=UTF-8` |
| `Ambiguous mapping` 启动失败 | 同一路径被两个控制器映射 | 启动日志关键字 | 回退最近一次控制器改动，禁止带病启动 |

## 10. 已知限制

1. **500 单 / 60 秒超时，容量上限 ≤50 单 / 300 任务**：默认 `timeLimitSeconds=60` 下 500 单返回 `TIMEOUT`；50 单约 61.3s 可 `SOLVED`，8 单 10.8–12.1s。不要按 500 单做容量规划。
2. **单进程求解并发度 1**：单工作线程，每场景最多 1 个 `RUNNING`；队列容量 32。多实例不会自动提升吞吐，反而各自持队列。
3. **`stepCount` / `unimprovedStepCount` 不再输出**：M3 起从 `metrics` 剔除；监控/报表不得依赖。
4. **`metrics` 中的 `null` 原样输出**：采集端必须能处理 `null`，不得当作字段缺失。
5. **异步导入 `PENDING` 在进程重启后滞留**：`V12` 的 job 表无恢复扫描，重启后不会继续；需人工重提或取消。
6. **`LocalDbRegressionHarness` 仅手工执行**：不在 CI，也不由启动脚本触发；发布前人工跑并留存结果。
7. **`/permissions` 裸数组、`/roles` 为 `{items}`**：前端/网关做响应解包时需分别处理。
8. **健康检查只查进程**：`/health` 不查 DB，不能作为「数据库就绪」判据；compose 的 `service_healthy` 也只代表进程活着。
9. **prod 无 admin 口令初始化**：`AdminPasswordBootstrap` 仅在 dev profile 执行；生产必须手工写入 bcrypt 哈希，否则管理员无法登录。
10. **旧部署文档已过期**：`docs/deploy/docker-compose.md`、`docs/deploy/local-dev.md` 仍写 `APS_JWT_SECRET` / `APS_JWT_EXPIRE_MINUTES`；当前后端读 `APS_SECURITY_JWT_SECRET` / `APS_SECURITY_JWT_TTL_SECONDS`，按旧文档配置会直接启动失败。
11. **dev 的 API Key 需三条件**：`mode=dev` + `dev` profile + `aps.security.dev.api-key-enabled=true`，且密钥非空；缺一即忽略 `X-API-Key`（回落到 JWT，表现为 40100）。
12. **compose 注释中的「28/29 张表」已过期**：以实际迁移结果为准。
13. **限流按单实例计数**：多实例部署总配额为「实例数 × capacity」，不是全局 100/60s。
14. **V11/V12 未做制品级验证**：仓库有脚本，但未确认已打入当前 jar；上线前必须用 §5.2 第 4 条命令确认。
15. **V8–V10 为空号**：Flyway 历史表中不会出现这三个版本，属正常现象，不要为了「连续」而补建脚本。
16. **compose 注释与制品不符**：迁移并非由 Dockerfile `COPY` 进镜像，而是由 `aps-admin/pom.xml` 复制进 fat jar；排查「表不存在」时不要去看镜像层。

