# OrcaTerm 上线命令清单

> 面向「在腾讯云 OrcaTerm 里登录广州服务器、把代码拉下来直接部署」的运维方式。
> 所有命令按顺序复制即可；带 `TODO` 的位置需要替换成你自己的值。
>
> 代码仓库：`<YOUR_GIT_REPO_URL>`（TODO：换成新仓库地址）
> 固定目录：`/opt/hulkai/FactroyAi`
> 镜像来源：默认 **GHCR pull**；无 CI 时可用服务器本地 build（见第 3 节）。

---

## 0. 先记住两件事

1. **静态站不在服务器上**。`hulkai.cn` / `www` 由 COS + CDN 提供，
   前端由本地 Windows 跑 `deploy/cos/publish.ps1` 上传，**服务器不需要 Node，也不需要构建前端**。
2. **服务器只跑 API**：`api.hulkai.cn` → Nginx → mfg / hogo / aps 三个容器，
   外加 Redis 与 PostgreSQL。安全组只需 `22`、`80`、`443`。

---

## 1. 环境检查

```bash
git --version
docker --version
docker compose version
uname -m                      # 必须是 x86_64
free -h                       # 4G 内存；Swap 见第 3 节
df -h /                       # 至少留 20G
sudo ss -lntp | grep -E ':80|:443' || echo '80/443 空闲'
```

如果 Docker 未安装（Ubuntu）：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"   # 重新登录后生效；不想重新登录就用 sudo docker
```

---

## 2. 拉代码到固定目录

```bash
sudo mkdir -p /opt/hulkai
sudo chown -R "$USER":"$USER" /opt/hulkai

export REPO_URL='<YOUR_GIT_REPO_URL>'   # TODO 替换
cd /opt/hulkai
git clone "$REPO_URL" FactroyAi
cd FactroyAi/deploy/gz-api
```

后续更新（不动 `.env` 与 `certs/`）：

```bash
cd /opt/hulkai/FactroyAi
git pull --ff-only
```

---

## 3. 选择镜像路径（二选一）

| | A. GHCR `pull`（推荐） | B. 服务器本地 `build` |
| --- | --- | --- |
| 前提 | 新仓库已跑过 GitHub Actions | 无 CI，或 CI 不可用 |
| 服务器负载 | 只下载镜像 | 编译 3 个后端，峰值 3–4G 内存 |
| 耗时 | 数分钟（取决于带宽） | 20–40 分钟，且要先加 swap |
| 可回滚性 | 换 `IMAGE_TAG` 即可 | 需重新 build |
| 适用 | 日常上线 | 临时兜底 |

### A. GHCR（默认推荐）

```bash
# 私有包才需要登录；token 只保存在服务器上，不要写进仓库或文档
# export GHCR_USERNAME='<github 用户名>'
# export GHCR_READ_TOKEN='<仅 read:packages 的 PAT>'
echo "$GHCR_READ_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin

docker compose config --quiet        # 校验 REGISTRY / IMAGE_TAG 已生效
docker compose pull
docker compose up -d --no-build
```

`.env` 里必须与 CI Summary 一致：

```dotenv
REGISTRY=ghcr.io/<owner>/<repo>      # 全小写
IMAGE_TAG=sha-1a2b3c4                # 对应某次提交，不要用 latest
```

### B. 服务器本地 build（仅兜底）

4C4G 必须先加 **2G swap**，否则 Maven 编译阶段会被 OOM Kill：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
COMPOSE_PARALLEL_LIMIT=1 docker compose build    # 串行构建，避免同时吃满内存
docker compose up -d
```

本地 build 时 `REGISTRY` / `IMAGE_TAG` 也要填（仅作为镜像名，不从远端拉取），
例如 `REGISTRY=local`、`IMAGE_TAG=dev`；下次切回 GHCR 前记得改回真实值。

---

