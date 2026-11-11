# OrcaTerm 上线命令清单

> 面向「在腾讯云 OrcaTerm 里登录广州服务器、把代码拉下来直接部署」的运维方式。
> 所有命令按顺序复制即可；带 `TODO` 的位置需要替换成你自己的值。
>
> 代码仓库：`<YOUR_GIT_REPO_URL>`（TODO：换成新仓库地址）
> 固定目录：`/opt/hulkai/FactroyAi`
> 镜像来源：**当前线上为服务器本地构建**（`.env` 里 `REGISTRY=local`、`IMAGE_TAG=local`，
> 见 3.B）；GHCR / TCR pull 是可选通道（3.A、4.1），不是线上默认。
>
> **顺序不能颠倒**：第 2–5 节（克隆仓库、拉镜像并校验 tag、`.env`、证书）全部在旧栈
> **运行中**完成，第 6 节才是停机窗口。仓库 / `.env` / 镜像 tag 任何一项没就绪就停旧栈，
> 会直接把站点置于长时间下线——这一条已经真实发生过一次（`cd /opt/hulkai/FactroyAi` 报
> `No such file or directory`，因为仓库从未克隆）。

---

## 0. 先记住两件事

1. **静态站不在服务器上**。`hulkai.cn` / `www` 由 COS + CDN 提供，
   前端由本地 Windows 跑 `deploy/cos/publish.ps1` 上传，**服务器不需要 Node，也不需要构建前端**。
2. **服务器只跑 API**：`api.hulkai.cn` → Nginx → mfg / hogo / aps 三个容器，
   外加 Redis 与 PostgreSQL。安全组只需 `22`、`80`、`443`。

---

## 0.1 时间线：哪些步骤可以在旧栈运行时做

| 阶段 | 小节 | 动作 | 旧栈 | 会不会掉线 |
| --- | --- | --- | --- | --- |
| 准备 | 2 | `mkdir` + `chown` + `git clone` | 运行中 | 不会 |
| 准备 | 4 | 复制并填写 `.env` | 运行中 | 不会 |
| 准备 | 3 | `docker compose config --quiet` | 运行中 | 不会（只解析配置，不建容器） |
| 准备 | 3 | `docker login` + `docker compose pull` | 运行中 | 不会（只占磁盘与带宽） |
| 准备 | 3 | `docker manifest inspect` 校验三个 tag | 运行中 | 不会 |
| 准备 | 5 | 放证书 + `openssl x509` 自检 | 运行中 | 不会 |
| 准备 | 6.0 | 三项硬门槛自检 | 运行中 | 不会 |
| **停机** | 6.1 | 停系统 nginx、停旧容器 | 停止 | **开始掉线** |
| **停机** | 6.2 | `docker compose up -d --no-build` | 新栈启动 | 掉线（首次 1–3 分钟） |
| **停机** | 6.3 | 健康检查；不通过立刻 6.4 回滚 | — | 回滚后恢复 |

> 结论：**只有 6.1–6.2 需要停机**。凡是能在旧栈运行时做完的，绝不要留到停栈之后才发现缺东西。

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

## 2. 准备目录并克隆仓库（第 0 步，不可跳过）

**仓库不在这台机器上时，后面每一条命令都会失败。** 已经实测过的表现：

| 你执行 | 实际结果 |
| --- | --- |
| `cd /opt/hulkai/FactroyAi` | `-bash: cd: /opt/hulkai/FactroyAi: No such file or directory` |
| 在任意目录 `docker compose up -d` | `no configuration file provided: not found` |
| 在别的目录恰好有同名 compose | 起错服务（最坏情况），且报错里完全看不出"仓库没克隆" |

所以克隆是准备阶段的第一步；它**不需要停机**，必须在停旧栈之前完成。

```bash
sudo mkdir -p /opt/hulkai
sudo chown -R "$USER":"$USER" /opt/hulkai

export REPO_URL='<YOUR_GIT_REPO_URL>'   # TODO 替换
cd /opt/hulkai
test -d FactroyAi/.git || git clone "$REPO_URL" FactroyAi

cd /opt/hulkai/FactroyAi/deploy/gz-api
pwd
ls -l docker-compose.yml .env.example
```

必须看到：`pwd` 输出 `/opt/hulkai/FactroyAi/deploy/gz-api`，且能列出 `docker-compose.yml`
与 `.env.example` 两个文件。看不到就别往下走。

后续更新（不动 `.env` 与 `certs/`）：

```bash
cd /opt/hulkai/FactroyAi
git pull --ff-only
```

