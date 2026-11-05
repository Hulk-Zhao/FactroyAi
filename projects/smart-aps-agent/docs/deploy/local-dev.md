# 本地开发环境（本地依赖栈）

> 任务：P0-06 开发环境统一（本地栈）
> 适用范围：所有开发者在本机启动 PostgreSQL 15 / Redis 7 / MinIO，供后端、前端联调。
> 架构依据：[aps-arch-page](../../aps-arch-page/public/index.html) 5.1「Docker Compose 服务清单与端口规划」（只读，不改动）。

## 1. 交付文件

| 文件 | 作用 |
| --- | --- |
| `deploy/docker-compose.dev.yml` | 基础栈：PostgreSQL 15 + Redis 7 + MinIO，含 healthcheck、依赖顺序、数据卷 |
| `deploy/docker-compose.dev.expose.yml` | 可选叠加文件：把依赖端口仅绑定 `127.0.0.1`，供 IDE / 本机客户端直连 |
| `deploy/initdb/01-extensions.sql` | 首次初始化数据库时创建 `pgcrypto`、`pg_trgm` 扩展（不建业务表） |
| `.env.example` | Compose 与后端共用的环境变量模板（变量名即前后端契约） |
| `scripts/dev-up.ps1` / `dev-up.sh` | 一键启动（含 Docker/端口检查、`.env` 兜底复制、健康检查等待） |
| `scripts/dev-down.ps1` / `dev-down.sh` | 一键停止（默认保留数据卷，可选删除） |
| `docs/deploy/local-dev.md` | 本文档 |

## 2. 前置条件

- Docker Desktop 4.x（Windows/macOS）或 Docker Engine 24+ with Compose v2（Linux）。
- 建议 4C8G 以上；首次拉取镜像约 1~3 分钟（受网络影响）。
- Windows 推荐 WSL2 后端；确认 `docker info` 可正常返回。

## 3. 快速开始

```powershell
# 仓库根目录执行
Copy-Item .env.example .env        # 首次；dev-up 脚本也会自动兜底复制
.\scripts\dev-up.ps1               # 仅内网启动，不占用宿主端口
.\scripts\dev-up.ps1 -ExposeDebug  # 需要 IDE 直连数据库/Redis/MinIO 时
```

```bash
cp .env.example .env
./scripts/dev-up.sh
./scripts/dev-up.sh --expose-debug
```

启动成功后脚本会输出连接信息，并自动创建 MinIO 附件桶（`MINIO_BUCKET`，默认 `aps-attachments`）。

常用参数：

| PowerShell | Bash | 说明 |
| --- | --- | --- |
| `-ExposeDebug` | `--expose-debug` | 叠加 expose 文件，端口仅绑定 `127.0.0.1` |
| `-SkipPull` | `--skip-pull` | 跳过镜像拉取（离线/已缓存） |
| `-NoWait` | `--no-wait` | 只 `up -d`，不等待健康检查（也不会自动建桶） |
| `-RemoveVolumes` | `--remove-volumes` | 停止时删除数据卷（会二次确认） |
| `-Force` | `--force` | 跳过删除卷的交互确认（自动化用） |

停止：

```powershell
.\scripts\dev-down.ps1                 # 保留数据
.\scripts\dev-down.ps1 -RemoveVolumes  # 连数据一起删除
```

## 4. 端口与数据卷规划

默认**不发布任何宿主端口**，三个服务只在 `aps-dev-aps-net` 内部互通（对齐架构页 5.1「内网」的规划）。
叠加 `docker-compose.dev.expose.yml` 后才发布到宿主机，且显式绑定 `127.0.0.1`，不会暴露到局域网。

