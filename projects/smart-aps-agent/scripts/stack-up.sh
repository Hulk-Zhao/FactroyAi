#!/usr/bin/env bash
# 一键启动 APS 整栈（PostgreSQL 15 / Redis 7 / MinIO / aps-backend / aps-frontend）
# 等价 PowerShell 脚本：scripts/stack-up.ps1
# M2-QA-03 起部署链路不再编译业务代码：后端挂载已构建的 fat jar，前端挂载 dist 静态产物。
#
# 分阶段计时：[1]镜像拉取 [2]镜像构建 [3]容器启动 [4]Flyway 完成 [5]健康检查 200 [6]前端启动
#
# 用法：
#   ./scripts/stack-up.sh                 # 拉镜像 + 构建 + 启动 + 等待 + 计时
#   ./scripts/stack-up.sh --skip-build    # 使用已有镜像，不重新构建
#   ./scripts/stack-up.sh --skip-pull     # 跳过镜像拉取（离线/已缓存）
#   ./scripts/stack-up.sh --no-wait       # 只 up -d，不等待健康检查，也不建桶
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
TOTAL_START=$(date +%s.%N)

SKIP_BUILD=0
SKIP_PULL=0
NO_WAIT=0

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --skip-pull) SKIP_PULL=1 ;;
        --no-wait) NO_WAIT=1 ;;
        -h|--help)
            sed -n '2,12p' "$0"
            exit 0
            ;;
        *)
            echo "[ERROR] 未知参数: $arg（支持 --skip-build / --skip-pull / --no-wait）" >&2
            exit 2
            ;;
    esac
done

STEP_NAMES=()
STEP_SECONDS=()
STEP_DETAILS=()

step() { printf '\033[36m[..] %s\033[0m\n' "$*"; }
warn() { printf '\033[33m[WARN] %s\033[0m\n' "$*"; }
detail() { printf '    %s\n' "$*"; }
fail() {
    printf '\033[31m[ERROR] %s\033[0m\n' "$*" >&2
    shift || true
    for hint in "$@"; do printf '  - %s\n' "$hint" >&2; done
    exit 1
}

stage_start() { STAGE_START=$(date +%s.%N); }
record_stage() {
    local name="$1" detail_text="${2:-}"
    local end elapsed
    end=$(date +%s.%N)
    elapsed=$(awk -v s="$STAGE_START" -v e="$end" 'BEGIN{printf "%.1f", e-s}')
    STEP_NAMES+=("$name")
    STEP_SECONDS+=("$elapsed")
    STEP_DETAILS+=("$detail_text")
}

env_value() {
    local key="$1" default="$2" value=""
    if [[ -f .env ]]; then
        value="$(grep -E "^[[:space:]]*${key}=" .env | tail -n1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//' || true)"
    fi
    printf '%s' "${value:-$default}"
}

env_has() {
    local key="$1" file_value=""
    [[ -f .env ]] && file_value="$(env_value "$key" '')"
    [[ -n "$file_value" || -n "${!key:-}" ]]
}

port_conflict() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}\$"; then
            echo "端口已被占用"
            return 0
        fi
    elif command -v lsof >/dev/null 2>&1; then
        if lsof -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
            echo "端口已被占用"
            return 0
        fi
    else
        if (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
            exec 3>&-
            echo "端口已被占用"
            return 0
        fi
    fi
    return 1
}

container_state() {
    docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null | tr -d '\r'
}

wait_healthy() {
    local name="$1" timeout="${2:-120}" state
    local deadline=$((SECONDS + timeout))
    while (( SECONDS < deadline )); do
        state="$(container_state "$name")" || true
        case "$state" in
            healthy|running) printf '%s' "$state"; return 0 ;;
            unhealthy) printf '%s' "$state"; return 1 ;;
        esac
        sleep 1
    done
    printf 'timeout'
    return 1
}

flyway_logs() {
    docker "${COMPOSE[@]}" logs --no-color --tail 500 aps-backend 2>&1 | tr -d '\r' || true
}

wait_flyway() {
    local timeout="${1:-240}"
    local deadline=$((SECONDS + timeout)) logs
    while (( SECONDS < deadline )); do
        logs="$(flyway_logs)"
        if grep -qE 'Successfully applied|is up to date\. No migration necessary|now at version' <<<"$logs"; then
            printf '%s\n' "$(grep -E 'Successfully applied|is up to date\. No migration necessary|now at version' <<<"$logs" | tail -n1)"
            return 0
        fi
        if grep -qE 'FlywayException|Migration .* failed|APPLICATION FAILED TO START' <<<"$logs"; then
            printf '%s\n' "$logs" >&2
            return 1
        fi
        sleep 2
    done
    printf '%s\n' "$(flyway_logs)" >&2
    return 1
}