---

## 3. 拉取镜像并校验 tag（旧栈运行时执行）

| | A. registry `pull`（可选） | B. 服务器本地构建（当前线上） |
| --- | --- | --- |
| 前提 | 已跑过 GitHub Actions，且 GHCR/TCR 可达 | 无 CI，或 registry 拉不动 |
| 服务器负载 | 只下载镜像 | 串行编译 3 个后端，峰值 3–4G 内存 |
| 耗时 | 数分钟（取决于带宽） | hogo / aps 数分钟；mfg 改镜像源后实测 77 秒 |
| 可回滚性 | 换 `IMAGE_TAG` 即可 | 需重新 build，没有 registry 可回拉 |
| 适用 | 多机分发 | 单机、网络受限（当前线上） |

### A. GHCR / TCR（可选）

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

# 私有包才需要登录；token 只保存在服务器上，不要写进仓库或文档
# export GHCR_USERNAME='<github 用户名>'
# export GHCR_READ_TOKEN='<仅 read:packages 的 PAT>'
echo "$GHCR_READ_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin

docker compose config --quiet        # 只解析配置：仓库/ .env / 变量插值一次暴露
docker compose pull                  # 只拉取，不构建，不影响运行中的旧栈
```

`.env` 里必须与 CI Summary 一致：

```dotenv
REGISTRY=ghcr.io/<owner>/<repo>      # 必须全小写；拉不动时换成 TCR，见 4.1
IMAGE_TAG=sha-1a2b3c4                # 触发构建的那次提交，不是 HEAD；不要用 latest
```

**`REGISTRY` 必须全小写。** GitHub 上 owner 显示为 `Hulk-Zhao`，但镜像路径只能写
`hulk-zhao`；写成大写时 `docker pull` 会在**鉴权之前**就报
`invalid reference format: repository name (...) must be lowercase`，
和登录、权限、包可见性都无关。CI Summary 里打印的 `REGISTRY` 已经是小写，
手输时按下面强制转换：

```bash
REGISTRY_LOWER="ghcr.io/$(printf '%s' '<owner>/<repo>' | tr '[:upper:]' '[:lower:]')"
echo "$REGISTRY_LOWER"
```

> `docker login -u "$GHCR_USERNAME"` 里的 GitHub 用户名**可以大写**（那是账号名），
> 只有 `ghcr.io/...` 这一段镜像路径必须小写，别把两者混在一起。

代入 `REGISTRY=ghcr.io/hulk-zhao/factroyai`、`IMAGE_TAG=sha-df57858`，compose 实际拉取的是：

```text
ghcr.io/hulk-zhao/factroyai-mfg-api:sha-df57858
ghcr.io/hulk-zhao/factroyai-aps-backend:sha-df57858
ghcr.io/hulk-zhao/factroyai-hogo-qa:sha-df57858
```

服务名是用 `-` 直接接在前缀后的（与 CI 的 `docker push` 逐字一致），**不是** `/mfg-api`。
GHCR 允许嵌套仓库路径（`a/b/c`），所以 `factroyai/mfg-api` 与 `factroyai-mfg-api`
是两个完全不同的镜像，混用会永远拉不到刚推的镜像。

**`IMAGE_TAG` 怎么取：不能用 `git rev-parse --short=7 HEAD`。**
workflow 有 `paths` 过滤，只有改动 `projects/**` 或 `.github/workflows/build-images.yml`
的提交才会构建镜像；只改文档/deploy 的提交（例如 `3c0641a`）不会产出任何 tag。
拿这种 HEAD 去拉，`gate3` 会三个镜像全 MISSING，看起来像镜像没发布，实际镜像挂在
上一个触发构建的提交上。

直接反查最近一次真正触发构建的提交：

```bash
cd /opt/hulkai/FactroyAi
BUILD_SHA=$(git log -1 --format=%H -- projects .github/workflows/build-images.yml | cut -c1-7)
git show -s --format='构建提交：%h %s (%ci)' "$BUILD_SHA"
cd deploy/gz-api
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=sha-$BUILD_SHA/" .env
grep '^IMAGE_TAG=' .env
```

- 反查结果只是**候选**：还要在 GitHub Actions 里确认该提交的 `build-images` 运行成功
  （job 级 `if` + `alias` 会为未重建的镜像补 tag，一次成功运行即可覆盖三个镜像）。
- 拿不到历史（浅克隆）时先 `git fetch --unshallow`，或直接从 CI Summary 复制 tag。
- 手动 `Run workflow` 会以当次 HEAD 产出 tag，这种情况下 HEAD 的 sha 才成立。

**逐个确认三个镜像的 tag 真实存在**（缺一个，`up` 就会在停机后失败）：

```bash
REGISTRY=$(grep -E '^REGISTRY=' .env | cut -d= -f2)
TAG=$(grep -E '^IMAGE_TAG=' .env | cut -d= -f2)
for svc in mfg-api aps-backend hogo-qa; do
  docker manifest inspect "$REGISTRY-$svc:$TAG" >/dev/null \
    && echo "ok   $svc:$TAG" \
    || echo "MISS $svc:$TAG  ← 这个 tag 不存在，先不要停旧栈"
done
```

**要不要用 `:latest`**：`:latest` 是「该镜像最近一次构建」的可变指针，不一定等于你选的 sha。
`alias` job 会把未重建镜像的 `:latest` digest 补成同一个 sha tag，所以同一次成功运行下，
两者通常指向同一 manifest。用 digest 对比确认：

```bash
for svc in mfg-api aps-backend hogo-qa; do
  echo "== $svc"
  docker manifest inspect -v "$REGISTRY-$svc:latest" | grep -m1 -o '"digest": *"[^"]*"'
  docker manifest inspect -v "$REGISTRY-$svc:$TAG"    | grep -m1 -o '"digest": *"[^"]*"'
done
```

两行 digest 相同：本次可以临时用 `:latest` 冒烟；不同：`:latest` 指向别的构建，
**生产仍用固定 `sha-xxxxxxx`**，便于整体回滚和定位提交。

> **401 不代表镜像不存在**：GHCR 对未认证请求一律返回 `401 unauthorized`，
> 私有包匿名拉取也是 401，无法区分「不存在」和「没权限」。
> 判断存在性必须**先登录**再 `docker manifest inspect`；
> 返回 `not found` / `manifest unknown` 才是真的不存在。

### B. 服务器本地构建（当前线上实际路径）

**什么时候用**：`docker pull` 从 GHCR 极慢、`docker push` 到 TCR 也极慢，或 CI 不可用。
2026-11-11 线上就是这条路径（clone 能通，镜像层拉/推都卡死）。

4C4G 必须先加 **2G swap**，否则 Maven 编译阶段会被 OOM Kill：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

`.env` 只改两行（`IMAGE_TAG` 用固定值即可，本地没有远端 tag 需要对齐）：

```dotenv
REGISTRY=local
IMAGE_TAG=local
```

**串行、由小到大构建**（同时构建会超过 4G 物理内存）：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
export REGISTRY=local IMAGE_TAG=local

COMPOSE_PARALLEL_LIMIT=1 docker compose build hogo-qa
COMPOSE_PARALLEL_LIMIT=1 docker compose build aps-backend
COMPOSE_PARALLEL_LIMIT=1 docker compose build app       # mfg-api 最后

docker images | grep -E 'local-(mfg-api|aps-backend|hogo-qa)'
docker compose up -d --no-build
```

实测（广州 4C4G，2026-11-11）：

| 镜像 | 体积 | 备注 |
| --- | --- | --- |
| hogo-qa | 171 MB | Node 前端构建 |
| aps-backend | 469 MB | Maven 编译，峰值内存最高 |
| mfg-api | 851 MB | 首次 `apt-get update` 卡死 4 分钟；改腾讯云 apt 源后单次构建 **77 秒** |

mfg-api 的 apt 源已写进 `projects/mfg-agent/api/Dockerfile`：`ARG APT_MIRROR`
默认 `http://mirrors.cloud.tencent.com`，构建时改写 deb822 的 `debian.sources`
（amd64 / arm64 同一套）；要回官方源加 `--build-arg APT_MIRROR=http://deb.debian.org`。

本地构建没有远端 tag，`manifest inspect` 校验跳过，改为确认 `docker images` 里三个镜像都在；
`docker compose pull` 对 `local-*` 会失败，起栈必须带 `--no-build`。

> **本地构建的镜像不带来源标记**：只在本机 `/var/lib/docker`，没有 registry 可回拉。
> 换机器、重装、清理镜像后必须重新构建；要跨机分发就走 CI（A）或 `docker save` /
> `docker load`。回切 registry 时把 `REGISTRY` / `IMAGE_TAG` 改回真实值再 `pull`。

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

再 `vi .env` 逐项填写。**必填键、缺失后果与生成方式**：

| 键 | 必填 | 缺失 / 保留占位符的现象 | 怎么生成 / 怎么填 |
| --- | --- | --- | --- |
| `REGISTRY` | 是 | 未替换：compose 插值报 `请先在 .env 配置 REGISTRY`，**一个容器都起不来**；含大写：`pull` 在鉴权前报 `invalid reference format ... must be lowercase` | 填 CI Summary 的 `ghcr.io/<owner>/<repo>`；owner 一律小写（`Hulk-Zhao` → `hulk-zhao`） |
| `IMAGE_TAG` | 是 | 缺省会退成 `latest`（不知道跑哪次提交）；对应 tag 不存在时 `pull`/`up` 失败 | 填**触发构建的提交**的 `sha-xxxxxxx`（第 3 节反查），不要用 HEAD；回滚填上一个可用 sha |
| `AUTH_SECRET` | 是 | mfg 每次重启后旧登录态全部失效，表现为"刚登录就 401" | `openssl rand -hex 32` |
| `MFG_ADMIN_PASS` | 是 | 仍是 `change-me`：后台可用默认口令登录（容器照常启动，不会报错） | 自定强口令 |
| `QW_KEY` | 是 | mfg 的向量化/检索链路 401/5xx，知识库检索不可用（`/health` 仍可能 200） | 阿里云百炼控制台 |
| `DASHSCOPE_API_KEY` | 是 | embedding 调用 401/403，检索与案例匹配不可用 | 同上 |
| `DS_KEY` | 是 | 对话/分析/报告接口 5xx 或 401 | DeepSeek 控制台 |
| `RERANK_API_KEY` | 是（`RERANK_ENABLED=true` 时） | 重排失败：接口报错或召回质量明显下降 | 百炼控制台 |
| `APS_DB_PASSWORD` | 是 | 连不上 PG：`aps-backend` 反复重启，日志 `password authentication failed`（数据还在） | `openssl rand -hex 32`；**卷首次初始化后就不要再改** |
| `APS_SECURITY_JWT_SECRET` | 是 | 空值/示例串：JWT 可被伪造或登录后立刻 401 | `openssl rand -hex 32` |
| `APS_ADMIN_INIT_PASSWORD` | 是（dev profile） | 不填则用示例口令，admin 账号可被猜解 | 自定强口令 |
| `APS_SECURITY_WS_ALLOWED_ORIGINS` | 是 | 浏览器 Origin 不在白名单 → WebSocket 403，求解进度不刷新 | 固定 `https://hulkai.cn,https://www.hulkai.cn` |
| `HOGO_AUTH_PASSWORD` | 是 | 仍是 `change-me`：演示门禁可被默认口令进入 | 自定强口令 |
| `HOGO_AUTH_SECRET` | 否 | 留空则由首启自动生成并写入卷 `hulkai_hogo_runtime`；卷被删且仍留空 → 会话全部失效 | 留空即可 |
| `NGINX_CONF` | 是（默认 `api`） | `api` 但证书缺失 → nginx `Exited (1)`，**80 也一起消失**（见 5.2） | 有证书填 `api`，没有先用 `api.http` |
| `SERVE_STATIC_ORIGIN` | 是（默认 `0`） | 设 `1` 但没有 `hulkai.cn` 证书 → nginx 快速失败 | 保持 `0`（静态在 COS） |
| `API_DOMAIN` / `SITE_DOMAIN` / `SITE_ORIGIN_*` | 保持默认 | 改错 → 证书文件名对不上、跨域预检失败 | 不需要改 |
| `APS_DB_NAME` / `APS_DB_USER` | 复用旧卷时必填对齐 | 与旧 PG 初始化时不一致 → `database "aps" does not exist` 或认证失败 | 见下方"复用旧数据"说明 |

不需要改：端口、`REDIS_URL`、`DB_URL`。

**复用旧数据卷时（保留 `hulkai_aps_pgdata`）**：PG 的库名/用户/口令在**第一次初始化时就写进数据目录**了，
`.env` 必须与旧容器一致，否则新容器连不上（报错看起来像"数据没了"，其实是认证/库名不符）。

```bash
docker inspect hulkai_aps_postgres-pre-switch \
  --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^POSTGRES_(DB|USER|PASSWORD)='
# 把输出的三个值原样写进 .env 的 APS_DB_NAME / APS_DB_USER / APS_DB_PASSWORD
```

检查必填项有没有漏（没有输出即通过）：

```bash
grep -nE '^(AUTH_SECRET|MFG_ADMIN_PASS|QW_KEY|DS_KEY|DASHSCOPE_API_KEY|RERANK_API_KEY|APS_DB_PASSWORD|APS_SECURITY_JWT_SECRET|HOGO_AUTH_PASSWORD)=$' .env
grep -n 'change-me' .env
grep -n 'your-github-name' .env      # 有输出说明 REGISTRY 还没换成真实地址
```

### 4.1 GHCR 拉不动时切换腾讯云 TCR（可选）

现象：`docker compose pull` 一直 `Retrying in 4 seconds`，某个层卡在几百 KB 十几分钟。
这是到 registry 的**网络层不可达**，不是镜像不存在，也不是鉴权问题，继续重试没有意义。

CI 会在配置了仓库 Secrets `TCR_USERNAME` / `TCR_PASSWORD` 时同时推 TCR（缺任一只推 GHCR，
不会报错）。开通与 Secrets 配置见 [README.md](README.md#49-ghcr-拉不动时切换腾讯云-tcr可选通道) 4.9。
推上去的地址：

```text
ccr.ccs.tencentyun.com/hulkai/factroyai-mfg-api:sha-xxxxxxx
ccr.ccs.tencentyun.com/hulkai/factroyai-aps-backend:sha-xxxxxxx
ccr.ccs.tencentyun.com/hulkai/factroyai-hogo-qa:sha-xxxxxxx
```

服务器切换（`IMAGE_TAG` 不变，只改 `REGISTRY`）：

```bash
echo '<TCR_PASSWORD>' | docker login ccr.ccs.tencentyun.com -u '<TCR_USERNAME>' --password-stdin

cd /opt/hulkai/FactroyAi/deploy/gz-api
sed -i 's|^REGISTRY=.*|REGISTRY=ccr.ccs.tencentyun.com/hulkai/factroyai|' .env
grep '^REGISTRY=' .env
docker compose config --quiet

TAG=$(grep -E '^IMAGE_TAG=' .env | cut -d= -f2)
for svc in mfg-api aps-backend hogo-qa; do
  docker manifest inspect "ccr.ccs.tencentyun.com/hulkai/factroyai-$svc:$TAG" >/dev/null \
    && echo "ok   $svc:$TAG" || echo "MISS $svc:$TAG"
done
docker compose pull
```

> `REGISTRY` 必须写到**仓库名前缀**（`.../hulkai/factroyai`）。只写 `ccr.ccs.tencentyun.com/hulkai`
> 会拼出 `.../hulkai-mfg-api`，少一层路径，TCR 找不到仓库。
> 回切同理：`sed -i 's|^REGISTRY=.*|REGISTRY=ghcr.io/hulk-zhao/factroyai|' .env` 后重新 `docker compose pull`。

---

## 5. 证书

`api.hulkai.cn` 直连本机，必须有覆盖它的证书（推荐 `*.hulkai.cn` 泛域名证书）。

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

### 5.1 有证书：`NGINX_CONF=api`（默认）

`.env` 保持 `NGINX_CONF=api`，nginx 同时监听 80 与 443，80 只保留 `/healthz`。

### 5.2 暂时没有证书：先用 `api.http` 冒烟

只监听 80，不占 443，能先把三个后端和路由验通：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
sed -i 's/^NGINX_CONF=.*/NGINX_CONF=api.http/' .env
docker compose up -d --no-build
curl -fsS -H 'Host: api.hulkai.cn' http://127.0.0.1/healthz
curl -fsS http://127.0.0.1:8004/api/v2/health
```

冒烟通过、证书放好之后切回正式配置：

```bash
ls -l certs/api.hulkai.cn.pem certs/api.hulkai.cn.key   # 两个文件都必须存在
openssl x509 -in certs/api.hulkai.cn.pem -noout -checkend 0   # 未过期
sed -i 's/^NGINX_CONF=.*/NGINX_CONF=api/' .env
docker compose up -d --no-build nginx
docker compose ps nginx
```

> **必须知道的失败方式**：nginx 容器启动时先跑 `nginx -t`，证书缺失、文件名与 `API_DOMAIN`
> 不符、私钥权限错、`.pem` 不是完整链，都会让它**立即退出**（`Exited (1)`）。
> 这时容器占用的 80 端口也一起消失，站点直接不可访问。
> 所以切回 `api` 之前先做上面两行自检；切回后立刻 `docker compose ps nginx` 看是否 `Up`，
> 失败就 `docker compose logs --tail=50 nginx`，并把 `NGINX_CONF` 改回 `api.http` 重新 `up -d nginx` 先保 80 可用。

---

## 6. 停机窗口：停旧栈 → 起新栈（仅第一次迁移）

### 6.0 停机前硬门槛（三项，缺一不可）

在旧栈仍然运行时执行，**全部通过才允许进入 6.1**：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

# 1) 仓库在
test -f docker-compose.yml && echo 'gate1 repo ok' || echo 'gate1 FAIL: 仓库没克隆，回第 2 节'

# 2) .env 在且必填项非空
test -f .env && echo 'gate2 env exists' || echo 'gate2 FAIL: 没有 .env'
grep -nE '^(AUTH_SECRET|MFG_ADMIN_PASS|QW_KEY|DS_KEY|DASHSCOPE_API_KEY|APS_DB_PASSWORD|APS_SECURITY_JWT_SECRET|HOGO_AUTH_PASSWORD)=$' .env \
  && echo 'gate2 FAIL: 上面这些键还是空的'

# 2b) REGISTRY 必须全小写：大写会在 pull 时被 Docker 在鉴权前拒绝
REGISTRY=$(grep -E '^REGISTRY=' .env | cut -d= -f2)
[ "$REGISTRY" = "$(printf '%s' "$REGISTRY" | tr '[:upper:]' '[:lower:]')" ] \
  && echo 'gate2 REGISTRY 小写 ok' \
  || echo 'gate2 FAIL: REGISTRY 含大写 → 先转小写，否则 pull 报 invalid reference format'

# 3) 三个镜像都在（registry 用 manifest；本地构建用 docker images）
REGISTRY=$(grep -E '^REGISTRY=' .env | cut -d= -f2)
TAG=$(grep -E '^IMAGE_TAG=' .env | cut -d= -f2)

if [ "$REGISTRY" = "local" ]; then
  # 本地构建：没有 registry，也没有 sha tag；只确认镜像存在
  docker images --format '{{.Repository}}:{{.Tag}}' \
    | grep -E '^local-(mfg-api|aps-backend|hogo-qa):' \
    && echo 'gate3 本地镜像 ok' \
    || echo 'gate3 FAIL: 本地镜像缺失，按 3.B 构建'
else
  for svc in mfg-api aps-backend hogo-qa; do
    docker manifest inspect "$REGISTRY-$svc:$TAG" >/dev/null && echo "gate3 ok  $svc:$TAG" || echo "gate3 FAIL: $svc:$TAG 不存在"
  done

  # 3b) tag 来源自检：这个 sha 是不是「真正触发构建」的提交
  TAG_SHA=${TAG#sha-}
  if TAG_FULL=$(git -C /opt/hulkai/FactroyAi rev-parse --verify --quiet "${TAG_SHA}^{commit}"); then
    git -C /opt/hulkai/FactroyAi show -s --format='tag 对应提交：%h %s (%ci)' "$TAG_FULL"
    if git -C /opt/hulkai/FactroyAi diff-tree -r --no-commit-id --name-only -m "$TAG_FULL" \
         | grep -qE '^(projects/|\.github/workflows/build-images\.yml)'; then
      echo 'tag 来源覆盖 paths：该提交会触发构建'
    else
      echo 'tag 来源不覆盖 paths：该提交没动 projects/** 或 workflow，不会产出镜像'
      echo '→ 用 HEAD 的 sha 会 MISSING，这不是镜像问题；按第 3 节反查触发构建的提交'
    fi
  else
    echo "本地历史里找不到 $TAG，检查 IMAGE_TAG 是否写错或来自别的仓库"
  fi
fi
```

