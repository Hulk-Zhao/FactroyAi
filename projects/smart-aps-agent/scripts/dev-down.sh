#!/usr/bin/env bash
# 停止 APS 本地开发依赖栈
# 默认保留数据卷；--remove-volumes 会连同数据一起删除（需要输入 yes 确认）。
#
# 用法：
#   ./scripts/dev-down.sh                        # 停止容器，保留数据卷
#   ./scripts/dev-down.sh --remove-volumes       # 删除容器 + 数据卷
#   ./scripts/dev-down.sh --remove-volumes --force  # 跳过交互确认（自动化用）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REMOVE_VOLUMES=0
FORCE=0

for arg in "$@"; do
    case "$arg" in
        --remove-volumes) REMOVE_VOLUMES=1 ;;
        --force) FORCE=1 ;;
        -h|--help)
            sed -n '2,10p' "$0"
            exit 0
            ;;
        *)
            echo "[ERROR] 未知参数: $arg（支持 --remove-volumes / --force）" >&2
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

step '检查 Docker CLI 与守护进程'
command -v docker >/dev/null 2>&1 || fail '未找到 docker 命令' \
    '安装 Docker Desktop：https://docs.docker.com/desktop/install/'
docker info >/dev/null 2>&1 || fail 'Docker 守护进程未运行' \
    '启动 Docker Desktop 后重试' \
    '若容器已不存在，可直接用 docker volume rm 清理残留卷'
docker compose version >/dev/null 2>&1 || fail '未检测到 docker compose v2 插件' \
    'Docker Desktop 自带；Linux 安装 docker-compose-plugin'

if [[ "$REMOVE_VOLUMES" -eq 1 && "$FORCE" -eq 0 ]]; then
    warn '即将删除所有本地数据卷（数据库/Redis/MinIO 数据都会丢失）'
    read -r -p '确认请输入 yes：' answer
    if [[ "$answer" != "yes" ]]; then
        echo '已取消，未做任何操作。'
        exit 0
    fi
fi

COMPOSE=(compose --env-file .env -f deploy/docker-compose.dev.yml)
DOWN=(down --remove-orphans)
if [[ "$REMOVE_VOLUMES" -eq 1 ]]; then
    DOWN+=(-v)
fi

step '停止并移除容器/网络'
if ! docker "${COMPOSE[@]}" "${DOWN[@]}"; then
    fail 'docker compose down 执行失败' \
        '确认 .env 存在且未被占用：cat .env' \
        '查看残留容器：docker ps -a --filter name=aps-dev'
fi

if [[ "$REMOVE_VOLUMES" -eq 1 ]]; then
    printf '\033[32m容器与数据卷已删除，下次 dev-up 会重新初始化数据库并创建扩展。\033[0m\n'
else
    printf '\033[32m容器已停止，数据卷保留；下次 dev-up 会复用现有数据。\033[0m\n'
fi
