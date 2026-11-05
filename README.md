# hulkai

个人站点与项目包：`hulkai.xyz` 的主页、技术博客，以及各项目的前端与后端。
（`hulkai.cn` 备案完成前暂不可用，部署配置保留为兼容域名。）

单仓分层，两台服务器拉同一份代码，各自用一份 compose 部署：

- **香港 2C2G · 43.132.214.124**：Nginx 静态站 + TLS + `/api` 反向代理
- **广州 4C4G · 129.204.108.45:8004**：FastAPI + SSE + SQLite + Chroma + 云端 embedding

## 目录

```
site/public/                   # 主页、博客、Demo 目录（零构建静态站）
projects/mfg-agent/web/        # 智造协同平台前端 → /demo/mfg/
projects/mfg-agent/api/        # 智造协同平台后端（FastAPI）
projects/hogo-qa/frontend/     # SPC 质量分析工具前端（React + Vite）
projects/hogo-qa/backend/      # SPC 质量分析工具后端（零依赖 Node 服务）
projects/smart-aps-agent/      # 智能 APS 排产平台（Vue3 + Spring Boot + PostgreSQL）→ /demo/mfg-agent-platform/
deploy/hk-web/                 # 香港：Nginx + certbot + hogo-qa / smart-aps 前端的 compose
deploy/gz-api/                 # 广州：FastAPI + APS 后端 + PostgreSQL 的 compose
docs/                          # 架构与改造记录
```

## 路由

| 路径 | 内容 | 由谁提供 |
|---|---|---|
| `/` | 个人主页（作品 / 博客 / 关于） | 香港 `site/public/index.html` |
| `/blog/` | 技术复盘列表与文章 | 香港 `site/public/blog/` |
| `/demo/` | Demo 总目录 | 香港 `site/public/demo/` |
| `/demo/mfg/` | 智造协同平台在线 Demo | 香港 alias → `projects/mfg-agent/web/` |
| `/demo/hogo-qa/` | SPC 质量分析工具在线版 | 香港反代 → 本机 `hogo-qa` 容器 `:8788` |
| `/demo/mfg-agent-platform/` | 智能 APS 排产平台在线 Demo | 香港反代 → 本机 `smart-aps-frontend` 容器 `:8080` → 广州 APS 后端 `:8085` |
| `/demo/mfg-agent-platform/api/*` | APS 接口与 STOMP WebSocket | 香港 → 本机 APS 前端容器 → 广州 `:8085` |
| `/api/*` | 智造协同平台接口与 SSE | 香港反代 → 广州 `:8004` |

## 分支与发布

- **`main`：生产分支。** 两台服务器都只拉 `main`，任何要上线的改动最终都要合进来。
- **`mfg` / `hogo` / `site`：项目开发分支。** 改哪个项目就在哪个分支上做：mfg → `mfg`，
  SPC 工具 → `hogo`，主页与博客 → `site`。

```bash
git checkout mfg                     # 换成 hogo / site
# ...改动、本地验证...
git commit -am "feat(mfg): ..."
git push origin mfg                  # 远端留一份备份（可选）
git checkout main
git merge --no-ff mfg                # 合回生产分支
git push origin main                 # 服务器 pull 到的就是这次改动
```

## 新增内容放哪里

先判断「有没有服务端」，再决定动哪几个文件：

| 要加的东西 | 存放位置 | 还要改什么 |
|---|---|---|
| 博客文章 | `site/public/blog/<slug>.html`；列表在 `site/public/blog/index.html`，主页卡片在 `site/public/index.html` | 不用改 Nginx，`git pull` 后刷新即可 |
| 纯静态 Demo（原生 HTML，或已构建好、无后端） | `projects/<项目>/web/` | `deploy/hk-web/nginx/templates/default.conf.template` 加 `location ^~ /demo/<项目>/` 的 alias；`site/public/demo/index.html` 加一张卡 |
| 前后端分离的新项目 | 前端 `projects/<项目>/frontend/`，后端 `projects/<项目>/backend/`（Python 项目可用 `api/`，见 `projects/mfg-agent/api`） | 香港 compose 加前端容器或 alias；广州 `deploy/gz-api/docker-compose.yml` 加后端服务；Nginx 加反代 |
| 密钥 / 环境变量 | `deploy/<主机>/.env`（不入库），示例写进对应的 `.env.example` | — |

分工原则：**默认前端放香港、后端放广州**，浏览器只访问香港，`/api/*` 由香港反代到广州 `:8004`。
只有「完全不需要服务端」的东西才整体留在香港。`projects/hogo-qa` 是例外——零依赖 Node 服务，
前后端同机，由香港 Nginx 反代到 `/demo/hogo-qa/`。

带服务端的子路径项目必须同时对齐三处，否则请求会串到同域其它 `/api`：
构建基路径（Vite `base`）、路由 `basename`、接口前缀 `${BASE_URL}api/`。
`projects/hogo-qa` 的 `HOGO_BASE` 写法可直接照抄。

## 两台服务器怎么拉取

两台都只从 GitHub 拉，不在服务器上改代码。仓库地址 `https://github.com/Hulk-Zhao/hulkai.git`
（旧地址 `mfg-agent` 会自动 301）。

```bash
cd <服务器上的仓库目录>
git remote -v      # 应指向 Hulk-Zhao/hulkai
git status         # 应为干净；有本地改动先自行处理
git fetch origin
```

