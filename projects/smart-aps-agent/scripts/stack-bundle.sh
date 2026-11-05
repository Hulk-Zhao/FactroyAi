#!/usr/bin/env bash
# 生成 APS 整栈离线部署包（镜像 tar + 制品 + 编排/脚本 + MANIFEST）。
# 等价 PowerShell 脚本：scripts/stack-bundle.ps1
# 产物默认输出到仓库之外的 ../_aps-artifacts/dist-offline（约 548MB，含镜像 tar），
# 仓库内只保留生成脚本与文档，不保留产物。
#
# 用法：
#   ./scripts/stack-bundle.sh                     # 默认 ../_aps-artifacts/dist-offline
#   ./scripts/stack-bundle.sh --output-dir /tmp/aps-offline
#   ./scripts/stack-bundle.sh --skip-images       # 只收集制品与脚本
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT_DIR="$(dirname "$ROOT_DIR")/_aps-artifacts/dist-offline"
SKIP_IMAGES=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir) OUT_DIR="${2:?缺少 --output-dir 的值}"; shift 2 ;;
        --skip-images) SKIP_IMAGES=1; shift ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "[ERROR] 未知参数: $1" >&2; exit 2 ;;
    esac
done

step() { printf '\033[36m[..] %s\033[0m\n' "$*"; }
detail() { printf '    %s\n' "$*"; }
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

JAR_PATH="aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar"
DIST_PATH="aps-frontend/dist"
[[ -f "$JAR_PATH" ]] || fail "未找到后端制品：$JAR_PATH" '构建制品由 W9 统一交付，本脚本不执行 mvn package'
[[ -f "$DIST_PATH/index.html" ]] || fail "未找到前端产物：$DIST_PATH/index.html" '请先由前端负责人完成 npm run build'

step "创建离线目录 $OUT_DIR"
mkdir -p "$OUT_DIR"/{deploy/backend,deploy/initdb,aps-admin/target,aps-frontend/dist,aps-frontend/nginx,scripts,images,docs/deploy}

step '复制编排、脚本与制品'
cp deploy/docker-compose.yml "$OUT_DIR/deploy/docker-compose.yml"
cp deploy/backend/Dockerfile "$OUT_DIR/deploy/backend/Dockerfile"
cp deploy/initdb/01-extensions.sql "$OUT_DIR/deploy/initdb/01-extensions.sql"
cp .env.example "$OUT_DIR/.env.example"
cp scripts/stack-up.ps1 scripts/stack-down.ps1 scripts/stack-up.sh scripts/stack-down.sh "$OUT_DIR/scripts/"
cp docs/deploy/docker-compose.md "$OUT_DIR/docs/deploy/docker-compose.md"
cp "$JAR_PATH" "$OUT_DIR/aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar"
cp aps-frontend/nginx/default.conf.template "$OUT_DIR/aps-frontend/nginx/default.conf.template"
cp -R "$DIST_PATH"/. "$OUT_DIR/aps-frontend/dist/"

MINIO_IMAGE="$(env_value MINIO_IMAGE 'pgsty/minio:RELEASE.2026-08-04T00-00-00Z')"
BACKEND_IMAGE="$(env_value APS_BACKEND_IMAGE 'aps-stack/aps-backend:local')"
FRONTEND_IMAGE="$(env_value APS_FRONTEND_IMAGE 'nginxinc/nginx-unprivileged:1.31-alpine')"
IMAGES=(postgres:15 redis:7 "$MINIO_IMAGE" "$BACKEND_IMAGE" "$FRONTEND_IMAGE")

IMAGE_LINES=()
if [[ "$SKIP_IMAGES" -eq 0 ]]; then
    step '导出镜像 tar（首次执行耗时较长）'
    for img in "${IMAGES[@]}"; do
        docker image inspect "$img" >/dev/null 2>&1 || fail "本机缺少镜像：$img" \
            '先执行 ./scripts/stack-up.sh 完成拉取/构建后再生成离线包' \
            '或改在已有镜像的机器上导出'
    done
    docker save -o "$OUT_DIR/images/aps-stack-images.tar" "${IMAGES[@]}" || fail 'docker save 失败' \
        '确认 Docker 守护进程正常且磁盘空间充足'
    IMAGE_LINES+=('images/aps-stack-images.tar')
    for img in "${IMAGES[@]}"; do IMAGE_LINES+=("  image: $img"); done
else
    step '已跳过镜像导出（--skip-images）'
    for img in "${IMAGES[@]}"; do IMAGE_LINES+=("  image: $img （需目标机器已有）"); done
fi

step '写入清单与校验值'
JAR_SIZE=$(wc -c <"$JAR_PATH" | tr -d ' ')
if command -v sha256sum >/dev/null 2>&1; then
    JAR_HASH=$(sha256sum "$JAR_PATH" | awk '{print $1}' | tr 'a-f' 'A-F')
else
    JAR_HASH=$(shasum -a 256 "$JAR_PATH" | awk '{print $1}' | tr 'a-f' 'A-F')
fi
{
    echo 'APS 整栈离线部署包'
    echo "生成时间: $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "主机: $(hostname)"
    echo ''
    echo 'jar: aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar'
    echo "jar_size: $JAR_SIZE"
    echo "jar_sha256: $JAR_HASH"
    echo 'dist: aps-frontend/dist'
    echo ''
    echo '镜像:'
    for line in "${IMAGE_LINES[@]}"; do echo "$line"; done
    echo ''
    echo '安装步骤:'
    echo '  1) docker load -i images/aps-stack-images.tar'
    echo '  2) cp .env.example .env 并替换 POSTGRES_PASSWORD / REDIS_PASSWORD / MINIO_ROOT_PASSWORD / APS_SECURITY_MODE / APS_SECURITY_JWT_SECRET'
    echo '  3) ./scripts/stack-up.sh --skip-pull'
    echo '  4) curl -fsS http://127.0.0.1:8080/api/v1/health'
    echo ''
    echo '说明: 部署链路不编译业务代码；后端挂载 jar，前端挂载 dist。'
} >"$OUT_DIR/MANIFEST.txt"

TOTAL=$(du -sm "$OUT_DIR" | awk '{print $1}')
echo
printf '\033[32m离线包已生成（仓库外）：%s\033[0m\n' "$OUT_DIR"
detail "总大小 ${TOTAL} MB   jar SHA256=${JAR_HASH}"
detail "清单：$OUT_DIR/MANIFEST.txt"
echo '  拷到目标机器后：docker load -i images/aps-stack-images.tar -> ./scripts/stack-up.sh --skip-pull'
