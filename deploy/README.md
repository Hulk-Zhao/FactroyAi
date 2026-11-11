# 上线手册：GHCR 镜像 + 广州单机 + COS/CDN 静态站

> **当前实测形态（以控制台为准）**
> - 腾讯云 CDN：加速域名 `hulkai.cn`（加速区域中国境内、加速类型「网页小文件」），
>   源站类型 **COS 源**，桶 `hulkai-cdn-1301421942`（`ap-guangzhou`），
>   回源协议 **HTTP**，回源 HOST `hulkai-cdn-1301421942.cos.ap-guangzhou.myqcloud.com`，
>   已开启私有存储桶访问；
> - CNAME：`hulkai.cn.cdn.dnsv1.com`；
> - **`www.hulkai.cn` 尚未单独创建加速域名**，需按 7.2 补一条同源配置；
> - 广州轻量服务器（4C4G / 5Mbps）只做 `api.hulkai.cn` 网关，静态流量全部走 CDN；
> - 后端镜像由 GitHub Actions 构建推 GHCR，服务器只 `docker login → pull → up`；
> - 自有源静态（`SERVE_STATIC_ORIGIN=1`）**降级为灾备**，默认关闭，启用条件见 7.8。

```
  GitHub 新仓库 ──push main──▶ Actions ──▶ GHCR
                                            │ docker pull
                                            ▼
  hulkai.cn / www ──▶ 腾讯云 CDN ──▶ COS（hulkai-cdn-1301421942）
  api.hulkai.cn ──────────────────▶ 广州单机 Nginx :443
                                       ├─ /api/*      → mfg-api      :8004
                                       ├─ /hogo/api/* → hogo-qa      :8788
                                       └─ /aps/*      → aps-backend  :8080
```

---

## 1. 目录与职责

| 路径 | 作用 |
| --- | --- |
| `.github/workflows/build-images.yml` | CI：构建 3 个后端镜像并推送 GHCR |
| `deploy/orcaterm-quickstart.md` | **OrcaTerm 里照着敲的上线命令清单**（环境检查 → 克隆仓库 → `.env` → 拉镜像并校验 tag → 停机前自检 → 停旧栈/起新栈 → 回滚 → 验证） |
| `deploy/gz-api/docker-compose.yml` | 广州单机全栈：mfg / APS / PostgreSQL / Redis / Hogo / Nginx |
| `deploy/gz-api/.env.example` | 服务器环境变量模板（含镜像 tag、灾备静态开关，无真实密钥） |
| `deploy/gz-api/nginx/templates/api.conf.template` | `api.hulkai.cn` 的 443、CORS、SSE、WebSocket |
| `deploy/gz-api/nginx/templates/api.http.conf.template` | 证书就绪前的 80 端口冒烟配置 |
| `deploy/gz-api/nginx/templates/site-origin.conf.template` | **灾备**：`hulkai.cn`/`www` 静态源站，仅 `SERVE_STATIC_ORIGIN=1` 时启用 |
| `deploy/gz-api/nginx/realip.conf` | 自有源灾备时的 CDN 回源真实 IP 段（默认空名单） |
| `deploy/cos/publish.ps1` | 构建 3 个前端 → 组装整站 → `coscli sync` 上传 COS |
| `deploy/data-persistence.md` | **数据落点 / 旧库迁移 / 备份恢复 / 部署后核对清单**（含 `down -v` 禁用说明） |
| `deploy/gz-api/backup.sh` | 一键备份：APS `pg_dump` + mfg SQLite 在线备份 + hogo 运行时 |
| `deploy/hk-web/` | **已退役**，仅保留为回滚参考，新链路不引用 |

`deploy/gz-api` 的运行只依赖同仓库的 `projects/` 目录与公共镜像（redis / postgres / nginx），
不引用 `deploy/hk-web`，也不依赖香港机器上的任何容器或目录。可自检：

```bash
cd deploy/gz-api
grep -R "hk-web" . || echo "未引用香港目录"
docker compose config --quiet && echo "compose 配置有效"
```

---

## 2. 上线前准备

- [ ] 仓库已上传到**新的 GitHub 仓库**，默认分支为 `main`，Actions 已启用。
- [ ] **服务器上 `/opt/hulkai` 已建好、属主是登录用户，仓库已克隆到 `/opt/hulkai/FactroyAi`**：
      `sudo mkdir -p /opt/hulkai && sudo chown -R "$USER":"$USER" /opt/hulkai`，
      然后 `cd /opt/hulkai && git clone <新仓库地址> FactroyAi`。
      没克隆就停旧栈等于站点直接下线：`cd /opt/hulkai/FactroyAi` 报 `No such file or directory`，
      `docker compose` 报 `no configuration file provided: not found`（2026-11-11 已真实发生过一次）。
- [ ] 已对齐部署顺序：克隆、`.env`、`docker compose config`、`docker compose pull`、镜像 tag 校验
      **全部在旧栈运行时完成**；只有「停旧栈 + `up -d`」需要停机（见第 4 节，时间线见
      [orcaterm-quickstart.md](orcaterm-quickstart.md) 0.1）。
- [ ] GitHub 账号有 GHCR 写权限（仓库 `Settings → Actions → General → Workflow permissions`
      建议选 `Read and write permissions`）。
- [ ] 广州服务器已安装 Docker 24+ 与 Compose v2，安全组 / UFW 只放行 `22`、`80`、`443`，
      且 80/443 未被宿主机 Nginx/Apache 占用（`sudo ss -lntp | grep -E ':80|:443'` 应为空）。
- [ ] 服务器已加 2G swap（4C4G 首次索引与 APS 启动需要）：
      `sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile`
- [ ] 腾讯云 COS 桶 `hulkai-cdn-1301421942`（`ap-guangzhou`）已建好，私有读写；
      CDN 加速域名 `hulkai.cn` 已配置（逐项核对见第 7 节）。
- [ ] 证书：申请一张 **`*.hulkai.cn` 泛域名证书**（含 `hulkai.cn`、`www.hulkai.cn`、`api.hulkai.cn`）；
      服务器上用 Nginx 格式（`fullchain.pem` + `privkey.key`），CDN 侧用控制台上传的同一张。
- [ ] CDN 控制台已给 `hulkai.cn` 绑定证书；`www.hulkai.cn` 加速域名补建后同样绑定。
- [ ] 已记录切换前 DNS 快照（`@`、`www`、`api`），回滚要用。
- [ ] 灾备才需要：`/opt/hulkai/site` 静态目录存在（`SERVE_STATIC_ORIGIN=1` 时）。
      静态走 COS 时**不需要**创建该目录，也**不需要**收集 CDN 回源 IP 段
      （`nginx/realip.conf` 保持空名单即可）；安全组只放行 `22/80/443`。
- [ ] 香港旧机器暂不退租，至少保留两周。

---

## 3. 第一步：CI 构建镜像（GitHub Actions → GHCR）

### 3.1 工作流与触发

工作流文件：`.github/workflows/build-images.yml`

- 触发：push 到 `main`，或在 Actions 页面手动 `Run workflow`。
- 运行环境：`ubuntu-latest`，只构建 `linux/amd64`（与广州服务器一致）。
- 推送目标：GHCR，使用自动注入的 `GITHUB_TOKEN`，不需要在仓库配置自定义 secret。