**香港（静态站 + 前端容器）**

```bash
git pull --ff-only
docker compose -f deploy/hk-web/docker-compose.yml up -d --build
# 只改了静态站或 nginx 配置时，不必重建：
docker compose -f deploy/hk-web/docker-compose.yml exec nginx nginx -s reload
```

**广州（API）**

```bash
git pull --ff-only
docker compose -f deploy/gz-api/docker-compose.yml up -d --build
docker compose -f deploy/gz-api/docker-compose.yml logs -f app
```

> 若 `git pull` 报 diverged（仓库提交历史在 2026-10 整理过，服务器是旧历史的克隆），
> 执行一次 `git fetch origin` + `git reset --hard origin/main` 对齐即可——服务器上没有本地改动。

## 香港部署（静态站 + hogo-qa / APS 前端）

拉取命令见上一节。首次部署要准备密钥文件并签发证书：

```bash
cp deploy/hk-web/.env.example deploy/hk-web/.env   # 必改 HOGO_AUTH_PASSWORD
```

对外域名不再写死在 Nginx 里：`PUBLIC_DOMAIN`（默认 `hulkai.xyz`）、
`PUBLIC_DOMAIN_ALIASES`（默认同时包含 `hulkai.cn` / `www.hulkai.cn` / `www.hulkai.xyz`）、
`CERT_DOMAIN`（证书 lineage 目录）都在 `deploy/hk-web/.env` 里配置，容器启动时渲染
`deploy/hk-web/nginx/templates/*.template`。备案通过后只改 `PUBLIC_DOMAIN` / `CERT_DOMAIN`
两个变量即可切回 `hulkai.cn`，不用改配置或重新构建前端。

证书签发步骤见 [deploy/hk-web/README.md](deploy/hk-web/README.md)。

APS 前端由 `deploy/hk-web/docker-compose.yml` 的 `smart-aps-frontend` 服务在 `git pull` 后
`up -d --build` 构建（Vue 3 + Vite，构建参数把 `base`、接口前缀、WS 前缀统一为
`/demo/mfg-agent-platform/`）。它只在内网暴露 `8080`，由 Nginx 反代；`/api` 与 `/ws` 再由容器
转发到广州 `:8085`，不会占用站点根路径的 `/api/*`。如需改后端地址，在香港 `.env` 里设置
`APS_BACKEND_URL`。

## 广州部署（API）

拉取命令见上一节。首次部署要生成密钥文件（`.env` 不入库）：

```bash
cd deploy/gz-api
cp ../../projects/mfg-agent/api/.env.example .env
docker compose up -d --build
docker compose logs -f app
```

APS 后端（`aps-postgres` + `aps-backend`）随同一个 compose 启动，首次构建会下载 Maven 依赖，
耗时明显长于 FastAPI；默认通过 `APS_MAVEN_MIRROR_URL`（阿里云公共仓库）加速，也可在 `.env` 覆盖。
首次启动由 Flyway 建 28 张表；如需演示数据，再导入 M1 种子（幂等）：

```bash
docker compose exec -T aps-postgres psql -U aps -d aps < aps-seed-m1.sql
```

APS 的 JWT 密钥、数据库口令、admin 首启口令、安全模式与 WebSocket 来源白名单都可以在
`deploy/gz-api/.env` 覆盖：`APS_SECURITY_JWT_SECRET`、`APS_DB_PASSWORD`、
`APS_ADMIN_INIT_PASSWORD`、`APS_SECURITY_MODE`、`APS_SECURITY_WS_ALLOWED_ORIGINS`。
后者的默认值以 `hulkai.xyz` 为准，并同时包含 `hulkai.cn` 兼容项；换域名只改这一个变量。
默认安全模式为 `prod`（所有接口需登录），演示账号 `admin`，首启口令默认 `ApsDemo2026`，
**上线前务必覆盖**。

> 资源提示：广州 4C4G 上 `mfg-agent` 已占 2.8G，APS 后端 700m + PostgreSQL 256m 属于紧平衡。
> 若 `docker stats` 显示频繁 OOM，优先临时停 `mfg-agent` 或升级到 8G，不要同时压缩两个服务的堆。

广州 `8004`（mfg）与 `8085`（APS）必须在**云安全组**里只放行香港 IP（`43.132.214.124/32`）。
Docker 的 `-p` 会绕过 ufw，宿主机防火墙拦不住。

## 演示数据与密钥

- `projects/mfg-agent/api/data/demo_seed/` 内置演示库（2093 条历史工单 + 案例库），
  首次启动由 entrypoint 复制到 `mfg_data` volume；已存在则跳过，不覆盖线上数据。
- 需要配置的变量见 `projects/mfg-agent/api/.env.example`：对话/分析模型、向量模型、
  `AUTH_SECRET`、Chroma 路径等。
- 香港侧的 `.env` 用于 `hogo-qa` 演示口令与对外域名，见 `deploy/hk-web/.env.example`
  （`HOGO_AUTH_PASSWORD`；域名相关变量均有默认值，不建 `.env` 也能用 `hulkai.xyz` 启动）。

## 说明

- 静态站零构建：原生 HTML/CSS/JS，无外部字体与 CDN 依赖。
- 博客文章中的数字来自仓库代码、演示数据与可复算样本。
- 前端静态资源带 `?v=` 版本号；改动后在 HTML 里同步提升版本，避免缓存旧文件。