| 服务 | 容器端口 | 默认宿主暴露 | `-ExposeDebug` 后（仅 127.0.0.1） | 数据卷 | 用途 |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL | 5432 | 无 | `POSTGRES_DEBUG_PORT`（默认 5432） | `aps-dev-pgdata` | 主业务库（`aps`） |
| Redis | 6379 | 无 | `REDIS_DEBUG_PORT`（默认 6379） | `aps-dev-redisdata` | 缓存 + Redis Stream |
| MinIO API | 9000 | 无 | `MINIO_API_DEBUG_PORT`（默认 9010） | `aps-dev-miniodata` | 附件 S3 API |
| MinIO Console | 9001 | 无 | `MINIO_CONSOLE_DEBUG_PORT`（默认 9011） | 同上 | 对象存储控制台 |
| minio-init | — | 无 | — | — | 一次性任务：等 MinIO 健康后建桶 |

说明：

- 网络名固定为 `aps-dev-aps-net`，数据卷固定为 `aps-dev-pgdata` / `aps-dev-redisdata` / `aps-dev-miniodata`（由 `COMPOSE_PROJECT_NAME` 派生，改工程名会得到一套全新卷）。
- `depends_on` + `condition: service_healthy`：`minio-init` 必须等 MinIO 健康后才执行；后续 `aps-backend` 容器加入时同样应依赖 postgres/redis 的 `service_healthy`。
- `minio-init` 是一次性任务（`restart: "no"`），启动脚本用 `docker compose run --rm` 触发，不参与 `up --wait` 的等待判定。

## 5. 环境变量契约

`.env.example` 分为两组：Compose 读取的变量、后端读取的变量。

| 变量 | 读取方 | 说明 |
| --- | --- | --- |
| `COMPOSE_PROJECT_NAME` | Compose | 工程名，决定容器/网络/卷名前缀，默认 `aps-dev` |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | PostgreSQL 容器 | 初始库名/用户/密码（必填） |
| `REDIS_PASSWORD` | Redis 容器 | `--requirepass`，必填；健康检查复用同一变量 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | MinIO 容器 | 根账号，密码必填 |
| `MINIO_BUCKET` | minio-init | 附件桶名，默认 `aps-attachments` |
| `MINIO_IMAGE` | Compose | 可选，覆盖 MinIO 镜像（内网 Harbor / 自建镜像） |
| `POSTGRES_DEBUG_PORT` 等 4 个 | expose 文件 | 仅 `-ExposeDebug` 生效 |

后端变量（由未来的 `application-dev.yml` 使用 `${...}` 占位符对接，本任务不修改该文件）：

| 变量 | 对应配置项（建议） |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.username` / `password` |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD` | `spring.data.redis.*`（Spring Boot 3.2+；2.x 用 `SPRING_REDIS_*`） |
| `APS_MINIO_ENDPOINT` / `_ACCESS_KEY` / `_SECRET_KEY` / `_BUCKET` | `aps.storage.minio.*` |
| `APS_JWT_SECRET` / `APS_JWT_EXPIRE_MINUTES` | `aps.security.jwt.secret` / `expire-minutes`（secret ≥ 32 字节） |

注意事项：

- `.env` 中不存在变量间插值，`POSTGRES_PASSWORD` 与 `SPRING_DATASOURCE_PASSWORD`、`REDIS_PASSWORD` 与 `SPRING_DATA_REDIS_PASSWORD`、`MINIO_ROOT_*` 与 `APS_MINIO_*` **需要手动保持一致**。
- 后端变量里的 host 写 `localhost`，前提是后端跑在宿主机（IDE）且叠加了 expose 文件；若后端之后也进 compose，host 应改为 `postgres` / `redis` / `minio`。
- 启动脚本遇到缺失的 `.env` 会自动从 `.env.example` 复制；**上线/共享前务必替换所有默认密码与 JWT secret**。

## 6. 验证与常用命令

