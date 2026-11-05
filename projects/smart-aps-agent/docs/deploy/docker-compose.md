# 一键整栈（Docker Compose）

> 任务：M1-QA-02「Docker Compose 编排」建立；M2-QA-03「Docker 一键部署」补齐干净环境实测、分阶段计时、必填变量硬校验与离线包。
> 适用范围：单机一条命令拉起 PostgreSQL 15 / Redis 7 / MinIO / aps-backend / aps-frontend，用于联调与冒烟验收。
> 架构依据：[aps-arch-page](../../aps-arch-page/public/index.html) 5.1「Docker Compose 服务清单与端口规划」（只读，不改动）。
> 相关文档：[local-dev.md](local-dev.md)（只含中间件的本地开发栈，本任务未改动该文件）。

> **M2-QA-03 关键变更：部署链路不再编译业务代码。**
> 后端改为「运行时镜像 + 只读挂载 W9 交付的 fat jar」，前端改为「Nginx 镜像 + 只读挂载 `aps-frontend/dist`」。
> 好处：① 部署耗时从「Maven/npm 全量构建」降到分钟级；② 容器内运行的 jar 与仓库制品逐字节一致（SHA256 可核对）；③ 避免在部署机上并发打包覆盖他人 `target/`。
> 源码构建文件 `aps-admin/Dockerfile`、`aps-frontend/Dockerfile` 仍保留，仅不在一键部署链路中使用。

## 1. 与 dev 基础设施栈的区别

| | `deploy/docker-compose.dev.yml` | `deploy/docker-compose.yml`（本任务） |
| --- | --- | --- |
| 编排范围 | 仅 PostgreSQL / Redis / MinIO | 中间件 + aps-backend + aps-frontend（+ 可选 minio-init） |
| 使用者 | 宿主机 IDE 中运行前后端的开发者 | 需要「一条命令得到可访问环境」的联调 / 验收 |
| 工程名 | `aps-dev`（`COMPOSE_PROJECT_NAME`） | 固定 `aps-stack` |
| 宿主端口 | 默认不发布，调试时叠加 expose 文件并只绑 `127.0.0.1` | 前端 80、后端 8080；中间件仅内网 |
| 启动脚本 | `scripts/dev-up.*` / `dev-down.*` | `scripts/stack-up.*` / `stack-down.*` |

**为什么新增 `stack-*` 而不是复用 `dev-*`**：两者编排的服务集合、端口策略、工程名和是否构建镜像都不同；复用会让 dev 脚本出现「是否要构建前后端」的分支，容易误启动/误停栈。新脚本沿用 dev 脚本的检查项、中文错误提示与参数命名，学习成本不变。

## 2. 交付文件

| 文件 | 作用 |
| --- | --- |
| `deploy/docker-compose.yml` | 一键整栈编排：服务、网络、命名卷、健康检查、依赖条件、日志轮转 |
| `deploy/backend/Dockerfile` | 后端「运行时」镜像：JRE 21 + curl，不含业务代码；jar 由 compose 只读挂载 |
| `scripts/stack-up.ps1` / `stack-up.sh` | 一键启动：Docker/.env/制品/端口前置检查 → 分阶段计时 → 拉镜像 → 构建运行时镜像 → 起容器 → 等 Flyway → 等健康 200 → 起前端 → MinIO 建桶 → SHA256 核对 |
| `scripts/stack-bundle.ps1` / `stack-bundle.sh` | 生成离线部署包（镜像 tar + jar + dist + 编排/脚本 + MANIFEST）；默认输出到**仓库外**的 `..\_aps-artifacts\dist-offline`，工作区不保留约 548MB 产物 |
| `scripts/stack-down.ps1` / `stack-down.sh` | 一键停止（默认保留数据卷，可选删除） |
| `docs/deploy/docker-compose.md` | 本文档：拓扑、端口、卷、环境变量对应关系、实测验证记录 |
| `docs/deploy/smoke-checklist.md` | 起栈后的验收清单与可复现命令 |

未改动的既有文件：`aps-admin/Dockerfile`、`aps-frontend/Dockerfile`、`aps-frontend/nginx/default.conf.template`、`.env.example`、`application*.yml`、`db/migration`、`deploy/docker-compose.dev*.yml`、`docs/deploy/local-dev.md`。