## 4. 生成 `.env`

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
cp .env.example .env
chmod 600 .env
```

先自动生成两个随机密钥（避免手抄）：

```bash
sed -i "s|^AUTH_SECRET=.*|AUTH_SECRET=$(openssl rand -hex 32)|" .env
sed -i "s|^APS_SECURITY_JWT_SECRET=.*|APS_SECURITY_JWT_SECRET=$(openssl rand -hex 32)|" .env
```

再 `vi .env` 逐项填写。**上线前必须改的键**：

| 分组 | 键 | 说明 |
| --- | --- | --- |
| 镜像 | `REGISTRY`、`IMAGE_TAG` | 与 CI Summary 一致；本地 build 见第 3 节 B |
| mfg 认证 | `AUTH_SECRET`、`MFG_ADMIN_PASS` | 上面已生成 secret；口令必须改掉 `change-me` |
| mfg 模型 | `QW_KEY`、`DS_KEY`、`DASHSCOPE_API_KEY`、`RERANK_API_KEY` | 对应阿里云百炼 / DeepSeek |
| APS | `APS_DB_PASSWORD`、`APS_ADMIN_INIT_PASSWORD` | 长随机串，不要用示例值 |
| APS WebSocket | `APS_SECURITY_WS_ALLOWED_ORIGINS` | 必须是 `https://hulkai.cn,https://www.hulkai.cn` |
| Hogo | `HOGO_AUTH_PASSWORD` | 演示门禁口令，必须改 |
| 静态灾备 | `SERVE_STATIC_ORIGIN` | 保持 `0`（静态在 COS） |

不需要改：`API_DOMAIN`、`SITE_DOMAIN`、`SITE_ORIGIN_*`、端口、`REDIS_URL`、`DB_URL`。

检查必填项有没有漏（没有输出即通过）：

```bash
grep -nE '^(AUTH_SECRET|MFG_ADMIN_PASS|QW_KEY|DS_KEY|APS_SECURITY_JWT_SECRET|APS_DB_PASSWORD|HOGO_AUTH_PASSWORD)=$' .env
grep -n 'change-me' .env
grep -n 'your-github-name' .env      # 有输出说明 REGISTRY 还没换成真实地址
```

---

## 5. 放证书并设权限