### 3.2 镜像命名与 tag 规则

假设新仓库是 `github.com/acme/factroy-ai`：

| 服务 | 镜像 |
| --- | --- |
| mfg-agent FastAPI | `ghcr.io/acme/factroy-ai-mfg-api:sha-1a2b3c4` |
| 智能 APS 后端 | `ghcr.io/acme/factroy-ai-aps-backend:sha-1a2b3c4` |
| Hogo-QA 后端 | `ghcr.io/acme/factroy-ai-hogo-qa:sha-1a2b3c4` |

- `sha-<7 位>` 与提交一一对应，三个镜像共用同一个 tag，便于整体回滚。
- 同时推送 `:latest`，但**生产只用 sha tag**，避免“不知道跑的是哪次提交”。
- 每次 CI 结束，Actions 的 Summary 会直接打印本次三个完整镜像名和服务器 `.env` 两行配置，
  复制即可。

### 3.3 Secrets / Variables 清单

仓库里只放占位符，不写任何真值。

| 配置位置 | 名称 | 必填 | 说明 |
| --- | --- | --- | --- |
| GitHub Actions Secrets | **无** | — | `GITHUB_TOKEN` 由 Actions 自动提供 |
| GitHub Repository Variables | `PIP_INDEX_URL` | 否 | 覆盖 pip 镜像；缺省用腾讯云镜像 |
| GitHub Repository Variables | `APS_MAVEN_MIRROR_URL` | 否 | 覆盖 Maven 镜像；缺省用阿里云公共仓库 |
| 广州服务器 | `GHCR_USERNAME` | 私有包必填 | GitHub 用户名 |
| 广州服务器 | `GHCR_READ_TOKEN` | 私有包必填 | Classic PAT，仅勾选 `read:packages` |
| 本地 Windows | coscli 配置 | 是 | `coscli config add` 写入本机 `~/.cos.yaml`，不入库 |
| 本地 / 服务器 | `SecretId` / `SecretKey` | 否 | 只用于 coscli；不要写进仓库、`.env.example` 或 CI |

> 如果 GHCR 包设为 Public，服务器可以不 login 直接 pull；私有包则必须用
> `GHCR_READ_TOKEN`。Token 只放在服务器 shell 环境或凭据管理器里，不要提交。

### 3.4 本地 build 备选（不推荐在生产机用）

Compose 里保留了 `build:` 段，作为 CI 不可用时的兜底：

```bash
cd deploy/gz-api
REGISTRY=local IMAGE_TAG=dev docker compose build
```

4C4G 上的内存代价（实测口径为构建峰值，非镜像大小）：

| 镜像 | 构建阶段 | 峰值内存 | 耗时参考 |
| --- | --- | --- | --- |
| mfg-api | `pip install`（FastAPI/Chroma 等） | 约 1.0–1.5 GB | 5–10 分钟 |
| aps-backend | Maven 编译 + JVM | 约 2.5–3.0 GB | 8–15 分钟 |
| hogo-qa | Node 前端构建 | 约 0.8–1.0 GB | 2–4 分钟 |

同时构建会超过 4G 物理内存，必须 `COMPOSE_PARALLEL_LIMIT=1` 并依赖 swap，耗时也会显著变长。
**推荐始终走 CI**，生产机只 pull。

---

## 4. 第二步：服务器拉镜像并启动

> 在 OrcaTerm 里逐步操作的话，直接按 [orcaterm-quickstart.md](orcaterm-quickstart.md) 敲命令；
> 本节保留原理与取舍说明。
>
> **顺序**：4.1–4.3 是准备，全部在旧栈**运行中**执行（不掉线）；
> 4.4 是停机前的三项硬门槛；**只有 4.5（停旧栈 + 起新栈）会掉线**；
> 4.6 是切换失败后立即执行的回滚块，4.7–4.8 在新栈就绪后做。
> 仓库、`.env`、镜像 tag 任何一项没就绪就停旧栈，会把站点置于长时间下线。

> **数据都在命名卷里，停服禁止 `docker compose down -v`。**
> `mfg_data`（SQLite + Chroma）、`hulkai_aps_pgdata`（APS 全库）、`hulkai_hogo_runtime`（hogo 密钥）
> 一旦被 `-v` 删除，只能从备份恢复。落点、旧库迁移、`pg_dump`/卷打包、部署后 30 秒核对
> 全部见 [data-persistence.md](data-persistence.md)。

### 4.1 准备目录并克隆仓库（第 0 步，不可跳过）

仓库不在服务器上时，后面每条命令都会失败。实测过的三种表现：

| 你执行 | 实际结果 |
| --- | --- |
| `cd /opt/hulkai/FactroyAi` | `-bash: cd: /opt/hulkai/FactroyAi: No such file or directory` |
| 在任意目录 `docker compose up -d` | `no configuration file provided: not found` |
| 在别的目录恰好有同名 compose | 起错服务，且报错里完全看不出「仓库没克隆」 |

2026-11-11 的线上切换就是这样失败的：旧栈已停、系统 nginx 已停、5 个容器已改名，
才发现仓库从未克隆，站点因此长时间下线。克隆不占端口、不碰旧容器，**不需要停机**。

```bash
sudo mkdir -p /opt/hulkai
sudo chown -R "$USER":"$USER" /opt/hulkai

export REPO_URL='<新仓库地址>'
cd /opt/hulkai
test -d FactroyAi/.git || git clone "$REPO_URL" FactroyAi

cd /opt/hulkai/FactroyAi/deploy/gz-api
pwd
ls -l docker-compose.yml .env.example
```

必须看到 `pwd` 输出 `/opt/hulkai/FactroyAi/deploy/gz-api`，且能列出 `docker-compose.yml`
与 `.env.example`；看不到就停下，不要继续。后续更新用 `cd /opt/hulkai/FactroyAi && git pull --ff-only`。

