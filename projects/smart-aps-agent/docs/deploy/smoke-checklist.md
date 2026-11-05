# 一键整栈冒烟验收清单

> 对应任务：M1-QA-02「Docker Compose 编排」
> 编排文件：`deploy/docker-compose.yml`；启动脚本：`scripts/stack-up.ps1` / `stack-up.sh`
> 目标：单机一键起栈后，按本清单逐项确认前端、后端、Flyway、MinIO 均可用。

## 0. 前置条件

- Docker Desktop 4.x / Docker Engine 24+ with Compose v2，`docker info` 正常。
- 仓库根目录存在 `.env`（缺失时 `stack-up` 会自动从 `.env.example` 复制）。
- 宿主端口 80、8080 未被占用（或已在 `.env` 设置 `APS_FRONTEND_PORT` / `APS_BACKEND_PORT`）。
- 首次执行需要拉取 `postgres:15`、`redis:7`、MinIO 镜像并构建前后端镜像，耗时取决于网络与缓存。

## 1. 起栈

```powershell
# 仓库根目录
.\scripts\stack-up.ps1
```

预期：脚本依次输出「检查 Docker CLI / 守护进程 / Compose v2」「准备 .env」「检查入口端口占用」「拉取中间件镜像」「构建镜像、启动容器并等待健康检查」「初始化 MinIO 附件桶」，最后输出前端与后端地址，退出码 0。

> 所有手动命令都必须带 `--project-name aps-stack`。根 `.env` 的 `COMPOSE_PROJECT_NAME=aps-dev` 会覆盖 compose 顶层 `name`，省略工程名会与 dev 栈混用。

为方便复制，下文命令统一使用：

```powershell
$dc = "docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml"
```

## 2. 验收项

| 编号 | 检查项 | 通过标准 |
| --- | --- | --- |
| SMK-01 | 容器状态 | 5 个长期服务均为 `Up ... (healthy)` |
| SMK-02 | 前端入口 | `GET http://localhost/` 返回 200，响应为 SPA 的 `index.html` |
| SMK-03 | 后端直连健康 | `GET http://localhost:8080/api/v1/health` 返回 200，`code=0`、`data.status=UP` |
| SMK-04 | Nginx `/api` 反代 | `GET http://localhost/api/v1/health` 与 SMK-03 结果一致 |
| SMK-05 | Flyway 迁移记录 | `flyway_schema_history` 有 1 条 `V1`，`success = t` |
| SMK-06 | 业务表数量 | 空库首次迁移后 `public` 下业务表 **28** 张（不含 `flyway_schema_history`） |
| SMK-07 | MinIO 附件桶 | `minio-init` 输出 `bucket ready: aps-attachments`；`mc ls local` 可见该桶 |
| SMK-08 | 后端启动日志 | Flyway 有 `Successfully applied 1 migration`，无 `ERROR` 级别异常 |

### SMK-01 容器状态

```powershell
Invoke-Expression "$dc ps"
# 或只看关键列
Invoke-Expression "$dc ps --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}'"
```

期望（名称可能因版本略有差异）：

```text
aps-stack-postgres    postgres     Up ... (healthy)
aps-stack-redis       redis        Up ... (healthy)
aps-stack-minio       minio        Up ... (healthy)
aps-stack-backend     aps-backend  Up ... (healthy)
aps-stack-frontend    aps-frontend Up ... (healthy)
```

`minio-init` 是一次性任务（`profiles: init`），不会出现在默认 `ps` 中，属正常。

> 前端运行镜像是 `nginxinc/nginx-unprivileged:1.31-alpine`，容器内监听 **8080**；宿主 `${APS_FRONTEND_PORT:-80}` 映射到容器 8080。镜像/compose 健康检查均打 `http://127.0.0.1:8080/healthz`，`ps` 中显示 `(healthy)` 即表示该检查已通过。

### SMK-02 前端入口（80）

```powershell
curl.exe -s -o NUL -w "HTTP %{http_code}`n" http://localhost/
curl.exe -s http://localhost/ | Select-String -Pattern "<div id=`"app`"></div>|<title>"
```

期望：`HTTP 200`；返回 HTML 含应用挂载点（Vue 构建产物）。若端口改过，把 `localhost` 换成 `localhost:$env:APS_FRONTEND_PORT` 或实际端口。Nginx 容器内为 8080（非 root 监听），宿主 80 只是映射，直接 `curl http://localhost:8080` 访问的是后端而非前端。

### SMK-03 后端直连健康（8080）

```powershell
curl.exe -s -w "`nHTTP %{http_code}`n" http://localhost:8080/api/v1/health
```

期望：

```json
{"code":0,"message":"success","data":{"status":"UP","service":"aps-admin"},"traceId":"..."}
HTTP 200
```

### SMK-04 Nginx `/api` 反代

```powershell
curl.exe -s -w "`nHTTP %{http_code}`n" http://localhost/api/v1/health
```