`api.hulkai.cn` 直连本机，必须有覆盖它的证书（推荐 `*.hulkai.cn` 泛域名证书）：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
mkdir -p certs
# 用 OrcaTerm 的文件上传 / 或 scp 传上来，然后改名：
#   certs/api.hulkai.cn.pem   （fullchain，包含中间证书）
#   certs/api.hulkai.cn.key   （私钥）
chmod 600 certs/*.key
chmod 644 certs/*.pem
ls -l certs/
```

自检证书是否覆盖 API 域名（`subjectAltName` 里要有 `api.hulkai.cn`）：

```bash
openssl x509 -in certs/api.hulkai.cn.pem -noout -subject -dates
openssl x509 -in certs/api.hulkai.cn.pem -noout -ext subjectAltName
```

> 证书链不全时浏览器/curl 会报 `unable to get local issuer certificate`，
> `.pem` 必须是「站点证书 + 中间证书」的完整链，不能只放站点证书。
> 链是否完整留到 7.2 启动后验证。

---

## 6. 首次启动

证书就绪后直接起正式配置（`NGINX_CONF=api`，带 443）：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose up -d --no-build        # 本地 build 场景去掉 --no-build
docker compose ps
```

首次启动较慢，属正常：

- `app`：构建 2594 条向量索引，约 1–3 分钟；
- `aps-backend`：Flyway 建 39 张表，约 1–2 分钟；
- `hogo-qa`：秒级。

```bash
docker compose logs -f app          # Ctrl+C 退出
docker compose logs -f aps-backend
```

### 6.1 导入 APS 演示数据（首次一次，可选）

`aps-backend` 变成 `healthy` 之后，表结构（39 张表）才算就位。此时才导演示数据，
**不要**把种子放进 PG 镜像的初始化目录（那时 Flyway 还没建表）。

先判断要不要导：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose exec -T aps-postgres psql -U aps -d aps -Atc "
select 'tables='||count(*) from information_schema.tables where table_schema='public';
select 'demo_orders='||count(*) from orders
 where id::text like 'd0000000-0000-0000-0000-0000000007%';"
```

- `tables` 不到 39 或报 `relation "orders" does not exist`：后端还没迁移完，先看 6 节日志；
- `tables=39`、`demo_orders=0`：需要导入；
- `demo_orders=9`：已在，跳过这一步即可。

导入（幂等，重复执行安全；`up -d` 不会自动跑它）：

```bash
docker compose --profile seed run --rm aps-seed
```

最后一行就是结论：

| 输出 | 含义 |
| --- | --- |
| `SEEDED: APS 演示数据已导入（... orders=9 ...）` | 本次导入成功 |
| `SKIP: APS 演示数据已存在（9/9 演示订单齐全），本次未做任何修改` | 已有数据，什么都没动 |
| `FAIL: APS 演示数据导入失败（psql 退出码 N）...` | 事务已回滚，按上方 `ERROR` 排查 |

日期是按执行当天算的：订单交期 = 当天 + 3…10 天，单号 `SO-<当天>-000N`，
日历/工艺路线/BOM/策略生效日 = 当天。隔天不会自动改期；要刷新演示日期，先按
[data-persistence.md 6.3.2](data-persistence.md#632-两条路径的取舍) 清演示订单，再导一次。

> 本地 Windows 开发仍用 `.\scripts\seed-m1-demo.ps1`（带 `-Clean`，还会顺带起本地库）；
> 服务器不要装 `pwsh`，直接用上面的 `aps-seed`。两条路径的数据内容与固定 UUID 完全一致。

如果证书还没准备好，先用 HTTP 模式冒烟（不占 443）：

```bash
NGINX_CONF=api.http docker compose up -d --no-build
# 证书放好后：
sed -i 's/^NGINX_CONF=.*/NGINX_CONF=api/' .env
docker compose up -d --no-build nginx
```

---

## 7. 验证

### 7.1 容器与健康检查

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose ps                     # 全部 Up / healthy
docker compose images                 # 确认镜像 tag 就是 .env 里的 IMAGE_TAG

curl -fsS http://127.0.0.1:8004/api/v2/health
curl -fsS http://127.0.0.1:8085/api/v1/health
docker compose exec nginx wget -qO- http://hogo-qa:8788/api/health

# APS：39 张表 + 演示数据是否在位（6.1 导入后应为 tables=39、demo_orders=9）
docker compose exec -T aps-postgres psql -U aps -d aps -Atc "
select 'tables='||count(*) from information_schema.tables where table_schema='public';
select 'demo_orders='||count(*) from orders where id::text like 'd0000000-0000-0000-0000-0000000007%';"
```

### 7.2 `api.hulkai.cn`

```bash
curl -I https://api.hulkai.cn/healthz
curl -I https://api.hulkai.cn/api/v2/health
```

应看到 `HTTP/2 200`、`server: nginx` 与安全响应头。
如果 curl 报证书错误，先在本机查链是否完整（应输出 `Verify return code: 0 (ok)`）：

```bash
openssl s_client -connect 127.0.0.1:443 -servername api.hulkai.cn </dev/null 2>/dev/null \
  | grep -E 'subject=|Verify return code'
```

若 DNS 还没生效，临时绕过解析验证：

```bash
curl -I --resolve api.hulkai.cn:443:129.204.108.45 https://api.hulkai.cn/healthz
```

跨域预检（必须 204 且带白名单 Origin）：

```bash
curl -i -X OPTIONS https://api.hulkai.cn/api/v2/auth/login \
  -H 'Origin: https://hulkai.cn' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type,x-trace-id'
```

### 7.3 静态站（经 CDN → COS）

```bash
curl -I https://hulkai.cn/
curl -I https://hulkai.cn/demo/hogo-qa/
curl -I https://hulkai.cn/demo/mfg-agent-platform/
curl -sI https://hulkai.cn/index.html | grep -i -E 'HTTP|cache-control|x-cache'
curl -sI https://hulkai.cn/demo/mfg/config.js | grep -i -E 'HTTP|cache-control'
```