```powershell
# 容器状态（含健康检查结果）
docker compose --env-file .env -f deploy/docker-compose.dev.yml ps

# PostgreSQL 扩展（pgcrypto / pg_trgm 应存在）
docker compose --env-file .env -f deploy/docker-compose.dev.yml exec postgres psql -U aps -d aps -c "\dx"

# Redis
docker compose --env-file .env -f deploy/docker-compose.dev.yml exec redis redis-cli -a redis_dev_pwd --no-auth-warning ping

# MinIO 健康端点（容器内）
docker compose --env-file .env -f deploy/docker-compose.dev.yml exec minio curl -fsS http://127.0.0.1:9000/minio/health/live

# 已创建的附件桶
docker compose --env-file .env -f deploy/docker-compose.dev.yml run --rm --no-deps -T minio-init mc ls local

# 日志
docker compose --env-file .env -f deploy/docker-compose.dev.yml logs -f postgres redis minio
```

叠加 expose 后，宿主机可直接访问：`jdbc:postgresql://localhost:5432/aps`、`localhost:6379`、`http://localhost:9010`、控制台 `http://localhost:9011`。

## 7. 镜像版本与代理

| 服务 | 镜像 | 说明 |
| --- | --- | --- |
| PostgreSQL | `postgres:15` | 官方稳定主版本 tag，自带 contrib 扩展（含 pgcrypto / pg_trgm） |
| Redis | `redis:7` | 官方稳定主版本 tag，开启 AOF + 512MB allkeys-lru |
| MinIO | `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` | 固定 RELEASE tag（原因见下） |

**关于 MinIO 镜像**：MinIO 官方社区镜像已从 Docker Hub 下架（`minio/minio` 仓库返回 404），`quay.io/minio/minio` 匿名拉取返回 401（需授权），官方社区分发已于 2025 年停止。为保证新人开箱可用，默认改用同一代码线的社区维护分支 `pgsty/minio` 的**固定 RELEASE tag**（该镜像内置 `minio`、`mc`、`curl`，ENV/命令/健康端点与官方一致，已验证镜像构建历史）。如公司内网 Harbor 有官方镜像或自建镜像，在 `.env` 设置：

```dotenv
MINIO_IMAGE=harbor.example.com/minio/minio:RELEASE.2025-09-07T16-13-09Z
```

集成环境应统一替换为受控镜像仓库的固定 digest/tag，避免 `latest` 漂移。

**拉取慢时走 clash 代理**（`127.0.0.1:7890`）：镜像拉取由 Docker 守护进程发起，命令行 `HTTP_PROXY` 不生效，需要在 Docker Desktop 里配置：
`Settings → Resources → Proxies → Manual → Web Server (HTTP) / Secure Web Server (HTTPS)` 均填 `http://127.0.0.1:7890`，Apply & Restart。
Linux/Docker Engine 则给 dockerd 配 `HTTP_PROXY`/`HTTPS_PROXY` systemd 环境变量后重启。

## 8. 常见问题

1. **`docker info` 失败 / `npipe dockerDesktopLinuxEngine` 不存在**
   Docker Desktop 未启动或 WSL2 后端未就绪：启动后等 1~2 分钟；仍失败执行 `wsl -l -v` 确认 `docker-desktop` 为 Running，必要时 `wsl --shutdown` 后重启 Docker Desktop。
2. **`-ExposeDebug` 报端口冲突**
   脚本会列出占用 PID/进程名。改 `.env` 的 `*_DEBUG_PORT`，或停止本机已有的 PostgreSQL/Redis；不加 `-ExposeDebug` 时不会占用宿主端口。
3. **镜像拉取失败 / 超时 / 401**
   先确认代理配置（见上一节）；`quay.io` 的 MinIO 官方镜像需要授权，本项目已默认改用可匿名拉取的镜像；内网环境用 `MINIO_IMAGE` 指向 Harbor。
4. **healthcheck 一直不通过**
   `docker compose ... logs postgres redis minio` 看具体原因；PostgreSQL 首次启动初始化耗时较长（`start_period: 30s`），Redis 密码不一致会导致 `NOAUTH/WRONGPASS`。
5. **修改 `deploy/initdb/01-extensions.sql` 后没生效**
   初始化脚本只在数据卷为空时执行。需要重跑：`.\scripts\dev-down.ps1 -RemoveVolumes` 后再 `dev-up`。