期望与 SMK-03 一致。若 502，说明 `APS_BACKEND_URL` 或后端健康检查有问题，见第 4 节。

### SMK-05 Flyway 迁移记录

```powershell
Invoke-Expression "$dc exec -T postgres psql -U aps -d aps -c `"SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;`""
```

期望：

```text
 version | description | success
---------+-------------+---------
 1       | init        | t
```

> 若 `.env` 改过 `POSTGRES_USER` / `POSTGRES_DB`，把命令中的 `aps` 换成实际值。

### SMK-06 业务表 28 张

```powershell
Invoke-Expression "$dc exec -T postgres psql -U aps -d aps -c `"SELECT count(*) AS business_tables FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history';`""
```

期望 `business_tables = 28`。若为 0，说明迁移未生效（优先检查后端日志与第 8 节问题 1）。

补充核对（总数应为 29 = 28 业务表 + Flyway 历史表）：

```powershell
Invoke-Expression "$dc exec -T postgres psql -U aps -d aps -c `"\dt`""
```

### SMK-07 MinIO 附件桶

```powershell
Invoke-Expression "$dc --profile init run --rm --no-deps -T minio-init"
```

期望输出：

```text
[minio-init] bucket ready: aps-attachments
```

也可在 MinIO 容器内确认：

```powershell
Invoke-Expression "$dc exec -T minio mc alias set local http://127.0.0.1:9000 apsminio aps_dev_minio_pwd"
Invoke-Expression "$dc exec -T minio mc ls local"
```

期望列表中有 `aps-attachments/`（账号密码取 `.env` 的 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`）。

### SMK-08 后端启动日志

```powershell
Invoke-Expression "$dc logs --tail 200 aps-backend" | Select-String -Pattern "Flyway|Successfully applied|ERROR|Exception"
```

期望：出现 `Successfully applied 1 migration`；不出现 `ERROR` / 未捕获异常。首次启动还可能出现 `Migrating schema "public" to version "1 - init"`，属正常。

## 3. 重启与数据保留（可选）

```powershell
.\scripts\stack-down.ps1      # 保留卷
.\scripts\stack-up.ps1
```

再次执行 SMK-05 / SMK-06：迁移记录仍为 V1（不会重复执行），业务表仍为 28，MinIO 桶仍存在。若要彻底清空：

```powershell
.\scripts\stack-down.ps1 -RemoveVolumes
```

## 4. 失败排查

| 现象 | 优先排查 |
| --- | --- |
| `stack-up` 在「检查 Docker 守护进程」退出 | Docker Desktop 未启动 / WSL2 后端未就绪；启动后等 1~2 分钟 |
| 端口 80 / 8080 冲突 | 改 `.env` 的 `APS_FRONTEND_PORT` / `APS_BACKEND_PORT`；Windows 80 常被 IIS/http.sys 占用 |
| 前端 502 | `logs aps-frontend`；确认 `APS_BACKEND_URL=http://aps-backend:8080`；`exec aps-frontend wget -qO- http://aps-backend:8080/api/v1/health` |
| 后端不健康 / 反复重启 | `logs aps-backend`；迁移脚本已由 Dockerfile `COPY db/migration` 打进镜像，Flyway 应从 `classpath:db/migration` 加载；确认没有复用旧镜像 |
| 业务表为 0 | 确认后端镜像是重新构建的（`aps-admin/Dockerfile` 已 `COPY db/migration`，`stack-up` 默认带 `--build`）；再看 `logs aps-backend` 中 Flyway 的迁移记录；compose 已不再挂载宿主机迁移目录 |
| 健康接口 200 但业务表缺失 | `/api/v1/health` 只是存活检查，不查库；必须以 SMK-05/06 为准 |
| MinIO 建桶失败 | `logs minio`；确认所用镜像包含 `mc`（默认 `pgsty/minio` 包含） |
| 镜像拉取超时 | 为 Docker Desktop 配置 clash 代理 `http://127.0.0.1:7890`；或 `stack-up -SkipPull` |

## 5. 本次执行记录（2026-11-04）

**未实机起栈**：本机 `com.docker.service` 未启动、无提权，`docker info` 无法连接 `npipe:////./pipe/dockerDesktopLinuxEngine`。因此 SMK-01 ~ SMK-08 均未实际执行，仅完成静态校验（compose config、YAML、`bash -n`、PowerShell AST），详见 [docker-compose.md](docker-compose.md) 第 9 节。

**Dockerfile 修复后复核**：`deploy/docker-compose.yml` 已移除 Flyway 挂载兜底，前端改为宿主 80 → 容器 8080、健康检查 `127.0.0.1:8080/healthz`；compose config（含 `--profile init`）与 PyYAML 解析均通过。SMK 项仍待在具备 Docker 守护进程的机器上实机执行。

有 Docker 环境的机器上按以下命令即可完整验收：

```powershell
Copy-Item .env.example .env
.\scripts\stack-up.ps1
.\scripts\stack-down.ps1 -RemoveVolumes
```