## 3. 服务、端口与卷拓扑

```text
                宿主机
  :80  ┌─────────────────────────┐
       │ aps-frontend (nginx)    │  /api/* 反代到 aps-backend:8080
       └───────────┬─────────────┘
                   │ aps-stack-aps-net（仅此 bridge 网络）
  :8080 ┌──────────┴─────────────┐
        │ aps-backend (Spring)   │  Flyway 启动迁移
        └───┬────────┬────────┬──┘
            │        │        │
      postgres   redis    minio        （均不发布宿主端口）
       5432      6379    9000/9001
```

| 服务 | 构建 / 镜像 | 容器端口 | 宿主映射 | 命名卷 | 健康检查 | depends_on |
| --- | --- | --- | --- | --- | --- | --- |
| `aps-frontend` | `nginxinc/nginx-unprivileged:1.31-alpine`；挂载 `aps-frontend/dist` 与 nginx 模板 | 8080 | `${APS_FRONTEND_PORT:-80}` → 容器 8080 | — | `wget http://127.0.0.1:8080/healthz` | `aps-backend: service_healthy` |
| `aps-backend` | `deploy/backend/Dockerfile`（JRE 21 + curl，无业务代码）；只读挂载 `${APS_BACKEND_JAR:-../aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar}` → `/app/app.jar` | 8080 | `${APS_BACKEND_PORT:-8080}` | — | `curl /api/v1/health` | postgres/redis/minio `service_healthy` |
| `postgres` | `postgres:15` | 5432 | 无（内网） | `aps-stack-pgdata` | `pg_isready` | — |
| `redis` | `redis:7` | 6379 | 无（内网） | `aps-stack-redisdata` | `redis-cli ping` | — |
| `minio` | `${MINIO_IMAGE:-pgsty/minio:RELEASE.2026-08-04T00-00-00Z}` | 9000 / 9001 | 无（内网） | `aps-stack-miniodata` | `curl /minio/health/live` | — |
| `minio-init` | 同 MinIO 镜像（内置 `mc`） | — | — | — | —（一次性任务） | `minio: service_healthy` |

说明：

- 网络固定为 `aps-stack-aps-net`，容器名固定为 `aps-stack-*`，卷固定为 `aps-stack-*`，与 dev 栈的 `aps-dev-*` 完全隔离。
- `minio-init` 放在 `profiles: ["init"]`，避免普通 `up --wait` 把「已正常退出的一次性容器」判为未就绪；由 `stack-up` 显式执行 `--profile init run --rm --no-deps -T minio-init`。
- 后端日志按 `logback-spring.xml` 统一输出到 stdout，因此不创建架构页中的 `logs` 卷；容器日志由 `json-file` 驱动轮转（`max-size=10m`、`max-file=3`）。
- 中间件全部只 `expose`、不 `ports`，符合架构页「仅前端 80 对外，backend/db/redis/minio 内网」的要求；后端 8080 按任务要求发布，架构页允许「8080 或仅内网」。
- 前端运行镜像为 `nginxinc/nginx-unprivileged:1.31-alpine`（uid 101），只能监听非特权端口，因此**容器内监听 8080**，宿主 `${APS_FRONTEND_PORT:-80}` 映射到容器 8080；容器健康检查也打 `127.0.0.1:8080/healthz`。
- `aps-backend` 的 `start_period` 为 90s，给首次 Flyway 迁移（V1–V6，共 32 张业务表）留足冷启动时间。
- 后端/前端只读挂载的路径相对 compose 文件解析：`../aps-admin/target/...`、`../aps-frontend/dist`；脚本会先校验 jar 存在、体积 >50MB、`BOOT-INF/lib` 非空，避免拿瘦包启动。

### 3.1 工程名必须显式指定（重要）

根目录 `.env` 中的 `COMPOSE_PROJECT_NAME=aps-dev` **优先级高于 compose 文件里的顶层 `name`**。若直接用 `docker compose -f deploy/docker-compose.yml up`，该栈会被归入 `aps-dev` 工程，`down --remove-orphans` 可能把 dev 栈的容器当作孤儿删除。

因此 `stack-up.*` / `stack-down.*` 与本文所有手动命令都显式带 `--project-name aps-stack`。手动执行时不要省略。

## 4. 快速开始

