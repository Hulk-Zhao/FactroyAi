#!/usr/bin/env bash
# 启动 APS 本地开发依赖栈（PostgreSQL 15 / Redis 7 / MinIO）
# 等价 PowerShell 脚本：scripts/dev-up.ps1
#
# 用法：
#   ./scripts/dev-up.sh                 # 仅内网，不发布宿主端口
#   ./scripts/dev-up.sh --expose-debug  # 依赖端口仅绑定 127.0.0.1，供 IDE 直连
#   ./scripts/dev-up.sh --skip-pull     # 跳过拉取（离线/已缓存镜像）
#   ./scripts/dev-up.sh --no-wait       # 只 up -d，不等待健康检查
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

EXPOSE_DEBUG=0
SKIP_PULL=0
NO_WAIT=0

for arg in "$@"; do
    case "$arg" in
        --expose-debug) EXPOSE_DEBUG=1 ;;
        --skip-pull) SKIP_PULL=1 ;;
        --no-wait) NO_WAIT=1 ;;
        -h|--help)
            sed -n '2,12p' "$0"
            exit 0
            ;;
        *)
            echo "[ERROR] 未知参数: $arg（支持 --expose-debug / --skip-pull / --no-wait）" >&2
            exit 2
            ;;
    esac
done

step() { printf '\033[36m[..] %s\033[0m\n' "$*"; }
warn() { printf '\033[33m[WARN] %s\033[0m\n' "$*"; }
fail() {
    printf '\033[31m[ERROR] %s\033[0m\n' "$*" >&2
    shift || true
    for hint in "$@"; do printf '  - %s\n' "$hint" >&2; done
    exit 1
}

env_value() {
    local key="$1" default="$2" value=""
    if [[ -f .env ]]; then
        value="$(grep -E "^[[:space:]]*${key}=" .env | tail -n1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//' || true)"
    fi
    printf '%s' "${value:-$default}"
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
if [[ ! -f .env ]]; then
    [[ -f .env.example ]] || fail '缺少 .env 与 .env.example，无法启动' '请从版本库恢复 .env.example 后重试'
    cp .env.example .env
    warn '未找到 .env，已从 .env.example 复制；请按需修改数据库/Redis/MinIO 密码与 JWT 密钥'
fi

COMPOSE=(compose --env-file .env -f deploy/docker-compose.dev.yml)

if [[ "$EXPOSE_DEBUG" -eq 1 ]]; then
    COMPOSE+=(-f deploy/docker-compose.dev.expose.yml)
    step '检查调试端口占用（仅绑定 127.0.0.1）'
    specs=(
        "PostgreSQL|POSTGRES_DEBUG_PORT|5432"
        "Redis|REDIS_DEBUG_PORT|6379"
        "MinIO API|MINIO_API_DEBUG_PORT|9010"
        "MinIO Console|MINIO_CONSOLE_DEBUG_PORT|9011"
    )
    conflicts=()
    for spec in "${specs[@]}"; do
        IFS='|' read -r name key default <<< "$spec"
        port="$(env_value "$key" "$default")"
        if owner="$(port_conflict "$port")"; then
            conflicts+=("${name} ${port} -> ${owner}")
        else
            printf '    %s %s 可用\n' "$name" "$port"
        fi
    done
    if [[ "${#conflicts[@]}" -gt 0 ]]; then
        fail '调试端口冲突，已中止启动（未改动任何运行中的容器）' \
            "${conflicts[@]}" \
            '修改 .env 中对应的 *_DEBUG_PORT 后重试' \
            '或先停止占用该端口的程序（如本机已有的 PostgreSQL/Redis）' \
            '不加 --expose-debug 时依赖栈不发布宿主端口，不会冲突'
    fi
fi

if [[ "$SKIP_PULL" -eq 0 ]]; then
    step '拉取镜像（首次执行耗时较长）'
    if ! docker "${COMPOSE[@]}" pull; then
        fail '镜像拉取失败' \
            '国内网络为 Docker Desktop 配置 clash 代理：Settings -> Resources -> Proxies -> Manual，HTTP/HTTPS 都填 http://127.0.0.1:7890' \
            'MinIO 使用社区维护分支镜像；若内网 Harbor 有镜像，在 .env 设置 MINIO_IMAGE' \
            '镜像已缓存时可加 --skip-pull 跳过拉取'
    fi
fi

# 只对长期运行的服务做 --wait（minio-init 是一次性任务，退出会影响 wait 判定）
UP=(up -d postgres redis minio)
if [[ "$NO_WAIT" -eq 0 ]]; then
    UP+=(--wait --wait-timeout 180)
fi

step '启动容器并等待健康检查'
if ! docker "${COMPOSE[@]}" "${UP[@]}"; then
    fail '容器启动或健康检查未通过' \
        '查看日志：docker compose --env-file .env -f deploy/docker-compose.dev.yml logs postgres redis minio' \
        '查看状态：docker compose --env-file .env -f deploy/docker-compose.dev.yml ps' \
        '镜像/网络问题：检查 Docker Desktop 代理（http://127.0.0.1:7890）或 MINIO_IMAGE' \
        '端口冲突：去掉 --expose-debug 后重试'
fi

if [[ "$NO_WAIT" -eq 0 ]]; then
    step '初始化 MinIO 附件桶'
    if ! docker "${COMPOSE[@]}" run --rm --no-deps -T minio-init; then
        warn 'MinIO 附件桶初始化失败（不影响 PostgreSQL/Redis/MinIO 运行）'
        warn '可手动重试：docker compose --env-file .env -f deploy/docker-compose.dev.yml run --rm minio-init'
    fi
fi

echo
printf '\033[32m本地依赖栈已就绪\033[0m\n'
echo '  PostgreSQL  postgres:5432   db=aps  user=aps'
echo '  Redis       redis:6379      已开启 AOF'
echo '  MinIO       http://minio:9000 (S3 API) / http://minio:9001 (Console)'
if [[ "$EXPOSE_DEBUG" -eq 1 ]]; then
    echo '  IDE 直连（仅 127.0.0.1）：'
    echo '    jdbc:postgresql://localhost:5432/aps'
    echo '    redis localhost:6379'
    echo '    http://localhost:9010 (MinIO API) / http://localhost:9011 (Console)'
else
    echo '  当前仅内网互通；IDE 需直连时重新执行：./scripts/dev-up.sh --expose-debug'
fi
echo
echo '校验扩展：docker compose --env-file .env -f deploy/docker-compose.dev.yml exec postgres psql -U aps -d aps -c "\dx"'
echo '停止栈：  ./scripts/dev-down.sh          （保留数据卷）'
echo '删除数据：./scripts/dev-down.sh --remove-volumes'