6. **Windows 下 MinIO 卷 IO 慢 / 内存不足**
   把项目放在非网络盘；Docker Desktop 分配 ≥4GB 内存；WSL2 可在 `%UserProfile%\.wslconfig` 调 `memory=6GB`。
7. **忘记密码**
   密码在 `.env`；已初始化过的数据卷不会因改 `.env` 而改库内密码，需重置数据卷（见下节）或手动改库。

## 9. 重置数据卷

```powershell
# 方式一：停止并删除本栈全部数据卷（推荐，脚本会二次确认）
.\scripts\dev-down.ps1 -RemoveVolumes

# 方式二：只删某个卷（先停栈）
docker compose --env-file .env -f deploy/docker-compose.dev.yml down --remove-orphans
docker volume rm aps-dev-pgdata        # 或 aps-dev-redisdata / aps-dev-miniodata

# 清空后重新启动，PostgreSQL 会重新执行 initdb 扩展脚本，MinIO 会重建附件桶
.\scripts\dev-up.ps1
```

验证重建结果：

```powershell
docker compose --env-file .env -f deploy/docker-compose.dev.yml exec postgres psql -U aps -d aps -c "SELECT extname, extversion FROM pg_extension WHERE extname IN ('pgcrypto','pg_trgm');"
```

## 10. 本次实际验证记录（2026-11-03）

本机 `docker` CLI 与 Compose v2（v5.5.1）存在，但 Docker 守护进程未运行（`com.docker.service` 处于 Stopped，`dockerDesktopLinuxEngine` npipe 不存在，无权限启动服务），因此**未能实际起栈、拉镜像与执行健康检查**。已完成的验证：

| 命令 | 结果 |
| --- | --- |
| `docker compose --env-file .env.example -f deploy/docker-compose.dev.yml config --quiet` | 通过（exit 0） |
| `docker compose ... -f deploy/docker-compose.dev.expose.yml config --quiet` | 通过（exit 0），解析出 `127.0.0.1:5432/6379/9010/9011` |
| `docker compose ... config`（检查网络/卷/条件） | `aps-dev-aps-net`、`aps-dev-pgdata/redisdata/miniodata`、`minio-init → condition: service_healthy` 均正确 |
| `powershell -File scripts/dev-up.ps1` | 按预期在守护进程检查处退出，输出可读中文排查建议（exit 1） |
| `powershell -File scripts/dev-down.ps1` | 同上（exit 1） |
| `bash -n scripts/dev-up.sh` / `dev-down.sh` | 语法检查通过（exit 0） |
| `bash scripts/dev-up.sh --expose-debug` | 参数解析正常，按预期在守护进程检查处退出（exit 1） |
| 镜像可用性 | `postgres:15`、`redis:7`、`pgsty/minio:RELEASE.2026-08-04T00-00-00Z` 均经 registry 校验存在；通过 registry config 确认 MinIO 镜像内置 `minio` / `mc` / `curl` |

有 Docker 环境的机器上，可复现的完整验收命令：

```powershell
Copy-Item .env.example .env
.\scripts\dev-up.ps1 -ExposeDebug
docker compose --env-file .env -f deploy/docker-compose.dev.yml ps
```

## 11. 边界与后续

- 本栈**只提供依赖服务**；`aps-backend` / `aps-frontend` 容器化分别由 P0-04（Dockerfile）及后续任务负责，本任务未创建 Dockerfile、未改 Maven/npm 工程。
- 数据库**业务 schema 由后端 Flyway（`db/migration`）管理**，`deploy/initdb` 仅创建扩展，避免与迁移脚本冲突。
- `.env.example` 中后端变量名为契约，`application*.yml` 由后端负责改造，本任务未触碰。
- 待后端容器化后，将在 compose 中新增 `aps-backend`，并声明 `depends_on: postgres/redis → condition: service_healthy`。