> 三个全 MISSING 时先看 3b 的结论：**如果是「不覆盖 paths」，换 tag 而不是去查 GHCR**。
> 可用的 tag 一定来自一次成功的 `build-images` 运行，见第 3 节反查命令。

再补两个"配件"检查（同样不停机）：

```bash
docker compose config --quiet && echo 'compose 配置有效'
ls -l certs/api.hulkai.cn.pem certs/api.hulkai.cn.key 2>/dev/null || echo '无证书 → 本次必须走 NGINX_CONF=api.http'
```

任一项 FAIL：**回退到准备阶段，旧栈保持运行**。此时不用慌，站点还在。

### 6.1 停旧栈（顺序不能变）

```bash
# 1) 先停入口（系统 nginx 占 80，且只有 listen 80 一个 server 块）
sudo systemctl disable --now nginx
sudo ss -lntp | grep -E ':80 |:443 ' || echo '80/443 已释放'

# 2) 先停应用，再停缓存，最后停数据库（保证 PG 干净关闭）
docker stop mfg_app hulkai_aps_backend hulkai_hogo_qa
docker stop mfg_redis
docker stop hulkai_aps_postgres
docker ps --format '{{.Names}} {{.Status}}'

# 3) 让出容器名（改名而不是删除，回滚时可直接 start 回来）
for c in mfg_app mfg_redis hulkai_aps_postgres hulkai_aps_backend hulkai_hogo_qa; do
  docker rename "$c" "$c-pre-switch"
done
docker ps -a --format '{{.Names}}' | grep -E '^(mfg_app|mfg_redis|hulkai_aps_postgres|hulkai_aps_backend|hulkai_hogo_qa)$' \
  && echo '名字还没腾空' || echo '容器名已腾空'
```