### 4.2 环境变量（旧栈运行中做）

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
cp .env.example .env
chmod 600 .env
vi .env
```

`.env.example` 是完整键清单。必填键、缺失后果与生成方式：

| 分组 | 键 | 必填 | 缺失 / 保留占位符的现象 |
| --- | --- | --- | --- |
| 镜像 | `REGISTRY`、`IMAGE_TAG` | 是 | `REGISTRY` 未替换：compose 插值报错，**一个容器都起不来**；tag 不存在：`pull`/`up` 失败 |
| 拓扑 | `API_DOMAIN`、`SITE_DOMAIN`、`SITE_ORIGIN_PRIMARY`、`SITE_ORIGIN_WWW`、`NGINX_CONF` | 是（默认值可直接用） | 证书文件名对不上导致 nginx 起不来；跨域预检不带 `access-control-allow-origin` |
| 静态源站（灾备） | `SERVE_STATIC_ORIGIN`、`SITE_ROOT` | 是（默认 `0`） | 设 `1` 但缺 `hulkai.cn` 证书 → nginx `Exited (1)`；`SITE_ROOT` 只在 `=1` 时生效 |
| 端口 | `MFG_HOST_PORT`、`APS_BACKEND_PORT` | 是（默认即可） | 与旧容器/系统 nginx 冲突时报 `port is already allocated` |
| mfg | `AUTH_SECRET` | 是 | 每次重启旧登录态失效，表现为「刚登录就 401」 |
| mfg | `MFG_ADMIN_PASS` | 是 | 保留默认口令仍能启动，但后台可用默认口令登录 |
| mfg | `QW_KEY`、`DASHSCOPE_API_KEY`、`RERANK_API_KEY` | 是（重排仅在 `RERANK_ENABLED=true` 时需要） | 检索/向量化 401/403，知识库与案例匹配不可用（`/health` 仍可能 200） |
| mfg | `DS_KEY` | 是 | 对话/分析/报告接口 401 或 5xx |
| APS | `APS_DB_NAME`、`APS_DB_USER`、`APS_DB_PASSWORD` | 复用旧卷时必须与旧 PG 一致 | `aps-backend` 反复重启：日志 `password authentication failed` 或 `database "aps" does not exist`（数据其实还在） |
| APS | `APS_SECURITY_JWT_SECRET` | 是 | 空值：JWT 可被伪造，或登录后立刻 401 |
| APS | `APS_ADMIN_INIT_PASSWORD` | 是（首次初始化写入） | 保留默认口令，admin 账号可被猜解 |
| APS | `APS_SECURITY_WS_ALLOWED_ORIGINS` | 是 | Origin 不在白名单 → WebSocket 403，求解进度不刷新 |
| Hogo | `HOGO_AUTH_PASSWORD` | 是 | 保留默认口令，演示门禁可被默认口令进入 |
| Hogo | `HOGO_AUTH_SECRET` | 否 | 留空则首启自动生成并写入卷 `hulkai_hogo_runtime`；卷被删且仍留空 → 会话全部失效 |

生成方式：`AUTH_SECRET`、`APS_SECURITY_JWT_SECRET`、`APS_DB_PASSWORD` 用
`openssl rand -hex 32`（不要手抄、不要复用）；其余口令自定义强口令，API key 从对应控制台取。
逐键说明见 [orcaterm-quickstart.md](orcaterm-quickstart.md) 第 4 节。

**复用旧数据卷时（保留 `hulkai_aps_pgdata`）**：PG 的库名/用户/口令在**第一次初始化时就写进数据目录**，
`.env` 必须与旧容器一致，否则新容器连不上（报错看起来像「数据没了」，实际是认证/库名不符）：

```bash
docker inspect hulkai_aps_postgres-pre-switch \
  --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^POSTGRES_(DB|USER|PASSWORD)='
# 把输出的三个值原样写进 .env 的 APS_DB_NAME / APS_DB_USER / APS_DB_PASSWORD
```

检查必填项有没有漏（无输出即通过）：

```bash
grep -nE '^(AUTH_SECRET|MFG_ADMIN_PASS|QW_KEY|DS_KEY|DASHSCOPE_API_KEY|APS_DB_PASSWORD|APS_SECURITY_JWT_SECRET|HOGO_AUTH_PASSWORD)=$' .env
grep -n 'change-me' .env
grep -n 'your-github-name' .env      # 有输出说明 REGISTRY 还没换
```

`.env` 已被 `.gitignore` 覆盖；不要提交、不要打进镜像、不要写进前端产物。

### 4.3 登录 GHCR、拉镜像并校验 tag（旧栈运行中做）

```bash
# 私有包才需要；Token 只勾 read:packages
echo "$GHCR_READ_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin

cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose config --quiet          # 只解析配置：仓库/.env/变量插值一次暴露
docker compose pull                    # 只拉取，不构建，不影响运行中的旧栈
```

如果 `pull` 报 `unauthorized`：检查 token 是否过期、是否勾选 `read:packages`、
包是否私有（GitHub → 个人/组织 → Packages → 该包 → Visibility）。

**逐个确认三个镜像的 tag 真实存在**（缺一个，`up` 会在停机后才失败）：

```bash
REGISTRY=$(grep -E '^REGISTRY=' .env | cut -d= -f2)
TAG=$(grep -E '^IMAGE_TAG=' .env | cut -d= -f2)
for svc in mfg-api aps-backend hogo-qa; do
  docker manifest inspect "$REGISTRY-$svc:$TAG" >/dev/null \
    && echo "ok   $svc:$TAG" \
    || echo "MISS $svc:$TAG  ← 这个 tag 不存在，先不要停旧栈"
done
```

本地 build 兜底时没有远端 tag，改为确认 `docker images` 里三个镜像都在。

### 4.4 停机前硬门槛（三项，缺一不可）

在旧栈仍在运行时执行，**全部通过才允许进入 4.5**：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

# 1) 仓库在
test -f docker-compose.yml && echo 'gate1 repo ok' || echo 'gate1 FAIL: 仓库没克隆，回 4.1'

# 2) .env 在且必填项非空
test -f .env && echo 'gate2 env exists' || echo 'gate2 FAIL: 没有 .env'
grep -nE '^(AUTH_SECRET|MFG_ADMIN_PASS|QW_KEY|DS_KEY|DASHSCOPE_API_KEY|APS_DB_PASSWORD|APS_SECURITY_JWT_SECRET|HOGO_AUTH_PASSWORD)=$' .env \
  && echo 'gate2 FAIL: 上面这些键还是空的'

# 3) 三个镜像 tag 都存在（本地 build 场景改为 docker images 确认）
REGISTRY=$(grep -E '^REGISTRY=' .env | cut -d= -f2)
TAG=$(grep -E '^IMAGE_TAG=' .env | cut -d= -f2)
for svc in mfg-api aps-backend hogo-qa; do
  docker manifest inspect "$REGISTRY-$svc:$TAG" >/dev/null && echo "gate3 ok  $svc:$TAG" || echo "gate3 FAIL: $svc:$TAG 不存在"
done
```

再补两个「配件」检查（同样不停机）：

```bash
docker compose config --quiet && echo 'compose 配置有效'
ls -l certs/api.hulkai.cn.pem certs/api.hulkai.cn.key 2>/dev/null || echo '无证书 → 本次必须走 NGINX_CONF=api.http'
```

任一项 FAIL：**回准备阶段，旧栈保持运行**。此时站点还在，不要往下走。

### 4.5 停旧栈 → 起新栈（唯一需要停机的步骤）

**前提**：4.4 三项门槛全绿。此时才开始掉线；任何一项没过都不要执行本节。
没有证书时先把 `.env` 的 `NGINX_CONF` 设为 `api.http`（见 4.7），否则 nginx 会因缺证书起不来。

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

# 1) 先停入口（系统 nginx 只有 listen 80 一个 server 块，占着 80）
sudo systemctl disable --now nginx
sudo ss -lntp | grep -E ':80 |:443 ' || echo '80/443 已释放'

# 2) 先应用、再缓存、最后数据库（保证 PG 干净关闭）
docker stop mfg_app hulkai_aps_backend hulkai_hogo_qa
docker stop mfg_redis
docker stop hulkai_aps_postgres

