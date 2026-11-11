# 香港：静态站 + 反向代理（已退役，仅作回滚参考）

> **2026-11 起不再使用。** 现行部署为「COS + CDN 静态站 + 广州单机 API」，
> 见 [deploy/README.md](../README.md)。本目录只在需要整体回退到香港旧架构时使用；
> 回滚前先确认旧证书覆盖 `hulkai.cn` / `www.hulkai.cn`。

纯静态站 + Nginx，外加 `hogo-qa`（Node 容器）与 `smart-aps-frontend`（Vue 产物）两个本机服务。
站点根目录挂载仓库的 `site/public`，各项目前端挂载 `projects/`，`/api/*` 反代到广州后端，
`/demo/hogo-qa/`、`/demo/mfg-agent-platform/` 反代到本机容器。

## 卷挂载

| 宿主机 | 容器内 | 用途 |
|---|---|---|
| `./nginx/templates` | `/etc/nginx/templates` | Nginx 配置模板（启动时渲染） |
| `../../site/public` | `/usr/share/nginx/html` | 主页、博客、Demo 目录 |
| `../../projects` | `/srv/projects` | `/demo/<项目>/` 的静态应用 |
| `letsencrypt`（卷） | `/etc/letsencrypt` | 证书 |
| `certbot_www`（卷） | `/var/www/certbot` | webroot 校验 |
| `hogo_runtime`（卷） | `/app/backend/data/runtime` | hogo-qa 的 HMAC 密钥 |

卷名沿用 `mfg_letsencrypt` / `mfg_certbot_www`，避免已在线的证书失效；
`hulkai_hogo_runtime` 新建。

## 对外域名（可切换）

Nginx 配置不再写死域名：容器启动时用 `envsubst` 渲染 `nginx/templates/*.template`。
域名全部来自 `deploy/hk-web/.env`：

| 变量 | 默认值 | 作用 |
|---|---|---|
| `PUBLIC_DOMAIN` | `hulkai.xyz` | 对外主域名，写入 `server_name` |
| `PUBLIC_DOMAIN_ALIASES` | `hulkai.cn www.hulkai.cn www.hulkai.xyz` | 兼容域名，同时写入 `server_name` |
| `CERT_DOMAIN` | `hulkai.xyz` | 证书 lineage 目录名（certbot 首签 `-d` 的第一个域名） |
| `NGINX_CONF` | `default` | `default`（含 443）或 `default.http`（首签证书时的 HTTP-only） |

备案通过后切回 `hulkai.cn`：只改 `.env` 的 `PUBLIC_DOMAIN=hulkai.cn`、`CERT_DOMAIN=hulkai.cn`
（证书按该域名签发后），再 `up -d --force-recreate nginx` 即可，不用改配置、不用重建前端。

## 首次部署

```bash
# 0) 生成 .env（域名变量有默认值，按需修改；HOGO_AUTH_PASSWORD 必改）
cp deploy/hk-web/.env.example deploy/hk-web/.env

# 1) 前置检查：域名必须解析到本机 43.132.214.124，否则证书签发会失败
dig +short hulkai.xyz A
dig +short www.hulkai.xyz A
#   两者都应为 43.132.214.124

# 2) 先用 HTTP-only 配置起 Nginx（此时还没有证书，加载 443 会启动失败）
#    在仓库根目录执行：
NGINX_CONF=default.http docker compose -f deploy/hk-web/docker-compose.yml up -d nginx

# 3) 用 HTTP 校验当前机器确实对外提供该域名（返回 ok）
curl -I http://hulkai.xyz/

# 4) 申请 hulkai.xyz 证书（webroot 走 80 端口）
docker compose -f deploy/hk-web/docker-compose.yml run --rm certbot certonly \
  --webroot -w /var/www/certbot \
  -d hulkai.xyz -d www.hulkai.xyz --email you@example.com --agree-tos -n

# 5) 切回正式配置（含 443）并起完整栈
docker compose -f deploy/hk-web/docker-compose.yml up -d --force-recreate nginx
docker compose -f deploy/hk-web/docker-compose.yml up -d --build
```

Nginx 用变量 + Docker 内置 DNS（`resolver 127.0.0.11`）解析 `hogo-qa` / `smart-aps-frontend`，
所以第 2 步只起 nginx 时不会因为容器未启动而报 `host not found in upstream`。

## 更新

```bash
git pull
# 静态文件是 bind mount，拉取后即生效；容器源码变了要重建镜像
docker compose -f deploy/hk-web/docker-compose.yml up -d --build
docker compose -f deploy/hk-web/docker-compose.yml exec nginx nginx -s reload
```

## 后端地址

写在 `nginx/templates/default.conf.template` 的 `upstream gz_backend`：`129.204.108.45:8004`。
APS 前端容器的后端地址由 `.env` 的 `APS_BACKEND_URL` 控制（默认 `http://129.204.108.45:8085`）。
改地址后 `nginx -s reload` / 重建容器即可。

## /demo 路由

```
/demo/mfg/                →  alias /srv/projects/mfg-agent/web/
/demo/hogo-qa/            →  反代 hogo-qa:8788（rewrite 去掉前缀）
/demo/mfg-agent-platform/ →  反代 smart-aps-frontend:8080（rewrite 去掉前缀）
```

`mfg` 用 `^~` 前缀匹配 + alias，优先于静态资源正则；`hogo-qa` / `mfg-agent-platform`
同样用 `^~`，避免 `/demo/<project>/assets/*.js` 被 30d 缓存规则截走。
三者没有尾斜杠时都会 301 到带斜杠地址。

新增项目时复制对应那段并改目录名/服务名。

> 证书续期由 `certbot` 容器每 12h 自动执行 `certbot renew`；续期后需要重载 Nginx，
> 建议宿主机 cron 每周执行一次
> `docker compose -f deploy/hk-web/docker-compose.yml exec nginx nginx -s reload`。