wait_health() {
    local port="$1" timeout="${2:-240}"
    local deadline=$((SECONDS + timeout)) code
    while (( SECONDS < deadline )); do
        code="$(curl -fsS -o /tmp/aps-health-body -w '%{http_code}' "http://127.0.0.1:${port}/api/v1/health" 2>/dev/null || true)"
        if [[ "$code" == "200" ]]; then
            printf '%s' "$(tr -d '\r\n' </tmp/aps-health-body)"
            return 0
        fi
        sleep 2
    done
    return 1
}

step '检查 Docker CLI'
command -v docker >/dev/null 2>&1 || fail '未找到 docker 命令' \
    '安装 Docker Desktop（含 Compose v2）：https://docs.docker.com/desktop/install/' \
    '安装完成后重新打开终端再试'

step '检查 Docker 守护进程'
docker info >/dev/null 2>&1 || fail 'Docker 守护进程未运行（docker info 失败）' \
    '启动 Docker Desktop / systemctl start docker，等待就绪后重试' \
    '确认当前用户有访问 docker socket 的权限（Linux: 加入 docker 组）'

step '检查 Docker Compose v2'
docker compose version >/dev/null 2>&1 || fail '未检测到 docker compose v2 插件' \
    'Docker Desktop 自带 compose v2；Linux 安装 docker-compose-plugin' \
    '不要使用旧版 docker-compose（v1）执行本脚本'

step '准备 .env'
ENV_COPIED=0
if [[ ! -f .env ]]; then
    [[ -f .env.example ]] || fail '缺少 .env 与 .env.example，无法启动' '请从版本库恢复 .env.example 后重试'
    cp .env.example .env
    ENV_COPIED=1
    warn '未找到 .env，已从 .env.example 复制；上线前务必替换所有默认密码、APS_SECURITY_MODE 与 APS_SECURITY_JWT_SECRET'
fi

# ---- 必填变量校验：缺失/为空即失败，绝不静默取默认值 ----
step '校验 .env 必填变量'
REQUIRED_KEYS=(POSTGRES_PASSWORD REDIS_PASSWORD MINIO_ROOT_PASSWORD APS_SECURITY_MODE APS_SECURITY_JWT_SECRET)
MISSING=()
for key in "${REQUIRED_KEYS[@]}"; do
    env_has "$key" || MISSING+=("$key")