# 3) 只改名不删除：回滚时可直接 start 回来
for c in mfg_app mfg_redis hulkai_aps_postgres hulkai_aps_backend hulkai_hogo_qa; do
  docker rename "$c" "$c-pre-switch"
done
docker ps -a --format '{{.Names}}' | grep -E '^(mfg_app|mfg_redis|hulkai_aps_postgres|hulkai_aps_backend|hulkai_hogo_qa)$' \
  && echo '名字还没腾空' || echo '容器名已腾空'

# 4) 起新栈
COMPOSE_PARALLEL_LIMIT=1 docker compose up -d --no-build
docker compose ps
docker compose logs -f app          # 首次建 2594 条向量索引，1–3 分钟
docker compose logs -f aps-backend  # 首次跑 Flyway，39 张表，1–2 分钟
```

> 旧容器名与新 compose 的 `container_name` 完全相同（`mfg_app` / `mfg_redis` /
> `hulkai_aps_postgres` / `hulkai_aps_backend` / `hulkai_hogo_qa`），不改名 `up` 会报
> `name already in use`；80/8004/8085 也必须先释放。**全程只 `rename`，不 `rm`，不加 `-v`**。

60 秒内做健康检查（回环端口，不经过公网）：

```bash
docker compose ps                                  # 三个服务 healthy
curl -fsS http://127.0.0.1:8004/api/v2/health
curl -fsS http://127.0.0.1:8085/api/v1/health
docker compose exec -T nginx wget -qO- http://hogo-qa:8788/api/health
curl -fsS -H 'Host: api.hulkai.cn' http://127.0.0.1/healthz
```

任一不通过：**不要开始排查**，立即执行 4.6 回滚，恢复访问后再查日志。

### 4.6 回滚（切换失败立即执行）

```bash
# 1) 拆掉新栈（不加 -v，新栈卷里的数据也不删）
if [ -f /opt/hulkai/FactroyAi/deploy/gz-api/docker-compose.yml ]; then
  ( cd /opt/hulkai/FactroyAi/deploy/gz-api && docker compose down )
fi

# 2) 按依赖顺序把旧容器 start 回来
docker start hulkai_aps_postgres-pre-switch
sleep 5
docker start mfg_redis-pre-switch
docker start mfg_app-pre-switch hulkai_aps_backend-pre-switch hulkai_hogo_qa-pre-switch

# 3) 恢复系统 nginx（占回 80）
sudo systemctl enable --now nginx
systemctl is-active nginx

# 4) 三条健康检查
sleep 3
curl -fsS http://127.0.0.1:8004/api/v2/health
curl -fsS http://127.0.0.1:8085/api/v1/health
curl -s -o /dev/null -w 'nginx:%{http_code}\n' http://127.0.0.1/
```

回滚期间**不要**执行 `docker compose down -v`、`docker volume rm`、`docker system prune`
——旧数据全在 `mfg_data` / `hulkai_aps_pgdata` / `hulkai_hogo_runtime` 里。

### 4.7 放置证书并切 HTTPS

证书就绪前保持 `NGINX_CONF=api.http`：只监听 80，不占 443，能先把三个后端和路由验通。
没有证书却直接用 `NGINX_CONF=api`，nginx 会因加载证书失败而起不来。

一张 `*.hulkai.cn` 泛域名证书，按用途改名放入 `certs/`：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
mkdir -p certs
# api 网关（必需，api 直连本机，不走 CDN）
#   certs/api.hulkai.cn.pem  / certs/api.hulkai.cn.key
# 静态源站灾备（仅 SERVE_STATIC_ORIGIN=1 时需要）
#   certs/hulkai.cn.pem      / certs/hulkai.cn.key   必须包含 www.hulkai.cn SAN
chmod 600 certs/*.key
chmod 644 certs/*.pem
ls -l certs/
```

证书文件名必须等于 `.env` 里的 `API_DOMAIN` / `SITE_DOMAIN`。冒烟通过后切回正式配置：

```bash
ls -l certs/api.hulkai.cn.pem certs/api.hulkai.cn.key          # 两个文件都在
openssl x509 -in certs/api.hulkai.cn.pem -noout -checkend 0     # 未过期
openssl x509 -in certs/api.hulkai.cn.pem -noout -ext subjectAltName   # 覆盖 api.hulkai.cn
sed -i 's/^NGINX_CONF=.*/NGINX_CONF=api/' .env
docker compose up -d --no-build nginx
docker compose ps nginx
# 默认应为 1（只有 api 的 443）；灾备模式应为 2
docker compose exec nginx grep -c 'listen 443 ssl' /etc/nginx/conf.d/*.conf
```

> **切回 `api` 时证书缺失会让 nginx 快速失败**：容器入口先跑 `nginx -t`，证书缺失、
> 文件名与 `API_DOMAIN` 不符、私钥权限错、`.pem` 不是完整链，都会让它**立即退出**
> （`docker compose ps` 显示 `Exited (1)`）。80 也由这个容器监听，所以**80 会一起消失**，
> 站点直接不可访问（旧栈的系统 nginx 在 4.5 已停掉，没有兜底）。
> 失败处理：`docker compose logs --tail=50 nginx` 看是哪个文件，然后
> `sed -i 's/^NGINX_CONF=.*/NGINX_CONF=api.http/' .env && docker compose up -d nginx`
> 先把 80 保回来，再修证书。
> 只放证书、不改 `NGINX_CONF` 不会生效——配置模板是在容器启动时按该变量渲染的。

### 4.8 验证 API

DNS 还没切到本机时，给每条 `curl` 加 `--resolve api.hulkai.cn:443:129.204.108.45`，
避免请求仍打到香港旧栈。

```bash
curl -fsS https://api.hulkai.cn/healthz                 # ok
curl -fsS https://api.hulkai.cn/api/v2/health           # mfg
curl -fsS https://api.hulkai.cn/hogo/api/health         # hogo
curl -fsS https://api.hulkai.cn/aps/api/v1/health       # APS

# 跨域预检：必须 204，且带 Access-Control-Allow-Origin
curl -i -X OPTIONS https://api.hulkai.cn/api/v2/auth/login \
  -H 'Origin: https://hulkai.cn' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type,x-trace-id'
```

预检响应应包含：

```
HTTP/2 204
access-control-allow-origin: https://hulkai.cn
access-control-allow-methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
access-control-allow-headers: Authorization, Content-Type, X-API-Key, X-Requested-With, Accept, Origin, X-Trace-Id, X-Import-Token
access-control-expose-headers: Content-Disposition, Content-Length, Content-Type, X-Template-Filename, X-Trace-Id
```

> 不在白名单的 `Origin` 不会返回 `access-control-allow-origin`，这是预期行为。

---

## 5. 第三步：前端构建并发布静态站

### 5.1 一次性安装 coscli（本地 Windows）

coscli 是腾讯云官方独立 CLI，不写入 `package.json`，不算项目依赖：

```powershell
coscli --version
coscli config add -b hulkai-cdn-1301421942 -r ap-guangzhou    # 凭据只在本机 ~/.cos.yaml
```

### 5.2 发布（主路径：上传 COS）

```powershell
# 仓库根目录；默认桶/地域即线上实测值，可被 -Bucket / -Region 覆盖
powershell -File deploy/cos/publish.ps1
```