```powershell
# 仓库根目录执行（前置：aps-admin/target 下已有 W9 交付的 fat jar，aps-frontend/dist 已有前端产物）
.\scripts\stack-up.ps1            # 拉镜像 + 构建运行时镜像 + 启动 + 等 Flyway/健康 200 + 建桶 + 打印分阶段计时
.\scripts\stack-up.ps1 -SkipPull  # 镜像已缓存/离线包已导入
.\scripts\stack-up.ps1 -SkipBuild # 运行时镜像已存在
.\scripts\stack-up.ps1 -NoWait    # 只 up -d，不等健康检查、不建桶
.\scripts\stack-bundle.ps1        # 生成离线部署包（默认仓库外 ..\_aps-artifacts\dist-offline\）
.\scripts\stack-down.ps1          # 停止，保留数据卷
.\scripts\stack-down.ps1 -RemoveVolumes
```

```bash
./scripts/stack-up.sh
./scripts/stack-up.sh --skip-pull
./scripts/stack-down.sh
./scripts/stack-down.sh --remove-volumes
```

脚本会：检查 Docker CLI / 守护进程 / Compose v2 → 缺失 `.env` 时从 `.env.example` 复制 → **校验 4 个必填变量非空**（缺任一立即 exit 1，不做默认值兜底）→ **校验 jar/dist 制品**（缺失、瘦包、`BOOT-INF/lib` 为空都中止）→ 检查入口端口占用 → 拉镜像 → 构建后端运行时镜像 → 启动中间件与后端 → 等 Flyway 完成 → 等 `/api/v1/health` 首次 200 → 启动前端 → 核对容器内 jar 的 SHA256 → 建 MinIO 桶 → 打印各阶段秒数与总耗时。守护进程未运行或必填变量缺失时都不会留下半启动状态。

手动执行等价命令：

```powershell
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml build aps-backend
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml up -d postgres redis minio
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml up -d aps-backend
# 等日志出现 Flyway 完成、等健康 200 后再起前端
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml up -d aps-frontend
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml `
  --profile init run --rm --no-deps -T minio-init
```

## 5. 环境变量与 `.env.example` 的对应关系

本栈不修改 `.env.example`（属 P0-06 交付物），直接复用其中的变量名；Compose 在启动时把「宿主机视角」的地址改写成容器内服务名。

| `.env.example` 变量 | 本栈消费方 | 本栈中的实际取值 / 说明 |
| --- | --- | --- |
| `COMPOSE_PROJECT_NAME` | Compose | **不用于本栈**；本栈固定 `--project-name aps-stack`，避免与 dev 栈混用 |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | postgres、aps-backend | 初始化库/账号；后端同时注入 `DB_*` 与 `SPRING_DATASOURCE_*` |
| `REDIS_PASSWORD` | redis、aps-backend | `--requirepass` 与 `SPRING_DATA_REDIS_PASSWORD` 共用 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | minio、minio-init、aps-backend | 根账号；后端的 `APS_MINIO_ACCESS_KEY/SECRET_KEY` 由这两个值派生 |
| `MINIO_BUCKET` | minio-init、aps-backend | 默认 `aps-attachments` |
| `MINIO_IMAGE` | Compose | 覆盖 MinIO 镜像（内网 Harbor / 自建）；需同时提供 `mc` 供 minio-init 使用 |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | aps-backend | `.env` 中的 `localhost` 版本**不直接使用**；Compose 改写为 `jdbc:postgresql://postgres:5432/${POSTGRES_DB}`，用户名/密码取 `POSTGRES_*` |
| `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` | aps-backend | host 改写为 `redis`，端口 `6379`，密码取 `REDIS_PASSWORD` |
| `APS_MINIO_ENDPOINT` | aps-backend | 改写为 `http://minio:9000` |
| `APS_MINIO_ACCESS_KEY` / `_SECRET_KEY` / `_BUCKET` | aps-backend | 由 `MINIO_ROOT_*` / `MINIO_BUCKET` 派生（请保持两者一致） |
| `APS_JWT_SECRET` / `APS_JWT_EXPIRE_MINUTES` | aps-backend | 原样注入，secret 为必填 |
| `POSTGRES_DEBUG_PORT` 等 4 个 | — | 本栈不发布中间件端口，不使用 |

