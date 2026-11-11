# hulkai

个人站点与项目包：`hulkai.cn` 的主页、技术博客，以及各项目的前端与后端。

## 部署形态（2026-11 起）

`hulkai.cn` / `www.hulkai.cn` 走**腾讯云 CDN**，源站类型 **COS 源**（桶
`hulkai-cdn-1301421942` / `ap-guangzhou`），静态不占服务器带宽；`api.hulkai.cn` 直连
广州单台轻量服务器（`129.204.108.45`）。后端镜像由 GitHub Actions 构建推 GHCR，服务器只 pull。

```
hulkai.cn / www.hulkai.cn ──▶ 腾讯云 CDN ──▶ COS（hulkai-cdn-1301421942）
api.hulkai.cn ──────────────────────────▶ 广州单机 Nginx :443
                                             ├─ /api/*       → mfg-agent FastAPI :8004
                                             ├─ /hogo/api/*  → Hogo-QA Node :8788
                                             └─ /aps/*       → 智能 APS :8080（含 WebSocket）
```

`SERVE_STATIC_ORIGIN=1` 的静态源站是灾备方案（COS 故障时把 CDN 临时改回自有源），默认关闭，
步骤见 [deploy/README.md](deploy/README.md#78-回滚cos-源--自有源灾备)。

完整上线步骤（镜像、证书、DNS、CDN、回滚）见 **[deploy/README.md](deploy/README.md)**；
在 OrcaTerm 里照着敲的命令清单见 **[deploy/orcaterm-quickstart.md](deploy/orcaterm-quickstart.md)**。
香港旧部署（`deploy/hk-web/`）已退役，只保留作回滚参考。

## 目录

```
site/public/                   # 主页、博客、Demo 目录（零构建静态站，对应站点根路径）
projects/mfg-agent/web/        # 智造协同平台前端 → /demo/mfg/
projects/mfg-agent/api/        # 智造协同平台后端（FastAPI）
projects/hogo-qa/frontend/     # SPC 质量分析工具前端（React + Vite）→ /demo/hogo-qa/
projects/hogo-qa/backend/      # SPC 质量分析工具后端（零依赖 Node 服务）
projects/smart-aps-agent/      # 智能 APS 排产平台（Vue3 + Spring Boot + PostgreSQL）→ /demo/mfg-agent-platform/
deploy/cos/                    # 前端构建 + 组装静态站 + 上传 COS（默认桶已内置）
deploy/gz-api/                 # 广州单机全栈 compose（mfg / APS / Hogo / Nginx）
deploy/gz-api/nginx/           # api.hulkai.cn 网关配置（CORS / SSE / WebSocket）+ 灾备静态源站
deploy/orcaterm-quickstart.md  # OrcaTerm 上线命令清单（拉代码 → .env → 证书 → 启动 → 验证）
docs/                          # 架构与改造记录
```

## 路由

| 路径 | 内容 | 由谁提供 |
|---|---|---|
| `/` | 个人主页 | COS + CDN |
| `/blog/` | 技术复盘列表与文章 | COS + CDN |
| `/demo/` | Demo 总目录 | COS + CDN |
| `/demo/mfg/` | 智造协同平台在线 Demo | COS + CDN（`projects/mfg-agent/web`） |
| `/demo/hogo-qa/` | SPC 质量分析工具在线版 | COS + CDN（React 构建产物） |
| `/demo/mfg-agent-platform/` | 智能 APS 排产平台在线 Demo | COS + CDN（Vue 构建产物） |
| `api.hulkai.cn/api/*` | 智造协同平台接口与 SSE | 广州 `:8004` |
| `api.hulkai.cn/hogo/api/*` | SPC 工具登录门禁与样例数据 | 广州 `:8788` |
| `api.hulkai.cn/aps/*` | APS 接口与 STOMP WebSocket | 广州 `:8080` |

## 分支与发布

- **`main`：生产分支。** 服务器与静态站发布都以 `main` 为准。
- **`mfg` / `hogo` / `site`：项目开发分支。** 改哪个项目就在哪个分支上做，验证后合回 `main`。

```bash
git checkout mfg                     # 换成 hogo / site
# ...改动、本地验证...
git commit -am "feat(mfg): ..."
git push origin mfg
git checkout main
git merge --no-ff mfg
git push origin main
```

## 发布

**后端（CI 构建镜像 → 服务器 pull）**

push 到 `main` 后，GitHub Actions（`.github/workflows/build-images.yml`）构建 3 个镜像并推送到
GHCR，tag 为 `sha-<7 位提交号>`。在 Actions 的 Summary 里复制 `REGISTRY` 与 `IMAGE_TAG`：

```bash
cd /opt/hulkai/FactroyAi
git pull --ff-only
cd deploy/gz-api
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=sha-1a2b3c4/' .env
docker compose pull
docker compose up -d --no-build
```

服务器不编译；本地 `docker compose build` 仅作 CI 不可用时的兜底（4C4G 上很吃内存）。

**前端（本地构建 → 上传 COS）**

```powershell
powershell -File deploy/cos/publish.ps1
```

默认桶 `hulkai-cdn-1301421942`、地域 `ap-guangzhou`，可用 `-Bucket` / `-Region` 覆盖；
上传后在 CDN 控制台刷新 HTML 目录。仅灾备（自有源）时才用 `-SkipUpload` + rsync
到 `/opt/hulkai/site`，见 [deploy/README.md](deploy/README.md) 第 5、7 节。
首次部署、证书、DNS 与 CDN 配置见 [deploy/README.md](deploy/README.md)。

## 新增内容放哪里

| 要加的东西 | 存放位置 | 还要改什么 |
|---|---|---|
| 博客文章 | `site/public/blog/<slug>.html`；列表与主页卡片同步更新 | 重新执行 `publish.ps1`，刷新 CDN 目录 |
| 纯静态 Demo（原生 HTML，无后端） | `projects/<项目>/web/` | `deploy/cos/publish.ps1` 增加复制规则；`site/public/demo/index.html` 加卡片 |
| 前后端分离的新项目 | 前端 `projects/<项目>/frontend/`，后端 `projects/<项目>/backend/` | 静态发布脚本加上传目录；`deploy/gz-api/docker-compose.yml` 加服务；`nginx/templates/api.conf.template` 加反向代理与 CORS |
| 密钥 / 环境变量 | 服务器 `deploy/gz-api/.env`（不入库）；模板改 `.env.example` | — |

跨域下带服务端的子路径项目必须同时对齐三处：构建基路径（Vite `base`）、路由 `basename`、
接口前缀。静态站与 API 不同源时，接口前缀要指向 `https://api.hulkai.cn/<前缀>/`，
可参考 `projects/hogo-qa` 的 `VITE_API_BASE` 与智能 APS 的 `VITE_API_BASE_URL` / `VITE_WS_BASE_URL`。

## 演示数据与密钥

- `projects/mfg-agent/api/data/demo_seed/` 内置演示库，首次启动复制到 `mfg_data` volume；已存在则跳过。
- mfg 变量见 `projects/mfg-agent/api/.env.example`；单机部署汇总模板见 `deploy/gz-api/.env.example`。
- Hogo 演示口令、APS 数据库口令与 JWT 密钥都在 `deploy/gz-api/.env`，**上线前必须覆盖默认值**。
- 仓库只放占位样例，不包含任何真实密钥、证书私钥或 token。

## 本地开发

```bash
# mfg 后端
cd projects/mfg-agent/api && cp .env.example .env && <启动 FastAPI>

# mfg 前端（原生静态，任意静态服务器即可）
cd projects/mfg-agent/web && python -m http.server 8080

# Hogo 前端 + 后端
cd projects/hogo-qa/frontend && npm ci && npm run dev
cd projects/hogo-qa/backend && npm start

# 智能 APS 前端
cd projects/smart-aps-agent/aps-frontend && npm ci && npm run dev
```

本地同源运行时 `projects/mfg-agent/web/config.js` 保持空串，前端会走同源探测；
只有发布脚本会把它改写为 `https://api.hulkai.cn`。

## 说明

- 静态站零构建：原生 HTML/CSS/JS，无外部字体依赖。
- mfg 前端静态文件带 `?v=` 版本号；改动后在 HTML 里同步提升版本，避免缓存旧文件。
- 页面与 API 全部走 HTTPS；鉴权使用 `Authorization: Bearer` + `localStorage`，未使用 Cookie。