脚本做的事：

1. 构建 hogo（`HOGO_BASE=/demo/hogo-qa/`、`VITE_API_BASE=https://api.hulkai.cn/hogo/api/`）
2. 构建智能 APS（`APS_BASE=/demo/mfg-agent-platform/`、
   `VITE_API_BASE_URL=https://api.hulkai.cn/aps/api/v1`、`VITE_WS_BASE_URL=wss://api.hulkai.cn/aps`、
   `VITE_ENABLE_MOCK=false`）
3. 复制 `site/public` 到根、`projects/mfg-agent/web` 到 `/demo/mfg/`
4. 生成 `/demo/mfg/config.js`，写入 `window.MFG_API_BASE = "https://api.hulkai.cn"`
5. `coscli sync deploy/cos/dist/ cos://hulkai-cdn-1301421942/ -r ap-guangzhou`

只改了静态文件、不想重跑构建时加 `-SkipBuild`。`-Delete` 会删除桶内多余对象，
首次上线不要加，确认布局无误后再用于清理旧文件。

> `-SkipUpload` 仅灾备用：只产出 `deploy/cos/dist/`，供自有源回滚时
> `rsync` 到 `/opt/hulkai/site`（见 7.8）。

### 5.3 桶内路径（必须与 URL 路径一致）

| 线上 URL | 桶内对象 |
| --- | --- |
| `https://hulkai.cn/` | `/index.html` |
| `https://hulkai.cn/blog/...` | `/blog/...` |
| `https://hulkai.cn/demo/` | `/demo/index.html` |
| `https://hulkai.cn/demo/mfg/...` | `/demo/mfg/...` |
| `https://hulkai.cn/demo/hogo-qa/...` | `/demo/hogo-qa/...` |
| `https://hulkai.cn/demo/mfg-agent-platform/...` | `/demo/mfg-agent-platform/...` |

**如果 CDN 的回源路径不是根路径**（例如配置成 `/site`），上传要加前缀，否则回源 404：

```powershell
powershell -File deploy/cos/publish.ps1 -Bucket hulkai-cdn-1301421942 -BucketPrefix site
```

桶建议：私有读写 + 版本控制开启。首页与 SPA 回退由 COS 错误文档 + CDN 规则负责（见 7.5）。

---

## 6. 第四步：DNS 切换

切换前把 TTL 调小（例如 600 秒），等旧 TTL 过期后再改记录。

| 主机记录 | 类型 | 值 | 说明 |
| --- | --- | --- | --- |
| `api` | A | `129.204.108.45` | 广州单机，不走 CDN |
| `@` | CNAME | `hulkai.cn.cdn.dnsv1.com` | 删除指向香港的 A 记录 |
| `www` | CNAME | 待补建 `www.hulkai.cn` 加速域名后返回的 CNAME | 见 7.2，未建之前不要切 |

### 6.1 先确认 CDN 回源 COS 正常

DNS 切到 CDN 之前，用 CNAME 域名 + `Host` 头直接验证整条链路（不依赖 DNS 生效）：

```bash
curl -sI -H 'Host: hulkai.cn' https://hulkai.cn.cdn.dnsv1.com/ | head -n 5
curl -s -H 'Host: hulkai.cn' https://hulkai.cn.cdn.dnsv1.com/ | head -n 5
```

返回 200 且内容是站点首页，才说明「CDN → COS」可行。

> 桶是私有的，直接访问 `https://hulkai-cdn-1301421942.cos.ap-guangzhou.myqcloud.com/`
> 返回 403 是**正常现象**，不要据此判断上传失败；只能经 CDN 验证。

### 6.2 顺序（不要在一天内全做完）

1. **先加 `api` A 记录**，用 `curl --resolve` 跑通「4.8」。此时站点仍指向香港，可随时回退。
2. **再确认 `hulkai.cn` 加速域名**：源站类型 COS 源、桶 `hulkai-cdn-1301421942`、
   回源 HOST 为桶域名、回源协议 HTTP、私有桶访问已授权（见 7.1）。
3. **切 `@` 到 `hulkai.cn.cdn.dnsv1.com`**。此时新访客走新链路，老访客按旧 TTL 逐步收敛。
4. `www` 等 7.2 补建加速域名、拿到 CNAME 后再切，不要提前指到主域名的 CNAME。
5. 观察 24 小时（错误率、命中率、证书告警）无异常，再删香港记录、退租香港机器。

> 关键点：**先确认 CDN 能回源 COS，再切 DNS**。反过来做（COS 里还没内容、DNS 已指向 CDN）
> 会出现全站 404/403，且缓存会把这些错误响应一并放大。

```bash
dig +short api.hulkai.cn
dig +short hulkai.cn
dig +short www.hulkai.cn
curl -I https://hulkai.cn/
curl -I https://www.hulkai.cn/
```

---

## 7. 第五步：CDN（COS 源）核对

CDN 已经切到 COS 源，这里不重复「如何添加域名」，而是核对**源站、桶内路径、缓存、
SPA 回退、证书、私有桶授权**是否与仓库产物一致。

### 7.1 当前配置对照表

| 项 | 实测值 | 与仓库是否一致 | 备注 |
| --- | --- | --- | --- |
| 加速域名 | `hulkai.cn` | 是 | 中国境内 / 网页小文件 |
| 源站类型 | COS 源 | 是 | 静态全部由 COS 提供 |
| 存储桶 | `hulkai-cdn-1301421942` | 是（`publish.ps1` 默认值） | `ap-guangzhou` |
| 回源协议 | HTTP | 是 | 桶仅内网回源，无需源站证书 |
| 回源 HOST | `hulkai-cdn-1301421942.cos.ap-guangzhou.myqcloud.com` | 是 | 不能填 `hulkai.cn` |
| 回源路径 | `/`（根） | 是 | 桶根直接对应 URL |
| 私有桶访问 | 已开启 | 是 | 需要服务授权，见 7.7 |
| CNAME | `hulkai.cn.cdn.dnsv1.com` | 是 | DNS 侧见第 6 节 |

逐项核对：

- [ ] 桶内根目录有 `index.html`，且 `https://hulkai.cn/` 能取到站点首页。
- [ ] 回源 HOST 是**桶默认域名**，不是 `hulkai.cn`（填站点域名会 404）。
- [ ] 回源路径为 `/`；若改成 `/site`，上传必须加 `-BucketPrefix site`（见 5.3）。
- [ ] 缓存规则按 7.4 分层，缓存键包含查询串。
- [ ] SPA 回退按 7.5 配置，CDN 不得再有「404 → 首页」规则。
- [ ] HTTPS 证书已绑定并开启强制跳转（7.6）。
- [ ] `nginx/realip.conf` 保持空名单即可（COS 源不经过本机回源，无需 CDN 回源 IP 段）。
- [ ] 控制台可刷新目录（发版要用）。

### 7.2 加速域名：`hulkai.cn` 已建，`www` 待补

当前只建了 `hulkai.cn` 一条加速域名，`www.hulkai.cn` **还没有**独立加速域名。
在补建之前，`www` 只能指向香港旧栈或主域 CNAME（后者会因证书/Host 不匹配报错），
不要急着切。

