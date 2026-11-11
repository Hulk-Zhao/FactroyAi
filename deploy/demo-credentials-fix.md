# 演示站登录口令修复（OrcaTerm 可整段粘贴）

> 现场：广州服务器 `129.204.108.45`，仓库 `/opt/hulkai/FactroyAi`，compose 目录
> `/opt/hulkai/FactroyAi/deploy/gz-api`。
> 目标口径：**hogo `123`、APS `admin / ApsDemo2026`、mfg `admin / 123`**，与页面提示一致。
>
> **绝对禁止** `docker compose down -v` / `docker volume rm` / `docker system prune --volumes`：
> `mfg_data`、`hulkai_aps_pgdata`、`hulkai_hogo_runtime` 三个命名卷保存全部业务数据，
> `-v` 会直接删除，只能从 `/opt/hulkai/backup/20261111-1951` 恢复。

---

## 0. 三个口令的真实生效方式（已按代码与线上实测确认）

| 站点 | 校验位置 | 值从哪来 | 改完是否需重建容器 |
| --- | --- | --- | --- |
| mfg `demo/mfg` | `projects/mfg-agent/api/src/mfg_agents/v2/demo/api.py:786-801` | **只读环境变量** `MFG_ADMIN_USER` / `MFG_ADMIN_PASS`，`secrets.compare_digest` 直接比对 | 是（容器创建时注入） |
| hogo `demo/hogo-qa` | `projects/hogo-qa/backend/lib/auth.mjs:125-146` | 环境变量 `AUTH_PASSWORD`（由 `.env` 的 `HOGO_AUTH_PASSWORD` 注入，启动读入） | 是 |
| APS `demo/mfg-agent-platform` | `AuthService.java`，`BCryptPasswordEncoder(10)` | PostgreSQL `users.password_hash`（`username='admin'`） | **否**，改库即时生效 |

### mfg：没有 SQLite 用户表，也不需要重置数据库

- 全仓库检索确认：mfg 后端没有 `users` 表、没有 `password_hash` 字段，也不存在启动时
  upsert 管理员；唯一口令来源是 `os.getenv("MFG_ADMIN_PASS", "123")`，每次登录请求实时读取。
- 因此「改 `.env` 不生效」的真实原因是：**环境变量在容器创建时固化**，正在运行的
  `mfg_app` 仍是旧值，必须 `--force-recreate app`；数据库不需要动。
- 登录还必须带**岗位**字段：`roles: ["quality"]`（或 `role: "quality"`）。缺岗位时即使口令正确，
  也会返回 `400 岗位未识别`，不要误判为口令错误。

### APS：`APS_ADMIN_INIT_PASSWORD` 只在 dev profile 首启、且哈希为空时写一次

- Flyway `V1__init.sql:945-956` 插入 admin 时 `password_hash = NULL`；
  `AdminPasswordBootstrap` 仅在 `dev` profile + 初始化口令非空 + 哈希为空三条件同时成立时写入。
- 线上哈希已存在，之后**只能直接改库**。`V1__init.sql:19` 已装 `pgcrypto`，用
  `crypt()/gen_salt('bf',10)` 生成 `$2a$10$...`，与 Spring 的 BCrypt 完全兼容，无需额外装包。
- 现场实测：库内 `$2a$10$`、长度 60、`crypt('ApsDemo2026', password_hash)=password_hash` 为 `t`，
  登录接口返回 200。

---

## 1. 一键修复块（整段粘贴，幂等，失败即停）