DNS 未切换时用 CDN 的 CNAME 直接验证：

```bash
curl -sI -H 'Host: hulkai.cn' https://hulkai.cn.cdn.dnsv1.com/
```

页面验收：`https://hulkai.cn/demo/mfg/` 右上角后端状态应为「在线」（说明跨域打通）。

---

## 8. 常见失败对照

| 现象 | 常见原因 | 处理 |
| --- | --- | --- |
| `port is already allocated` / nginx 起不来 | 宿主机已有 Nginx/Apache 占用 80/443 | `sudo ss -lntp \| grep -E ':80\|:443'`，停掉宿主服务或改用容器 |
| 外部访问 443 超时，本机 curl 正常 | 安全组/防火墙未放行 443 | 控制台安全组放行 TCP 22/80/443；`sudo ufw status` |
| 浏览器报证书不受信任 / curl `unable to get local issuer` | 证书链不全（只放了站点证书） | `.pem` 换成 fullchain（站点 + 中间证书） |
| nginx 反复重启，日志 `cannot load certificate` | 文件名与 `API_DOMAIN` 不一致，或权限不对 | 文件名必须是 `api.hulkai.cn.pem` / `.key`；`chmod 600 *.key` |
| 容器反复重启，日志提示缺变量 | `.env` 缺键或仍是空值 | 用第 4 节的 grep 检查；`docker compose config --quiet` |
| `pull access denied` / `unauthorized` | 未登录 GHCR，或 token 无 `read:packages` | `docker login ghcr.io`；私有包必须登录 |
| `api.hulkai.cn` 打不开 / 解析到旧 IP | DNS 未生效或仍指向香港 | `dig +short api.hulkai.cn`；用 `--resolve` 先确认服务正常，再等 TTL |
| `https://hulkai.cn/` 404 或 403 | 桶内没有对象 / 回源 HOST 填错 | 本地先 `publish.ps1` 上传；CDN 回源 HOST 用桶域名 |
| 页面能开但接口全红 | 跨域或 API 域名错 | 7.2 的 OPTIONS 检查；确认前端构建时 `API_BASE` 正确 |
| 构建阶段 `Killed`（本地 build） | 内存不足 | 先加 2G swap，并 `COMPOSE_PARALLEL_LIMIT=1` |

---

## 9. 发版与回滚

```bash
cd /opt/hulkai/FactroyAi
git pull --ff-only
cd deploy/gz-api

# 取 CI Summary 里的新 tag
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=sha-1a2b3c4/' .env
docker compose pull
docker compose up -d --no-build
docker compose ps
```

回滚到上一个 tag：

```bash
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=sha-previous/' .env
docker compose pull
docker compose up -d --no-build
```

> 镜像 tag 与提交一一对应，三个服务共用同一个 tag，整体回滚即可。
>
> **数据不要动**：业务数据在命名卷 `mfg_data` / `hulkai_aps_pgdata` / `hulkai_hogo_runtime`，
> 回滚镜像不涉及它们。**禁止 `docker compose down -v`**（会删卷）。
> 迁移旧库、备份恢复、部署后 30 秒核对见 [data-persistence.md](data-persistence.md)；
> 日常备份在服务器执行 `bash deploy/gz-api/backup.sh`。

---

## 10. 静态走 COS 后不再需要的项

以下内容**只在灾备**（CDN 临时改回自有源）时才用，当前无需准备：

- `/opt/hulkai/site` 目录：不需要创建。`SERVE_STATIC_ORIGIN=0` 时 compose 不会读取它。
- CDN 回源 IP 段：不需要收集。`deploy/gz-api/nginx/realip.conf` **保持空名单**即可，
  只保留 `real_ip_header` / `real_ip_recursive` 两行，不会生效也不影响启动。
- `certs/hulkai.cn.pem|key`：不需要。静态源站关闭时不会被加载。
- 安全组只需放行 `22/80/443`，不需要为 CDN 回源额外放行地址段（回源目标是 COS，不是这台机器）。

需要灾备时，按 `deploy/README.md` 第 7.8 节操作。