补建步骤（控制台）：

1. CDN → 域名管理 → 添加域名，加速域名填 `www.hulkai.cn`；
2. 加速区域「中国境内」，加速类型选与主域一致的「网页小文件」；
3. 源站类型 **COS 源**，桶选 `hulkai-cdn-1301421942`（`ap-guangzhou`）；
4. 回源协议 **HTTP**，回源 HOST 保持桶默认域名
   `hulkai-cdn-1301421942.cos.ap-guangzhou.myqcloud.com`；
5. 开启私有存储桶访问并完成授权（与主域同一套，7.7）；
6. 绑定证书：必须包含 `www.hulkai.cn`（推荐直接复用 `*.hulkai.cn` 泛域名证书）；
7. 缓存规则、压缩、防盗链照抄 `hulkai.cn`（7.4 / 7.7）；
8. 拿到新的 CNAME（形如 `www.hulkai.cn.cdn.dnsv1.com`）后，再把 DNS 的 `www` 切过去。

> 两条加速域名共享同一个桶，桶内路径不需要为 `www` 单独准备一份。

### 7.3 桶内路径与 URL 的对应关系

| 线上 URL | 桶内对象 |
| --- | --- |
| `https://hulkai.cn/` | `/index.html` |
| `https://hulkai.cn/blog/...` | `/blog/...` |
| `https://hulkai.cn/demo/mfg/...` | `/demo/mfg/...` |
| `https://hulkai.cn/demo/hogo-qa/...` | `/demo/hogo-qa/...` |
| `https://hulkai.cn/demo/mfg-agent-platform/...` | `/demo/mfg-agent-platform/...` |

`www.hulkai.cn` 建好后指向同一份对象，路径完全一致，不需要复制。

### 7.4 缓存规则与压缩（CDN 侧）

COS 源没有源站 Nginx，缓存时间以 **CDN 缓存规则**为准。按文件类型分层：

| 优先级 | 匹配 | 缓存时间 | 说明 |
| --- | --- | --- | --- |
| 1 | 路径后缀 `.html` | 0 秒（不缓存） | 首页、博客、SPA 入口必须实时 |
| 2 | 路径前缀 `/demo/hogo-qa/assets/` | 31536000 秒 | Vite 带 hash 产物 |
| 3 | 路径前缀 `/demo/mfg-agent-platform/assets/` | 31536000 秒 | 同上 |
| 4 | 后缀 `.js` `.css` `.png` `.jpg` `.jpeg` `.gif` `.webp` `.svg` `.ico` `.woff` `.woff2` `.ttf` | 2592000 秒 | mfg 用 `?v=` 破缓存 |
| 5 | 默认 | 86400 秒 | 其余静态资源 |

必须确认：

- **缓存键包含查询串**，否则 `/demo/mfg/app.js?v=perf5` 升级后仍命中旧文件；
- `index.html` 命中规则 1，不能被默认规则缓存；
- 规则选「按缓存时间」而不是「遵循源站」，避免桶内对象元数据缺 `Cache-Control` 时回落到默认 TTL；
- 开启「智能压缩」（gzip + brotli），网页小文件类型下压缩收益明显；
- 发版后只刷 HTML 与目录，带 hash 的 assets 不需要刷。

### 7.5 SPA 回退与 404

COS 只能配置**一个全局错误文档**，而这里有两个 history 路由的 SPA（hogo、智能 APS），
它们的回退目标不同。因此分工是：

1. **COS 静态网站**（桶 → 基础配置 → 静态网站）：开启，索引文档 `index.html`；
   错误文档填 `index.html`（承接主站与博客的未知路径，返回站点首页）。
2. **CDN 路径重写**（规则引擎）只对两个 SPA 前缀生效：

   ```
   ^/demo/hogo-qa/(?!assets/).*$            → /demo/hogo-qa/index.html
   ^/demo/mfg-agent-platform/(?!assets/).*$ → /demo/mfg-agent-platform/index.html
   ```

   若控制台不支持负向断言，用两条规则组合：前缀 `/demo/hogo-qa/assets/` 不重写，
   前缀 `/demo/hogo-qa/` 重写到 `/demo/hogo-qa/index.html`；APS 同理。

3. **不要**再配「404 状态码 → 返回/跳转首页」一类的规则。那会把不存在的 `.js`
   也返回成 HTML，浏览器报 `Unexpected token '<'`，表现为白屏。

验证（无痕窗口直接打开子路由）：

```
https://hulkai.cn/demo/mfg-agent-platform/orders
https://hulkai.cn/demo/hogo-qa/analysis
```

`/demo/mfg/` 用 hash 路由，不需要重写。

### 7.6 HTTPS 证书绑定与 CNAME / TTL

**证书矩阵**

| 位置 | 证书 | 说明 |
| --- | --- | --- |
| 客户端 ↔ CDN | 控制台上传的证书（`hulkai.cn`，`www` 建域名后也要覆盖） | 浏览器看到的证书 |
| CDN ↔ COS | 不需要 | 回源协议 HTTP，走腾讯云内网，与证书无关 |
| 客户端 ↔ `api.hulkai.cn` | 服务器 `certs/api.hulkai.cn.pem` | api 直连广州机，不走 CDN |
| CDN ↔ 自有源（仅灾备时） | 服务器 `certs/hulkai.cn.pem` | 必须覆盖回源 HOST，否则 526 |

**绑定步骤**：CDN → 证书管理 → 上传 `*.hulkai.cn`（或 `hulkai.cn`）→ 域名管理 →
`hulkai.cn` → HTTPS 配置 → 绑定证书 → 开启「强制 HTTPS」→ 开启 HTTP/2。
`www` 加速域名建好后重复一次。

**CNAME 与 TTL**

1. 切换前把 DNS TTL 调到 600 秒，等旧 TTL 过期；
2. 用 6.1 的 `curl -H 'Host: hulkai.cn' https://hulkai.cn.cdn.dnsv1.com/` 确认 CDN 返回 200；
3. 再把 `@` 改成 CNAME `hulkai.cn.cdn.dnsv1.com`；
4. `dig +short hulkai.cn` 应返回 CDN 节点；`curl -I https://hulkai.cn/` 应看到
   `server: tencent-cos` 或 CDN 节点标识、`x-cache` 头；
5. 观察 24 小时，再删除指向香港的 A 记录并退租香港机器。

> 证书续期时，CDN 控制台与服务器 `certs/api.hulkai.cn.*` 要**同时更新**；
> 只换一边会出现「站点正常、API 报证书错误」或反之。

### 7.7 私有桶授权与防盗链

**私有桶授权**

- 桶 ACL 保持**私有读写**，不要为了省事开公有读——公有读等于把桶域名变成免费 CDN，
  流量费会直接计到桶上，且绕过 CDN 的缓存与防盗链。
- CDN 侧已开启「私有存储桶访问」，需要在控制台完成一次服务授权：按提示授予 CDN
  访问该桶的服务角色/策略。授权失败时表现为回源 403。
- 更新桶策略或删除服务角色后，要回来复查这条配置。

**防盗链**