> 旧容器名与新 compose 的 `container_name` 完全相同（`mfg_app` / `mfg_redis` /
> `hulkai_aps_postgres` / `hulkai_aps_backend` / `hulkai_hogo_qa`），不改名 `up` 会报
> `name already in use`。新 nginx 是 `hulkai_gz_nginx`，不冲突。
>
> 全程**只 `rename`，不 `rm`，不加 `-v`**。此时数据卷未动。

### 6.2 起新栈

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose up -d --no-build
docker compose ps
```

首次启动较慢，属正常：

- `app`：构建 2594 条向量索引，约 1–3 分钟；
- `aps-backend`：Flyway 建 39 张表，约 1–2 分钟；
- `hogo-qa`：秒级。

### 6.3 切换后 60 秒内检查

```bash
docker compose ps                                  # 三个服务都应 healthy
curl -fsS http://127.0.0.1:8004/api/v2/health
curl -fsS http://127.0.0.1:8085/api/v1/health
docker compose exec -T nginx wget -qO- http://hogo-qa:8788/api/health
curl -fsS -H 'Host: api.hulkai.cn' http://127.0.0.1/healthz
```

任一不通过，**不要开始排查**，直接执行 6.4 回滚；恢复访问后再慢慢查日志。

### 6.4 回滚（切换失败立即执行）

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

---

## 7. 首次启动（服务器上本来没有旧栈）

如果这台机器从来没有跑过旧栈（空机首装），跳过第 6 节，直接：

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose up -d --no-build        # 本地 build 场景去掉 --no-build
docker compose ps
```