本栈新增的可选变量（不写入 `.env.example`，需要时手工追加到 `.env`；未设置则用默认值）：

```dotenv
# 前端/后端宿主端口（默认 80 / 8080；前端容器内固定监听 8080）
APS_FRONTEND_PORT=80
APS_BACKEND_PORT=8080
# Flyway 自动迁移开关（默认 true；排障时可临时关掉）
APS_FLYWAY_ENABLED=true
# Spring profile（默认 dev，用于加载 application-dev.yml 的 Flyway 配置）
APS_SPRING_PROFILES=dev
# 自定义镜像名（便于内网仓库/离线包；前端镜像默认直接用 nginx-unprivileged）
APS_BACKEND_IMAGE=aps-stack/aps-backend:local
APS_FRONTEND_IMAGE=nginxinc/nginx-unprivileged:1.31-alpine
# 制品路径覆盖（默认 aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar 与 aps-frontend/dist）
APS_BACKEND_JAR=aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar
APS_FRONTEND_DIST=aps-frontend/dist
```

必填变量（缺失时 Compose 会直接报错并提示复制 `.env.example`）：`POSTGRES_PASSWORD`、`REDIS_PASSWORD`、`MINIO_ROOT_PASSWORD`、`APS_JWT_SECRET`。

**当前后端配置消费情况**：`application.yml` / `application-dev.yml` 实际读取的是 `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` 与 `SPRING_*` 标准变量；Redis、MinIO、JWT 相关变量目前只是按 `.env.example` 契约注入，后端尚未引入对应客户端/配置项。变量名改名需前后端同步。

### 5.1 开发默认口令与密钥：仅限本地与 CI

- `aps_dev_pwd`（本地库 / CI 服务容器口令）、`dev-write-key` / `dev-read-key`（鉴权联调用例密钥）、`DEFAULT_JWT_SECRET`（`ApiSecurityProperties` 的 dev 默认值）都属于**开发默认值，仅限本地开发与 CI 使用**。
- 进入生产环境前必须全部改为环境变量注入，且不得沿用这些默认值；生产口令与密钥由部署环境提供，不进仓库。
- 本编排中的 `${POSTGRES_PASSWORD}`、`${REDIS_PASSWORD}`、`${MINIO_ROOT_PASSWORD}`、`${APS_JWT_SECRET}` 均为**必填占位**，缺失时 Compose 启动即失败，不允许静默回落到默认值。

## 6. Flyway 自动迁移

- `SPRING_PROFILES_ACTIVE=dev` 加载 `application-dev.yml`（`spring.flyway.enabled=true`、location `classpath:db/migration`）。
- `SPRING_FLYWAY_ENABLED` 提供独立的开关（默认 `true`）。
- M2-QA-03 起迁移脚本来自**挂载的 fat jar 自身**的 `BOOT-INF/classes/db/migration`（`aps-admin/pom.xml` 打包时已包含 `db/migration`），实测 jar 内含 V1–V6 共 6 个迁移；compose 不挂载宿主机迁移目录，也不设置 `SPRING_FLYWAY_LOCATIONS`。
- 更新 `db/migration` 后需要 W9 重新交付 fat jar（不是重新构建镜像）；部署脚本只负责挂载，不编译。
- 空库首次启动预期（实测）：`Schema history table ... does not exist yet` → `Successfully validated 6 migrations` → `Current version ... << Empty Schema >>` → `Successfully applied 6 migrations ... now at version v6`；`public` 下业务表 32 张（不含 `flyway_schema_history`），历史表 6 行。验收见 [smoke-checklist.md](smoke-checklist.md)。

## 7. 日志与运维

- 所有服务使用 `json-file` + `max-size=10m` / `max-file=3`，单机长期运行不会写满磁盘。
- 后端 `logback-spring.xml`：非 prod 输出单行控制台日志，prod 输出 JSON，均由 `docker logs` 采集。
- 常用命令（均需带 `--project-name aps-stack`）：

```powershell
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml logs -f aps-backend aps-frontend
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml restart aps-backend
```

## 8. Dockerfile 对接问题清单（1~3 已修复，其余为观察项）