- 推荐 Referer 白名单：`hulkai.cn`、`www.hulkai.cn`，并**允许空 Referer**
  （部分浏览器、App 内嵌 WebView、直接输入网址都不带 Referer）。
- 不建议使用时间戳 URL 鉴权（Type A/B）：静态站无法给 `index.html` 生成带签名地址，
  一旦开启会导致整站打不开。
- 建议在 CDN 配「流量封顶 / 带宽封顶」与告警，避免被刷时产生不可控账单。

**发版后**

1. 控制台刷新目录：`/`、`/demo/hogo-qa/`、`/demo/mfg-agent-platform/`、`/demo/mfg/`；
2. 只刷 HTML，带 hash 的 assets 不需要刷；
3. 若 `index.html` 仍被缓存，单独刷新 URL `/index.html` 与 `/demo/*/index.html`。

### 7.8 回滚：COS 源 → 自有源（灾备）

**什么时候才打开 `SERVE_STATIC_ORIGIN=1`**：COS 故障 / 欠费 / 被刷导致不可用，
CDN 回源 COS 持续 5xx，或需要临时验证一份新构建而不想改动桶。它是短期兜底，
不是常态——切回自有源后静态流量会重新占用广州机的 5Mbps 出口。

**前置（建议平时就准备好）**

1. 服务器上放一份当前产物：

   ```powershell
   powershell -File deploy/cos/publish.ps1 -SkipUpload
   ```
   ```bash
   rsync -az --delete deploy/cos/dist/ root@129.204.108.45:/opt/hulkai/site/
   ```

2. `certs/hulkai.cn.pem|key` 存在，且证书覆盖 `hulkai.cn` 与 `www.hulkai.cn`；
3. `.env` 改 `SERVE_STATIC_ORIGIN=1`，`docker compose up -d --no-build nginx`；
4. 本机自检：`curl -sk -H 'Host: hulkai.cn' https://127.0.0.1/ | head -n 3`；
5. 可选：把 CDN 回源 IP 段填进 `nginx/realip.conf`，日志才有真实客户端 IP。

**切换（控制台，按顺序）**

1. CDN → 域名管理 → `hulkai.cn` → 源站配置；
2. 源站类型改「自有源」，源站地址 `129.204.108.45`，端口 `443`；
3. 回源协议改 **HTTPS**（源站 80 会 301，不能选 HTTP）；
4. 回源 HOST 改 `hulkai.cn`；
5. 保存后刷新缓存目录 `/`，再用 6.1 的方式验证；
6. `www` 加速域名（如已建）同样改；
7. 若回源 526：源站证书没覆盖 `hulkai.cn`，先修证书，不要长期关闭回源校验；
8. 安全组放行 443；回源 IP 段是动态的，不要只放行单个节点 IP，否则会间歇性超时。

**切回 COS**

把源站类型改回「COS 源」、选中桶、回源协议改回 HTTP、回源 HOST 改回桶默认域名，
服务器 `.env` 改回 `SERVE_STATIC_ORIGIN=0`，再刷新一次 CDN 目录。

### 7.9 不需要在 CDN 配 CORS

跨域由 `api.hulkai.cn` 的 Nginx 统一处理；静态站只提供同源 HTML/JS/CSS，
不需要在 CDN 上再加跨域响应头。

---

## 8. 验收清单

- [ ] `https://hulkai.cn/` 首页、`/blog/`、`/demo/` 正常，证书无告警。
- [ ] `https://www.hulkai.cn/` 与主域一致（需先按 7.2 补建加速域名）。
- [ ] 用 CNAME + Host 绕过 DNS 验证也正常：
      `curl -sI -H 'Host: hulkai.cn' https://hulkai.cn.cdn.dnsv1.com/`。
- [ ] `curl -sI https://hulkai.cn/demo/hogo-qa/assets/<hash>.js` 返回 200 且带
      `cache-control: max-age=31536000` 或 CDN 的 `x-cache: Hit`；`/index.html` 不被长缓存。
- [ ] `www.hulkai.cn` 加速域名建好后，两条域名访问同一份内容。
- [ ] 桶为私有读：直接访问桶域名应 403，经 CDN 正常（说明没有开公有读）。
- [ ] 直接打开 `https://hulkai.cn/demo/mfg/`，页面右上角后端状态为「在线」。
- [ ] mfg 登录、报表、附件下载正常（`/api/*` 跨域无报错，SSE 正常）。
- [ ] Hogo 演示口令登录成功，刷新子路由不 404（`/hogo/api/*`）。
- [ ] 智能 APS 登录、排产求解、进度条实时更新（`/aps/*` 与 `wss://api.hulkai.cn/aps/ws/*`），
      导入模板下载保留原文件名。