```bash
set -Eeuo pipefail

cd /opt/hulkai/FactroyAi/deploy/gz-api
DC="sudo -n docker compose"
say() { printf '\n== %s\n' "$*"; }

# 0) 明确禁止破坏数据卷：本脚本只做 recreate，不做 down -v
say "0. 前置检查"
test -f docker-compose.yml || { echo "FAIL: 不在 compose 目录"; exit 1; }
test -f .env              || { echo "FAIL: 缺 .env"; exit 1; }
sudo -n docker volume ls --format '{{.Name}}' | grep -qx 'hulkai_aps_pgdata' \
  || { echo "FAIL: 数据卷不存在，停手排查"; exit 1; }

# 1) 备份 .env（含密钥，权限 600，不回显内容）
say "1. 备份 .env"
umask 077
BAK=".env.bak.$(date +%Y%m%d-%H%M%S)"
cp -p .env "$BAK"
ls -l "$BAK"

# 2) 写入三个演示口令（键不存在则追加；重复执行结果一致）
say "2. 写入演示口令"
set_env() {
  local key="$1" val="$2"
  if grep -qE "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${val}|" .env
  else
    printf '%s=%s\n' "$key" "$val" >> .env
  fi
}
set_env MFG_ADMIN_USER admin
set_env MFG_ADMIN_PASS 123
set_env HOGO_AUTH_PASSWORD 123
set_env APS_ADMIN_INIT_PASSWORD ApsDemo2026

# 校验：只打印长度，不打印值
grep -qE '^MFG_ADMIN_USER=admin$' .env || { echo "FAIL: MFG_ADMIN_USER"; exit 1; }
[ "$(grep -E '^MFG_ADMIN_PASS=' .env | cut -d= -f2-)" = "123" ] || { echo "FAIL: MFG_ADMIN_PASS"; exit 1; }
[ "$(grep -E '^HOGO_AUTH_PASSWORD=' .env | cut -d= -f2-)" = "123" ] || { echo "FAIL: HOGO_AUTH_PASSWORD"; exit 1; }
[ "$(grep -E '^APS_ADMIN_INIT_PASSWORD=' .env | cut -d= -f2-)" = "ApsDemo2026" ] || { echo "FAIL: APS_ADMIN_INIT_PASSWORD"; exit 1; }
echo "env ok"

# 3) 重建 app（口令在容器创建时注入，必须 recreate 才生效）
say "3. 重建 mfg app"
$DC up -d --no-build --force-recreate app
for i in $(seq 1 30); do
  st=$(sudo -n docker inspect -f '{{.State.Health.Status}}' mfg_app 2>/dev/null || echo starting)
  [ "$st" = "healthy" ] && break
  sleep 10
done
[ "$st" = "healthy" ] || { echo "FAIL: mfg_app 未 healthy（$st）"; sudo -n docker logs --tail=50 mfg_app; exit 1; }
[ "$(sudo -n docker exec mfg_app sh -c 'printf %s "$MFG_ADMIN_PASS"')" = "123" ] \
  || { echo "FAIL: 容器内 MFG_ADMIN_PASS 不是 123"; exit 1; }
echo "mfg app ok"

# 4) 重建 hogo
say "4. 重建 hogo"
$DC up -d --no-build --force-recreate hogo-qa
sleep 3
[ "$(sudo -n docker inspect -f '{{.State.Running}}' hulkai_hogo_qa)" = "true" ] \
  || { echo "FAIL: hogo 未运行"; sudo -n docker logs --tail=50 hulkai_hogo_qa; exit 1; }
[ "$(sudo -n docker exec hulkai_hogo_qa sh -c 'printf %s "$AUTH_PASSWORD"')" = "123" ] \
  || { echo "FAIL: 容器内 AUTH_PASSWORD 不是 123"; exit 1; }
echo "hogo ok"

# 5) APS：重置 admin 哈希为 ApsDemo2026（pgcrypto 已在 V1 安装）
say "5. 重置 APS admin 口令"
umask 077
APS_HASH_BAK="$HOME/aps-admin-password-hash.bak"
sudo -n docker exec -T aps-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' \
  > "$APS_HASH_BAK" <<'SQL'
SELECT password_hash FROM users WHERE username = 'admin' AND deleted = false;
SQL
test -s "$APS_HASH_BAK" || { echo "FAIL: 未取到旧哈希"; exit 1; }

sudo -n docker exec -T aps-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE users
   SET password_hash = crypt('ApsDemo2026', gen_salt('bf', 10)),
       locked_until  = NULL,
       updated_at    = now()
 WHERE username = 'admin'
   AND deleted = false;
SELECT crypt('ApsDemo2026', password_hash) = password_hash AS verify_ok
  FROM users WHERE username = 'admin' AND deleted = false;
SQL

APS_OK=$(sudo -n docker exec -T aps-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' <<'SQL'
SELECT crypt('ApsDemo2026', password_hash) = password_hash
  FROM users WHERE username = 'admin' AND deleted = false;
SQL
)
APS_OK=$(printf '%s' "$APS_OK" | tr -d '[:space:]')
[ "$APS_OK" = "t" ] || { echo "FAIL: APS 哈希自检未通过"; exit 1; }
echo "aps ok"

say "修复完成，下一步跑第 2 节登录验证"
```

失败时脚本在第一处 `exit 1` 停止，前面的步骤都是幂等的，排查后整段重跑即可。

### 回滚

| 变更 | 回滚方式 |
| --- | --- |
| `.env` | `cp -p .env.bak.<时间戳> .env`，再重建 `app` 与 `hogo-qa` |
| APS 哈希 | 用 `$HOME/aps-admin-password-hash.bak` 里的旧哈希写回（`:'hash'` 安全引用，`$` 不会被 shell 展开）： |

```bash
H=$(cat "$HOME/aps-admin-password-hash.bak")
sudo -n docker exec -T aps-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v hash="$0"' \
  "$H" <<'SQL'
UPDATE users SET password_hash = :'hash', updated_at = now()
 WHERE username = 'admin' AND deleted = false;
SQL
```