| # | 状态 | 文件 | 原问题 → 修复结果 / 现状 |
| --- | --- | --- | --- |
| 1 | 已修复 | `aps-admin/Dockerfile` | 原未复制 `db/migration`，镜像内没有迁移脚本 → 已新增 `COPY db/migration db/migration`，V1 随镜像进入 classpath；compose 已删除只读挂载与 `SPRING_FLYWAY_LOCATIONS` |
| 2 | 已修复 | `aps-frontend/Dockerfile` | 原默认 `APS_BACKEND_URL=http://aps-admin:8080` → 已改为 `http://aps-backend:8080`，与 compose 服务名一致 |
| 3 | 已修复 | `aps-frontend/Dockerfile` | 原 `nginx:alpine` 以 root 运行、容器端口 80 → 已改为 `nginxinc/nginx-unprivileged:1.31-alpine`，容器监听 8080；compose 改为宿主 80 → 容器 8080，健康检查同步 8080 |
| 4 | 保持 | `aps-admin/Dockerfile` | 非 root（uid 1001）、jar 名 `aps-admin-*.jar`、`curl` HEALTHCHECK 均正常，与 compose 健康检查一致 |
| 5 | 观察 | `application.yml` | `/api/v1/health` 只返回进程存活，不检查数据库/迁移状态；验收必须单独查表（见 smoke checklist） |
| 6 | 保持 | `aps-frontend/.dockerignore` | 已排除 `node_modules` / `dist`，构建上下文干净 |
| 7 | 观察 | `MINIO_IMAGE` 覆盖 | 若换成不含 `mc` 的镜像，`minio-init` 需改用独立的 `minio/mc` 镜像执行建桶 |

> **Dockerfile 已修复，原先的 Flyway 挂载兜底已移除**：`aps-backend` 不再声明 `../db/migration:/app/db/migration:ro` 卷，也不再设置 `SPRING_FLYWAY_LOCATIONS=filesystem:/app/db/migration`，迁移脚本由**挂载 jar 内的** `BOOT-INF/classes/db/migration` 提供。

## 9. 实机验证记录（M2-QA-03，2026-11-04 09:39–09:50）

### 9.1 环境

| 项 | 实测值 |
| --- | --- |
| Docker Server | `29.8.2 (linux/amd64)`，API `1.56` |
| Docker Compose | `v5.5.1` |
| 宿主 | Windows（Docker Desktop），仓库 `D:\tools\smart-aps-agent` |
| 验收端口 | 后端 `18090`、前端 `18091`（避开他人占用的 80/8080/18085/18087/18088；写入本地 `.env`，不入库） |
| 后端制品 | `aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`，181,522,139 B |
| jar SHA256 | `7D13922BB436C333058E72619B30F6BC306868A892546D17405371C2FD0A2BEF` |
| jar 内依赖 | `BOOT-INF/lib` 共 112 条目（111 个 jar + 目录条目自身），含 V1–V6 迁移 |

### 9.2 分阶段计时（`stack-up.ps1` 输出，单位：秒）

| 阶段 | 第一次（09:39 起） | 第二次（09:43 起，`down -v` 后全新卷） |
| --- | --- | --- |
| [1] 镜像拉取 | 3.5 | 4.4 |
| [2] 镜像构建（JRE+curl，无业务代码） | 61.4（首个构建含 apt 装 curl） | 2.2（层缓存） |
| [3] 容器启动（中间件 healthy + 后端容器创建） | 10.1 | 13.3 |
| [4] Flyway 完成 | 30.0 | 15.0 |
| [5] `/api/v1/health` 首次 200 | 2.8 | 5.0 |
| [6] 前端容器 healthy | 8.7 | 9.0 |
| **总耗时** | **129.8** | **61.4** |

> 口径说明：两次实测开始时本机已缓存镜像（冷拉取在准备阶段单独执行过，未纳入上面的秒数）。镜像总量约 1.4GB（postgres 633MB、temurin 408MB、minio 216MB、redis 170MB、nginx 91MB），冷拉取耗时只取决于网络。第二次 61.4s 是清空数据卷后的真实重建耗时。
> 等价 Bash 脚本 `scripts/stack-up.sh --skip-pull --skip-build` 也实测通过（50.8s，Flyway 走「已是最新」分支，输出 `SHA256 一致：7D13922B…`）。

### 9.3 干净环境复验（不靠旧卷）

```text
.\scripts\stack-down.ps1 -RemoveVolumes -Force   -> 容器与卷全部删除
docker volume ls --filter name=aps-stack         -> （空）
.\scripts\stack-up.ps1                           -> 总耗时 61.4s
```

