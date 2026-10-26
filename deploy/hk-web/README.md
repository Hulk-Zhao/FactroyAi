# 香港：静态站 + 反向代理

纯静态站 + Nginx，外加一个 `hogo-qa` Node 容器。站点根目录挂载仓库的 `site/public`，
各项目前端挂载 `projects/`，`/api/*` 反代到广州后端，`/demo/hogo-qa/` 反代到本机容器。

## 卷挂载

| 宿主机 | 容器内 | 用途 |
|---|---|---|
| `../../site/public` | `/usr/share/nginx/html` | 主页、博客、Demo 目录 |
| `../../projects` | `/srv/projects` | `/demo/<项目>/` 的静态应用 |
| `letsencrypt`（卷） | `/etc/letsencrypt` | 证书 |
| `certbot_www`（卷） | `/var/www/certbot` | webroot 校验 |
| `hogo_runtime`（卷） | `/app/backend/data/runtime` | hogo-qa 的 HMAC 密钥 |

卷名沿用 `mfg_letsencrypt` / `mfg_certbot_www`，避免已在线的证书失效；
`hulkai_hogo_runtime` 新建。

## 首次部署

```bash
# 0) 首次：生成 .env（只放 hogo-qa 的口令）
cp deploy/hk-web/.env.example deploy/hk-web/.env   # 改 HOGO_AUTH_PASSWORD

# 1) 先用 HTTP-only 配置起 Nginx（此时还没有证书，加载 443 会启动失败）
#    在仓库根目录执行：
NGINX_CONF=default.http.conf docker compose -f deploy/hk-web/docker-compose.yml up -d nginx

# 2) 申请证书
docker compose -f deploy/hk-web/docker-compose.yml run --rm certbot certonly \
  --webroot -w /var/www/certbot \
  -d hulkai.cn -d www.hulkai.cn --email you@example.com --agree-tos -n

# 3) 切回正式配置并起完整栈
docker compose -f deploy/hk-web/docker-compose.yml up -d --force-recreate nginx
docker compose -f deploy/hk-web/docker-compose.yml up -d --build
```

Nginx 用变量 + Docker 内置 DNS（`resolver 127.0.0.11`）解析 `hogo-qa`，
所以第 1 步只起 nginx 时不会因为 `hogo-qa` 未启动而报 `host not found in upstream`。

## 更新

```bash
git pull
# 静态文件是 bind mount，拉取后即生效；hogo-qa 源码变了要重建镜像
docker compose -f deploy/hk-web/docker-compose.yml up -d --build
docker compose -f deploy/hk-web/docker-compose.yml exec nginx nginx -s reload
```

## 后端地址

写在 `nginx/default.conf` 的 `upstream gz_backend`：`129.204.108.45:8004`。
改地址后 `nginx -s reload` 即可。

## /demo 路由

```
/demo/mfg/       →  alias /srv/projects/mfg-agent/web/
/demo/hogo-qa/   →  反代 hogo-qa:8788（rewrite 去掉前缀）
```

`mfg` 用 `^~` 前缀匹配 + alias，优先于静态资源正则；`hogo-qa` 同样用 `^~`，
避免 `/demo/hogo-qa/assets/*.js` 被 30d 缓存规则截走（它由后端按内容哈希自行设置缓存头）。
两者在没有尾斜杠时都会 301 到带斜杠地址。

新增项目时复制对应那段并改目录名/服务名。

> 证书续期后需要重载 Nginx，建议宿主机 cron 每周执行一次
> `docker compose -f deploy/hk-web/docker-compose.yml exec nginx nginx -s reload`。