容器可用 `docker start/stop`；**不要** `down -v`。

---

## 2. 登录接口验证（curl，三个都应为 200）

mfg 与 hogo 的登录需要一次性图形验证码。两者签发的 SVG 里字符以 `<text>` 文本节点存在，
下面的脚本直接从 SVG 提取答案，因此无需人工识图；验证码一次性、10 分钟过期，
所以「取图 → 提交」必须在同一次执行内完成。

```bash
set -Eeuo pipefail
BASE=https://api.hulkai.cn

say() { printf '\n== %s\n' "$*"; }

say "1) mfg admin/123"
mfg_json=$(curl -fsS "$BASE/api/v2/auth/captcha")
mfg_id=$(printf '%s' "$mfg_json" | sed -n 's/.*"captcha_id":"\([^"]*\)".*/\1/p')
mfg_ans=$(printf '%s' "$mfg_json" | grep -oE '>[23456789A-HJ-NP-Z]<' | tr -d '><' | tr -d '\n')
[ "${#mfg_ans}" -eq 4 ] || { echo "FAIL: 验证码解析失败"; exit 1; }
mfg_code=$(curl -s -o /tmp/mfg_login.json -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"123\",\"captcha_id\":\"$mfg_id\",\"captcha_code\":\"$mfg_ans\",\"roles\":[\"quality\"]}" \
  "$BASE/api/v2/auth/login")
[ "$mfg_code" = "200" ] || { echo "FAIL: mfg=$mfg_code"; cat /tmp/mfg_login.json; exit 1; }
echo "PASS mfg 200"

say "2) hogo 123"
hogo_json=$(curl -fsS "$BASE/hogo/api/auth/captcha")
hogo_id=$(printf '%s' "$hogo_json" | sed -n 's/.*"captchaId":"\([^"]*\)".*/\1/p')
hogo_ans=$(printf '%s' "$hogo_json" | grep -oE '>[23456789A-HJ-NP-Z]<' | tr -d '><' | tr -d '\n')
[ "${#hogo_ans}" -eq 4 ] || { echo "FAIL: 验证码解析失败"; exit 1; }
hogo_code=$(curl -s -o /tmp/hogo_login.json -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "{\"password\":\"123\",\"captchaId\":\"$hogo_id\",\"captchaCode\":\"$hogo_ans\"}" \
  "$BASE/hogo/api/auth/login")
[ "$hogo_code" = "200" ] || { echo "FAIL: hogo=$hogo_code"; cat /tmp/hogo_login.json; exit 1; }
echo "PASS hogo 200"

say "3) APS admin/ApsDemo2026"
aps_code=$(curl -s -o /tmp/aps_login.json -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ApsDemo2026"}' \
  "$BASE/aps/api/v1/auth/login")
[ "$aps_code" = "200" ] || { echo "FAIL: aps=$aps_code"; cat /tmp/aps_login.json; exit 1; }
echo "PASS aps 200"

echo
echo "三个登录接口全部 200"
```

补充检查（可选）：

```bash
curl -fsS https://api.hulkai.cn/api/v2/health; echo
curl -fsS https://api.hulkai.cn/hogo/api/health; echo
curl -fsS https://api.hulkai.cn/aps/api/v1/health; echo
```

浏览器入口与账号：

| 站点 | 地址 | 账号 / 口令 |
| --- | --- | --- |
| MFG | `https://hulkai.cn/demo/mfg/` | `admin` / `123`（岗位任选） |
| Hogo | `https://hulkai.cn/demo/hogo-qa/` | `123` |
| APS | `https://hulkai.cn/demo/mfg-agent-platform/` | `admin` / `ApsDemo2026` |

---

## 3. 现场记录与注意事项

- 2026-11-12 已在服务器执行：`.env` 三项改为 `123 / 123 / ApsDemo2026`，重建
  `mfg_app`、`hulkai_hogo_qa`；APS 库内 admin 哈希重置为 `ApsDemo2026` 的 BCrypt。
  实测三个登录接口均 200。
- 本文只写演示口令，**不包含也不应写入** `AUTH_SECRET`、`APS_SECURITY_JWT_SECRET`、
  `HOGO_AUTH_SECRET`、数据库口令等真实密钥。
- 重置口令不会让已签发的令牌立即失效：mfg 依赖 `AUTH_SECRET`，APS 依赖 JWT 密钥，
  hogo 依赖 `HOGO_AUTH_SECRET`。需要强制全员重新登录时，才轮换对应密钥并重建容器。
- 失败的典型对照：
  - mfg 返回 `400 岗位未识别` → 登录缺 `roles`，不是口令错。
  - APS 返回 `401` → 哈希未更新或账号被 `locked_until` 锁定（脚本已清空）。
  - hogo 返回 `401 验证码错误` → 验证码被消费/过期，重新取图即可。