日志与排障同 6.2。

### 7.1 导入 APS 演示数据（首次一次，可选）

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

- `tables` 不到 39 或报 `relation "orders" does not exist`：后端还没迁移完，先看 6.2 / 7 的日志；
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

---

## 8. 验证

### 8.1 容器与健康检查

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose ps                     # 全部 Up / healthy
docker compose images                 # 确认镜像 tag 就是 .env 里的 IMAGE_TAG

curl -fsS http://127.0.0.1:8004/api/v2/health
curl -fsS http://127.0.0.1:8085/api/v1/health
docker compose exec nginx wget -qO- http://hogo-qa:8788/api/health

# APS：39 张表 + 演示数据是否在位（7.1 导入后应为 tables=39、demo_orders=9）
docker compose exec -T aps-postgres psql -U aps -d aps -Atc "
select 'tables='||count(*) from information_schema.tables where table_schema='public';
select 'demo_orders='||count(*) from orders where id::text like 'd0000000-0000-0000-0000-0000000007%';"
```

### 8.2 `api.hulkai.cn`

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

### 8.3 静态站（经 CDN → COS）

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

## 9. 常见失败对照

| 现象 | 常见原因 | 处理 |
| --- | --- | --- |
| `cd: /opt/hulkai/FactroyAi: No such file or directory` | 仓库从未克隆 | 回第 2 节；此时旧栈若已停，先按 6.4 起回旧栈再准备 |
| `no configuration file provided: not found` | 当前目录不是 `deploy/gz-api`，或仓库缺失 | `pwd` 确认路径；仓库缺失见第 2 节 |
| `name already in use` / 容器名冲突 | 旧容器没停或没改名 | 按 6.1 顺序 stop + rename |
| `port is already allocated` / nginx 起不来 | 系统 nginx 仍占 80 | `sudo systemctl disable --now nginx` |
| nginx `Exited (1)`，日志 `cannot load certificate` | 证书缺失 / 文件名与 `API_DOMAIN` 不符 / 权限不对 / 链不全 | 见 5.2：先改回 `NGINX_CONF=api.http` 保住 80，再修证书 |
| 外部访问 443 超时，本机 curl 正常 | 安全组/防火墙未放行 443 | 控制台安全组放行 TCP 22/80/443；`sudo ufw status` |
| `aps-backend` 反复重启，日志 `password authentication failed` | 复用旧卷但 `APS_DB_PASSWORD` 与卷初始化时不一致 | 用 4 节的 `docker inspect ...-pre-switch` 读出旧值并回填 |
| `aps-backend` 报 `database "aps" does not exist` | 复用旧卷但 `APS_DB_NAME` 不一致 | 同上，对齐 `POSTGRES_DB` |
| 容器反复重启，日志提示缺变量 | `.env` 缺键或仍是空值 | 用第 4 节的 grep 检查；`docker compose config --quiet` |
| `invalid reference format: repository name (...) must be lowercase` | `.env` 的 `REGISTRY` 带了大写 owner（如 `Hulk-Zhao`）；Docker 在**鉴权前**就拒绝，与权限无关 | 把 `REGISTRY` 全转小写；`docker login -u` 的用户名可保留大写 |
| `pull access denied` / `unauthorized` / 401 | 未登录 GHCR，或 token 无 `read:packages`；**401 不能用来判断镜像是否存在**（匿名请求一律 401） | 先 `docker login ghcr.io`，再 `docker manifest inspect`；`not found` / `manifest unknown` 才是真不存在 |
| `Retrying in N seconds`，某个层卡在几百 KB 不动 | 到 registry 的网络层不可达（GHCR 到国内常见），不是镜像不存在，也不是鉴权问题 | 按 4.1 切腾讯云 TCR；重试不会变好 |
| 三个镜像 `manifest inspect` 全 MISSING | `IMAGE_TAG` 用了 HEAD 的 sha，而该提交只改文档、没触发构建 | 按第 3 节反查「最近一次触发构建的提交」重设 tag；不是镜像没发布 |
| gate3 提示「tag 来源不覆盖 paths」 | 同上，tag 指向的提交不在 `projects/**` / workflow 范围 | 换 tag，不要改 Dockerfile 或重发镜像 |
| `api.hulkai.cn` 打不开 / 解析到旧 IP | DNS 未生效或仍指向香港 | `dig +short api.hulkai.cn`；用 `--resolve` 先确认服务正常，再等 TTL |
| `https://hulkai.cn/` 404 或 403 | 桶内没有对象 / 回源 HOST 填错 | 本地先 `publish.ps1` 上传；CDN 回源 HOST 用桶域名 |
| 页面能开但接口全红 | 跨域或 API 域名错 | 8.2 的 OPTIONS 检查；确认前端构建时 `API_BASE` 正确 |
| 构建阶段 `Killed`（本地 build） | 内存不足 | 先加 2G swap，并 `COMPOSE_PARALLEL_LIMIT=1` |

---

## 10. 发版与回滚

```bash
cd /opt/hulkai/FactroyAi
git pull --ff-only
cd deploy/gz-api

# 取 CI Summary 里的新 tag（对应触发构建的提交；pull 后的 HEAD 可能只是文档提交）
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

> 镜像 tag 与**触发构建的提交**一一对应（不是 pull 后的 HEAD），三个服务共用同一个 tag，整体回滚即可。
> 只改文档的提交不会产出 tag，`docker compose pull` 会报 tag 不存在。
>
> **数据不要动**：业务数据在命名卷 `mfg_data` / `hulkai_aps_pgdata` / `hulkai_hogo_runtime`，
> 回滚镜像不涉及它们。**禁止 `docker compose down -v`**（会删卷）。
> 迁移旧库、备份恢复、部署后 30 秒核对见 [data-persistence.md](data-persistence.md)；
> 日常备份在服务器执行 `bash deploy/gz-api/backup.sh`。

---

## 11. 静态走 COS 后不再需要的项

以下内容**只在灾备**（CDN 临时改回自有源）时才用，当前无需准备：

- `/opt/hulkai/site` 目录：不需要创建。`SERVE_STATIC_ORIGIN=0` 时 compose 不会读取它。
- CDN 回源 IP 段：不需要收集。`deploy/gz-api/nginx/realip.conf` **保持空名单**即可，
  只保留 `real_ip_header` / `real_ip_recursive` 两行，不会生效也不影响启动。
- `certs/hulkai.cn.pem|key`：不需要。静态源站关闭时不会被加载。
- 安全组只需放行 `22/80/443`，不需要为 CDN 回源额外放行地址段（回源目标是 COS，不是这台机器）。

需要灾备时，按 `deploy/README.md` 第 7.8 节操作。