第二次 Flyway 日志（证明空库重建，而非复用旧卷）：

```text
aps-stack-backend | JdbcTableSchemaHistory - Schema history table "public"."flyway_schema_history" does not exist yet
aps-stack-backend | DbValidate - Successfully validated 6 migrations (execution time 00:00.086s)
aps-stack-backend | DbMigrate - Current version of schema "public": << Empty Schema >>
aps-stack-backend | DbMigrate - Successfully applied 6 migrations to schema "public", now at version v6 (execution time 00:01.244s)
```

第二次库内实测：业务表 32 张（不含历史表），`flyway_schema_history` 6 行（rank 1..6，`success` 全为 `t`）。

### 9.4 运行状态与健康检查

`docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps`（第二次运行后，节选）：

```text
NAME                 IMAGE                                      STATUS                   PORTS
aps-stack-backend    aps-stack/aps-backend:local                Up (healthy)             0.0.0.0:18090->8080/tcp
aps-stack-frontend   nginxinc/nginx-unprivileged:1.31-alpine    Up (healthy)             0.0.0.0:18091->8080/tcp
aps-stack-minio      pgsty/minio:RELEASE.2026-08-04T00-00-00Z   Up (healthy)             9000-9001/tcp
aps-stack-postgres   postgres:15                                Up (healthy)             5432/tcp
aps-stack-redis      redis:7                                    Up (healthy)             6379/tcp
```

```text
GET http://127.0.0.1:18090/api/v1/health   -> HTTP 200
{"code":0,"message":"success","data":{"service":"aps-admin","status":"UP"},"traceId":"052a8a0f6eae4815b90c0a6a5be45925"}
GET http://127.0.0.1:18091/healthz         -> HTTP 200（前端 nginx）
GET http://127.0.0.1:18091/api/v1/health   -> HTTP 200（前端反代后端）
```

### 9.5 制品一致性（容器内 vs 仓库）

```text
$ (容器内) ls -l /app/app.jar       -> 181522139 B
$ (容器内) sha256sum /app/app.jar   -> 7d13922bb436c333058e72619b30f6bc306868a892546d17405371c2fd0a2bef
$ (宿主)   Get-FileHash <仓库 jar>  -> 7D13922BB436C333058E72619B30F6BC306868A892546D17405371C2FD0A2BEF
stack-up 脚本内置校验：[6] 阶段后打印 "SHA256 一致：7D13922B…"，不一致时直接 exit 1
```

容器内 JRE：`openjdk version "21.0.13" 2026-10-20 LTS`。后端镜像内**不含**任何业务 jar，运行时从宿主只读挂载，因此不存在「镜像里的旧包」问题。

### 9.6 必填变量缺失时的失败行为（负向实测）

临时从 `.env` 删除 `REDIS_PASSWORD` 后：

```text
[..] 校验 .env 必填变量
[ERROR] 必填变量缺失或为空：REDIS_PASSWORD
  - 请编辑 D:\tools\smart-aps-agent\.env，为上述变量填入非空值后重试
  - 模板可从 .env.example 复制；部署环节不会替你填充默认密码
  - 对应的 compose 变量声明带 :? 保护，缺失时 docker compose 也会直接报错
exit 1（未触碰任何运行中的容器）
```

同一份 `.env` 直接执行 `docker compose config` 也会失败：`required variable REDIS_PASSWORD is missing a value: REDIS_PASSWORD is required, copy .env.example to .env first`。测试后 `.env` 已还原。

### 9.7 离线部署包（`stack-bundle.ps1`，实测）

```text
产物：仓库外 D:\tools\_aps-artifacts\dist-offline\（546.6 MB；默认输出路径，不在 git 工作树内）
  MANIFEST.txt        jar_size=181522139
                      jar_sha256=7D13922BB436C333058E72619B30F6BC306868A892546D17405371C2FD0A2BEF
  images/aps-stack-images.tar    postgres:15 / redis:7 / pgsty/minio:RELEASE.2026-08-04T00-00-00Z
                                 / aps-stack/aps-backend:local / nginxinc/nginx-unprivileged:1.31-alpine
  deploy/ scripts/ aps-admin/target/ aps-frontend/dist/ .env.example docs/
docker load -i D:\tools\_aps-artifacts\dist-offline\images\aps-stack-images.tar -> exit 0，5 个镜像全部 Loaded
```