- [ ] 浏览器 Network 中 API 请求的 `Origin` 为站点域名，响应带 `access-control-allow-origin`。
- [ ] 刷新页面后 `index.html` 返回 200 且 `Age` 很小或不存在（未被 CDN 缓存）。
- [ ] `https://api.hulkai.cn/` 根路径跳回 `https://hulkai.cn/`。
- [ ] **数据完整**：三个命名卷都在，APS/mfg 关键行数不少于迁移前，详见
      [data-persistence.md §6](data-persistence.md#6-部署前后核对清单30-秒判断数据是否完整)：
      `docker compose ps` 全 `running`、`docker volume ls | grep -E 'mfg_data|hulkai_aps_pgdata|hulkai_hogo_runtime'`。

排障命令：

```bash
# CDN 是否命中缓存
curl -sI https://hulkai.cn/demo/mfg/app.js?v=perf5 | grep -i -E 'x-cache|age|cache'

# 后端容器日志
docker compose -f deploy/gz-api/docker-compose.yml logs -f --tail=100 nginx app aps-backend hogo-qa

# 确认线上镜像就是 .env 里的 tag
docker compose -f deploy/gz-api/docker-compose.yml images
```

---

## 9. 发版

### 9.1 后端

1. push 到 `main`，等 `build-images` 变绿；
2. 打开本次 Actions 的 Summary，复制 `REGISTRY` 与 `IMAGE_TAG`；
3. 服务器：

```bash
cd /opt/hulkai/FactroyAi
git pull --ff-only
cd deploy/gz-api

sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=sha-1a2b3c4/' .env
docker compose pull
docker compose up -d --no-build
docker compose ps
```

只需重启某个服务时：

```bash
docker compose up -d --no-build app
```

不要加 `--build`；服务器上没有构建缓存，会重新编译并占满内存。

### 9.2 前端

```powershell
powershell -File deploy/cos/publish.ps1
```

默认桶 `hulkai-cdn-1301421942`、地域 `ap-guangzhou`；换桶用 `-Bucket` / `-Region`。
上传后在 CDN 刷新目录：

- `/`、`/demo/hogo-qa/`、`/demo/mfg-agent-platform/`、`/demo/mfg/`
- 只刷 HTML；带 hash 的 assets 不需要刷
- 如果 `index.html` 规则没生效，临时刷新 URL `/index.html`、`/demo/*/index.html`

> 灾备路径（`SERVE_STATIC_ORIGIN=1`）才需要 `-SkipUpload` + `rsync` 到 `/opt/hulkai/site`，
> 见 7.8。切回 COS 后记得把 `.env` 改回 `0`。

---

## 10. 回滚

### 10.1 后端回到上一个镜像 tag

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

# 找到上一个可用 tag：GitHub → Packages 页面，或服务器本地缓存
docker image ls | grep -E 'mfg-api|aps-backend|hogo-qa'

sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=sha-OLD_TAG/' .env
docker compose pull
docker compose up -d --no-build
```

如果旧镜像还在本地，可以跳过 `pull`，直接 `docker compose up -d --no-build`。
三个镜像共用同一个 tag，整体回滚不会出现版本错配。

> 只回滚镜像不动数据卷；**不要**在回滚时顺手 `docker compose down -v`。
> 若需要连数据一起回到某个时间点，按 [data-persistence.md §5](data-persistence.md#5-备份与恢复) 恢复备份。

### 10.2 静态站

- 开启 COS 版本控制后，把 `index.html` 等对象恢复到上一版本，再刷新 CDN；
- 或本地保留上一次的 `deploy/cos/dist`，重新 `coscli sync` 覆盖；
- 灾备模式（自有源）下才用 `/opt/hulkai/site` 的 `site.bak-*` 目录回退，见 7.8。

### 10.3 全量回退到香港旧部署

1. 确认香港旧栈仍在运行，且证书覆盖 `hulkai.cn` / `www.hulkai.cn`
   （旧栈默认按 `hulkai.xyz` 签发，未覆盖时先补证书）。
2. 先把 CDN 的回源 HOST / 源站地址改回香港（如果切换后改过），或直接把 CDN 域名停用。
3. 把 `@`、`www`、`api` 的 DNS 改回切换前快照。
4. TTL 600 秒时，约 10 分钟收敛；期间源站与 CDN 都可能出现混合版本，属正常现象。
5. 回滚期间不要删除 COS 桶、GHCR 包、广州 `.env` 与 `/opt/hulkai/site`。

---

## 11. 容量、成本与安全

- **构建**：全部在 GitHub Actions 完成；服务器只 pull，4C4G 不参与编译。
- **镜像**：mfg 约 1.2–1.6 GB、APS 约 0.4–0.6 GB、Hogo 约 0.2 GB。
  每次发版新增一层 tag，建议每月清理无引用的旧镜像：`docker image prune -a`（先确认没有回滚需要）。
- **内存**：mfg 2800m + APS 700m + PostgreSQL 256m + Hogo 160m + Nginx 64m + Redis ≈ 4G 上限，
  务必保留 swap，并用 `docker stats` 观察峰值。
- **磁盘**：60G；镜像 + volume 通常 < 15G，`docker system prune` 前先确认卷与镜像。
- **带宽**：静态走 COS + CDN，不占服务器 5Mbps；这台机器只承载 API、SSE 与 WebSocket。
  只有灾备切回自有源（7.8）时才需要担心静态抢出口。
- **COS**：私有桶 + CDN 私有桶访问授权 + Referer 防盗链；不要把桶改成公有读。
- **真实 IP**：默认（COS 源）不涉及；灾备自有源时 `realip.conf` 只信任 CDN 回源段。
- **安全组**：只开 22/80/443。`8004`、`8085`、`8788` 只绑 `127.0.0.1`。
- **CORS**：只放行 `https://hulkai.cn`、`https://www.hulkai.cn`；新增域名时同时改 `.env` 与
  `api.conf.template` 的 map。
- **Cookie**：当前三套应用都用 `Authorization: Bearer` + `localStorage`，没有 Cookie。
  若未来引入 Cookie，必须设置 `Domain=.hulkai.cn; Path=/; Secure; SameSite=None`，
  并在网关增加 `Access-Control-Allow-Credentials: true`，同时把
  `Access-Control-Allow-Origin` 限定为具体域名（不能用 `*`）。
- **密钥**：只在服务器 `deploy/gz-api/.env`、服务器 GHCR token 与本地 coscli 配置中；
  仓库、镜像、CI 日志、前端产物里一律不得出现真实值。

---

## 12. 常见问题

**Q：`docker compose pull` 报 `unauthorized` / `denied`？**
私有包需要先 `docker login ghcr.io`，token 勾 `read:packages`；或把包改为 Public。
确认 `REGISTRY` 全小写，且与 CI 推送的前缀完全一致。

**Q：`docker compose up` 又想本地 build？**
服务器统一加 `--no-build`。Compose 同时声明了 `build:` 与 `image:`，不加 `--no-build` 时
在镜像缺失的情况下会尝试构建。

**Q：页面能打开，但接口全部 CORS 报错？**
检查请求的 `Origin` 是否在 `.env` 的 `SITE_ORIGIN_PRIMARY` / `SITE_ORIGIN_WWW` 中；
改完执行 `docker compose up -d --no-build nginx` 重新渲染模板，再用 4.8 的 `OPTIONS` 验证。

**Q：APS 进度条不动，控制台 WebSocket 403？**
`APS_SECURITY_WS_ALLOWED_ORIGINS` 必须包含浏览器地址栏的 Origin
（`https://hulkai.cn` 与 `https://www.hulkai.cn`）；并确认 `/aps/` location 带
`Upgrade` / `Connection` 头。

**Q：刷新 `/demo/hogo-qa/xxx` 返回主页或 404？**
COS 静态网站只有一个全局错误文档，两个 SPA 的深链要靠 CDN 路径重写回各自的
`index.html`（7.5）。若返回的是主站首页，说明重写规则没生效或顺序被默认规则抢先。

**Q：发版后用户还是旧页面？**
`index.html` 被缓存。确认 CDN 规则 1 生效并刷新目录；不要把 HTML 放进长缓存规则。

**Q：CDN 报 526 / SSL handshake failed？**
当前是 COS 源 + HTTP 回源，正常情况下不会 526。出现 526 基本意味着源站被改成了
「自有源 + HTTPS」，但服务器证书没覆盖回源 HOST（`hulkai.cn`）。按 7.8 核对证书，
或把源站改回 COS 源。

**Q：CDN 回源 404？**
按顺序查：桶根是否有 `index.html`；回源路径是否为 `/`（配了 `/site` 就要
`-BucketPrefix site`）；回源 HOST 是否为桶默认域名而不是 `hulkai.cn`（7.1 / 7.3）。

**Q：COS 私有桶回源 403？**
「私有存储桶访问」授权失效或桶策略未放行 CDN 服务角色，重新授权（7.7）。
不要在排障时长期改成公有读。

**Q：`www.hulkai.cn` 打不开或证书报错？**
还没有建 `www` 的加速域名。按 7.2 补建并绑定覆盖 `www` 的证书，再切 DNS。

**Q：Nginx 日志里全是 CDN 节点 IP？**
仅灾备自有源模式相关：把 CDN 回源 IP 段填进 `realip.conf` 后重启 nginx（7.8）；未填不影响功能。

**Q：mfg 页面显示后端离线？**
打开 `https://hulkai.cn/demo/mfg/config.js`，确认 `window.MFG_API_BASE` 是
`https://api.hulkai.cn`；再看 `https://api.hulkai.cn/api/v2/health` 是否可达。