done
if (( ${#MISSING[@]} > 0 )); then
    fail "必填变量缺失或为空：${MISSING[*]}" \
        "请编辑 $(pwd)/.env，为上述变量填入非空值后重试" \
        '模板可从 .env.example 复制；部署环节不会替你填充默认密码' \
        '对应的 compose 变量声明带 :? 保护，缺失时 docker compose 也会直接报错'
fi
if [[ "$ENV_COPIED" -eq 1 ]]; then
    warn '当前 .env 使用模板中的开发默认密码，仅可用于本机验证；正式环境必须逐项替换'
fi
for key in "${REQUIRED_KEYS[@]}"; do detail "$key 已设置"; done

# ---- 制品前置检查：缺失/瘦包直接失败 ----
step '校验部署制品（jar / dist）'
JAR_PATH="$(env_value APS_BACKEND_JAR 'aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar')"
DIST_PATH="$(env_value APS_FRONTEND_DIST 'aps-frontend/dist')"
[[ -f "$JAR_PATH" ]] || fail "未找到后端制品：$JAR_PATH" \
    '构建制品由 W9 统一交付，部署环节不执行 mvn package' \
    '请先取回 aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar，或在 .env 用 APS_BACKEND_JAR 指向已有 jar'
JAR_SIZE=$(wc -c <"$JAR_PATH" | tr -d ' ')
if (( JAR_SIZE < 52428800 )); then
    fail "后端制品疑似瘦包：${JAR_SIZE} B（<50MB）" \
        '正常 fat jar 约 181MB 且 BOOT-INF/lib 内有 100+ 个依赖' \
        '请重新获取 W9 交付的完整制品，不要启动瘦包'
fi
if command -v sha256sum >/dev/null 2>&1; then
    JAR_HASH=$(sha256sum "$JAR_PATH" | awk '{print $1}' | tr 'a-f' 'A-F')
else
    JAR_HASH="$(shasum -a 256 "$JAR_PATH" | awk '{print $1}' | tr 'a-f' 'A-F')"
fi
LIB_COUNT=$(unzip -l "$JAR_PATH" 2>/dev/null | grep -cE 'BOOT-INF/lib/.*\.jar$' || true)
detail "jar  $JAR_PATH"
detail "     ${JAR_SIZE} B  SHA256=${JAR_HASH}  BOOT-INF/lib=${LIB_COUNT:-n/a}"
if [[ -n "${LIB_COUNT:-}" && "$LIB_COUNT" == "0" ]]; then
    fail '后端制品 BOOT-INF/lib 为空，判定为瘦包，已中止启动' '请从 W9 取回完整制品'
fi
[[ -f "$DIST_PATH/index.html" ]] || fail "未找到前端静态产物：$DIST_PATH/index.html" \
    '请先在 aps-frontend 执行 npm run build（由前端负责人统一构建），或在 .env 用 APS_FRONTEND_DIST 指向已有 dist'
detail "dist $DIST_PATH"

# 显式 --project-name：根 .env 的 COMPOSE_PROJECT_NAME=aps-dev 会覆盖 compose 顶层 name
COMPOSE=(compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml)

FRONTEND_PORT="$(env_value APS_FRONTEND_PORT 80)"
BACKEND_PORT="$(env_value APS_BACKEND_PORT 8080)"

step '检查入口端口占用'
CONFLICTS=()
if owner="$(port_conflict "$FRONTEND_PORT")"; then
    CONFLICTS+=("前端入口 ${FRONTEND_PORT} -> ${owner}")
else
    detail "前端入口 ${FRONTEND_PORT} 可用"
fi
if owner="$(port_conflict "$BACKEND_PORT")"; then
    CONFLICTS+=("后端 API ${BACKEND_PORT} -> ${owner}")
else
    detail "后端 API ${BACKEND_PORT} 可用"
fi
if [[ "${#CONFLICTS[@]}" -gt 0 ]]; then
    fail '入口端口冲突，已中止启动（未改动任何运行中的容器）' \
        "${CONFLICTS[@]}" \
        '在 .env 中设置 APS_FRONTEND_PORT / APS_BACKEND_PORT 换用其他端口后重试' \
        '或停止占用该端口的程序（Linux 上 80 端口常被已有 Nginx/Apache 占用）'
fi

stage_start
if [[ "$SKIP_PULL" -eq 0 ]]; then
    step '[1] 拉取镜像（中间件 + 前端运行镜像）'
    docker "${COMPOSE[@]}" pull postgres redis minio aps-frontend || fail '镜像拉取失败' \
        '国内网络为 Docker Desktop 配置 clash 代理：Settings -> Resources -> Proxies -> Manual，HTTP/HTTPS 都填 http://127.0.0.1:7890' \
        'MinIO 使用社区维护分支镜像；若内网 Harbor 有镜像，在 .env 设置 MINIO_IMAGE' \
        '离线部署可使用 scripts/stack-bundle.sh 打包好的镜像 tar（docker load 后加 --skip-pull）'
    record_stage '[1] 镜像拉取' 'postgres/redis/minio/aps-frontend'
else
    step '[1] 跳过镜像拉取（--skip-pull）'
    record_stage '[1] 镜像拉取' '已跳过'
fi

stage_start
if [[ "$SKIP_BUILD" -eq 0 ]]; then
    step '[2] 构建后端运行时镜像（不编译业务代码）'
    docker "${COMPOSE[@]}" build aps-backend || fail '后端运行时镜像构建失败' \
        '该镜像仅安装 JRE 21 + curl，不含业务代码；失败多为网络/apt 源问题' \
        '检查 Docker Desktop 代理设置后重试'
    record_stage '[2] 镜像构建' 'aps-backend 运行时镜像'
else
    step '[2] 跳过镜像构建（--skip-build）'
    record_stage '[2] 镜像构建' '已跳过'
fi

stage_start
step '[3] 启动中间件与后端容器'
docker "${COMPOSE[@]}" up -d postgres redis minio || fail '中间件容器启动失败' \
    '查看状态：docker compose ... ps；日志：docker compose ... logs postgres redis minio'
for pair in aps-stack-postgres:postgres aps-stack-redis:redis aps-stack-minio:minio; do
    cname="${pair%%:*}" label="${pair##*:}"
    if ! state="$(wait_healthy "$cname" 120)"; then
        fail "${label} 未在 120s 内就绪（状态：${state}）" \
            "查看日志：docker compose ... logs ${label}" \
            '确认 .env 中 POSTGRES_PASSWORD / REDIS_PASSWORD / MINIO_ROOT_PASSWORD 非空且格式正确'
    fi
    detail "${label} -> ${state}"
done
docker "${COMPOSE[@]}" up -d aps-backend || fail '后端容器启动失败' \
    '查看日志：docker compose ... logs aps-backend' \
    '确认 APS_BACKEND_JAR 指向的 fat jar 存在且未损坏'
record_stage '[3] 容器启动' '中间件 healthy + 后端容器创建'

if [[ "$NO_WAIT" -eq 0 ]]; then
    stage_start
    step '[4] 等待 Flyway 迁移完成'
    FLYWAY_LINE="$(wait_flyway 240)" || fail 'Flyway 未在 240s 内完成或迁移失败' \
        '用 docker compose ... logs aps-backend 查看完整启动日志' \
        '确认数据库卷为空库且 db/migration 已随 fat jar 打入 BOOT-INF/classes/db/migration' \
        '已应用的 V1–V6 迁移文件不得修改，校验和不匹配时只能 flyway repair'
    detail "$FLYWAY_LINE"
    record_stage '[4] Flyway 完成' "$FLYWAY_LINE"

    stage_start
    step '[5] 等待 /api/v1/health 首次 200'
    HEALTH_BODY="$(wait_health "$BACKEND_PORT" 240)" || fail '健康检查未在 240s 内返回 200' \
        "手工验证：curl -fsS http://127.0.0.1:${BACKEND_PORT}/api/v1/health" \
        '查看后端日志：docker compose ... logs aps-backend'
    detail "HTTP 200 ${HEALTH_BODY}"
    record_stage '[5] 健康检查 200' 'GET /api/v1/health'

    stage_start
    step '[6] 启动前端容器'
    docker "${COMPOSE[@]}" up -d aps-frontend || fail '前端容器启动失败' '查看日志：docker compose ... logs aps-frontend'
    if ! state="$(wait_healthy aps-stack-frontend 60)"; then
        fail "前端容器未就绪（状态：${state}）" \
            '确认 aps-frontend/dist/index.html 存在且可读' \
            '查看日志：docker compose ... logs aps-frontend'
    fi
    record_stage '[6] 前端启动' 'aps-frontend healthy'

    step '校验容器内制品 SHA256'
    # Git Bash(MSYS) 会把容器内绝对路径 /app/app.jar 改写成 Windows 路径，需关闭路径转换
    CONTAINER_HASH="$(MSYS_NO_PATHCONV=1 docker "${COMPOSE[@]}" exec -T aps-backend sha256sum /app/app.jar 2>/dev/null | awk '{print $1}' | tr 'a-f' 'A-F')" || true
    if [[ -z "$CONTAINER_HASH" ]]; then
        warn '无法读取容器内 jar 的 SHA256（exec 失败），请手工核对'
    elif [[ "$CONTAINER_HASH" != "$JAR_HASH" ]]; then
        fail "制品不一致：容器内 $CONTAINER_HASH，仓库 $JAR_HASH" '容器可能挂载了旧包，请检查 APS_BACKEND_JAR'
    else
        detail "SHA256 一致：$JAR_HASH"
    fi

    step '初始化 MinIO 附件桶'
    if ! docker "${COMPOSE[@]}" --profile init run --rm --no-deps -T minio-init; then
        warn 'MinIO 附件桶初始化失败（不影响其他服务运行）'
        warn '可手动重试：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml --profile init run --rm minio-init'
    fi
else
    step '--no-wait：启动全部服务但不等待健康检查'
    docker "${COMPOSE[@]}" up -d || fail '整栈启动失败' '查看状态：docker compose ... ps'
fi

echo
TOTAL_SECONDS=$(awk -v s="$TOTAL_START" -v e="$(date +%s.%N)" 'BEGIN{printf "%.1f", e-s}')
printf '\033[32m分阶段耗时（实测）\033[0m\n'
for i in "${!STEP_NAMES[@]}"; do
    printf '    %-16s %6s s   %s\n' "${STEP_NAMES[$i]}" "${STEP_SECONDS[$i]}" "${STEP_DETAILS[$i]}"
done
printf '\033[32m    %-16s %6s s\033[0m\n' '总耗时' "$TOTAL_SECONDS"
echo

step 'docker compose ps'
docker "${COMPOSE[@]}" ps || true

echo
printf '\033[32mAPS 整栈已启动\033[0m\n'
echo "  前端入口   http://localhost:${FRONTEND_PORT}"
echo "  后端健康   http://localhost:${BACKEND_PORT}/api/v1/health"
echo '  中间件     postgres:5432 / redis:6379 / minio:9000（仅容器内网）'
if [[ "$NO_WAIT" -eq 1 ]]; then
    printf '\033[33m  当前未等待健康检查，也未初始化 MinIO 桶；请查看 ps 状态后再做验收。\033[0m\n'
fi
echo
echo '状态：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps'
echo '停止：./scripts/stack-down.sh            （保留数据卷）'
echo '清数据：./scripts/stack-down.sh --remove-volumes'