目标机器安装：`docker load -i images/aps-stack-images.tar` → 复制 `.env.example` 并替换 4 个必填变量 → `.\scripts\stack-up.ps1 -SkipPull`。
离线包目录结构与仓库一致，compose 相对挂载路径无需修改。

### 9.8 已知边界

- 前端 `aps-frontend/dist` 由前端负责人构建，属**会变动的制品**；compose 以只读挂载方式托管，容器始终读取当前 dist（nginx 每请求读文件）。离线包中的 dist 是打包时刻的快照（本次 MANIFEST 记录了 `index.html` 时间）。
- 冷镜像拉取未纳入上面的 129.8s / 61.4s；从零机器的总时间 = 冷拉取（网络相关）+ 上述一次运行时间。
- 本任务未执行 `mvn package`、未重新打镜像内 jar、未执行任何 git 写命令；未触碰 18085/18087/18088 端口。

### 9.9 复现命令

```powershell
Copy-Item .env.example .env          # 按需修改 4 个必填变量与 APS_*_PORT
.\scripts\stack-up.ps1               # 打印分阶段计时
docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps
curl.exe http://127.0.0.1:18090/api/v1/health
.\scripts\stack-down.ps1 -RemoveVolumes
.\scripts\stack-up.ps1               # 干净环境第二次实测
```

## 10. 常见问题

1. **端口 80 被占用**：Windows 常见于 IIS / `http.sys`；改 `.env` 的 `APS_FRONTEND_PORT` 后重试，脚本会在启动前检查并中止。
2. **`down` 担心误删 dev 栈**：必须带 `--project-name aps-stack`（脚本已内置）。不要省略工程名。
3. **后端反复重启**：`logs aps-backend` 看 Flyway。迁移脚本在挂载的 fat jar 内；若提示找不到 `db/migration`，确认 `APS_BACKEND_JAR` 指向的是 W9 交付的完整 jar（`BOOT-INF/classes/db/migration` 存在），不要指向瘦包。
4. **前端 502**：`docker compose ... exec aps-frontend wget -qO- http://aps-backend:8080/api/v1/health`；再确认 `APS_BACKEND_URL` 未被改成 `aps-admin`。
5. **镜像拉取慢/失败**：Docker Desktop → Settings → Resources → Proxies 配 `http://127.0.0.1:7890`；或用 `-SkipPull` / 内网 Harbor 的 `MINIO_IMAGE`。
6. **改密码后旧库不生效**：PostgreSQL 只在空卷初始化；改 `.env` 密码需 `stack-down.ps1 -RemoveVolumes` 后重建，或手工改库。

## 11. 边界与后续

- M2-QA-03 未修改 `aps-admin/Dockerfile`、`aps-frontend/Dockerfile`、pom、`application*.yml`、`db/migration`、CI 配置、前端源码；未执行 `mvn package`、未执行任何 git 写命令。
- M2-QA-03 修改的部署资产：`deploy/docker-compose.yml`（后端/前端改为镜像 + 只读挂载制品）、新增 `deploy/backend/Dockerfile`、`scripts/stack-up.*`（必填变量/制品/端口前置校验 + 分阶段计时 + SHA256 核对）、新增 `scripts/stack-bundle.*`、`.gitignore`（保留 `dist-offline/` 兜底忽略，实际默认输出已移到仓库外）、本文档。
- `minio-init` 依赖 MinIO 镜像内置的 `mc`；若通过 `MINIO_IMAGE` 换成不含 `mc` 的官方镜像，需要改用独立的 `minio/mc` 镜像执行建桶。
- 离线包（默认 `..\_aps-artifacts\dist-offline\`，仓库外）复用本编排与 `stack-*` 脚本，不需要改 `APS_*_IMAGE`；导入镜像后加 `-SkipPull` 即可。仓库内可用 `.\scripts\stack-bundle.ps1 -OutputDir <路径>` 指定其他输出位置。
- 若后续改为「镜像内自带 jar」的发布形态，需要同时维护 SHA256 校验与镜像版本号；当前选择挂载制品的方案是为了让「运行的是哪份 jar」可被逐字节证明。
