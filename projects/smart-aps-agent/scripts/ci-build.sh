#!/usr/bin/env bash
# =============================================================================
# 衡策 APS · 本地复现 CI 构建（Linux / macOS / Git Bash）
#   与 .github/workflows/ci.yml、.gitlab-ci.yml 保持同一套命令。
#   示例：
#     ./scripts/ci-build.sh                                  # 后端打包 + 前端构建
#     ./scripts/ci-build.sh --tests --static                 # 含单测/覆盖率与静态检查
#     ./scripts/ci-build.sh --target frontend --proxy        # 走本机 127.0.0.1:7890 代理
# =============================================================================
set -euo pipefail

TARGET="all"
RUN_TESTS=0
RUN_STATIC=0
USE_PROXY=0
PROXY_URL="http://127.0.0.1:7890"

usage() {
  sed -n '3,8p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'
参数：
  --target <all|backend|frontend>   构建目标（默认 all）
  --tests                          执行后端单测并生成覆盖率报告
  --static                         执行 Checkstyle/PMD 与 ESLint/Prettier
  --proxy                          使用本机代理（默认 http://127.0.0.1:7890）
  --proxy-url <url>                自定义代理地址
  -h, --help                       显示帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      TARGET="${2:-}"
      shift 2
      ;;
    --target=*)
      TARGET="${1#*=}"
      shift
      ;;
    --tests)
      RUN_TESTS=1
      shift
      ;;
    --static)
      RUN_STATIC=1
      shift
      ;;
    --proxy)
      USE_PROXY=1
      shift
      ;;
    --proxy-url)
      PROXY_URL="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$TARGET" in
  all|backend|frontend) ;;
  *) echo "非法 --target：$TARGET（可选 all/backend/frontend）" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step() {
  local title="$1"
  shift
  echo "==> $title"
  "$@"
}

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "缺少命令 '$1'：$2" >&2
    exit 1
  }
}

if [[ $USE_PROXY -eq 1 ]]; then
  export HTTP_PROXY="$PROXY_URL"
  export HTTPS_PROXY="$PROXY_URL"
  export NO_PROXY="localhost,127.0.0.1"
  export npm_config_proxy="$PROXY_URL"
  export npm_config_https_proxy="$PROXY_URL"
  hostport="${PROXY_URL#*://}"
  host="${hostport%%:*}"
  port="${hostport##*:}"
  export MAVEN_OPTS="${MAVEN_OPTS:-} -Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttps.proxyHost=$host -Dhttps.proxyPort=$port"
  echo "已启用代理：$PROXY_URL（HTTP_PROXY / npm / MAVEN_OPTS）"
fi

MAVEN_CLI_OPTS="-B -ntp"

if [[ "$TARGET" == "all" || "$TARGET" == "backend" ]]; then
  require mvn "请安装 Maven 3.9+，并确保 JAVA_HOME 指向 JDK 21。"

  step "后端打包（skipTests）" \
    mvn $MAVEN_CLI_OPTS -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true package

  if [[ $RUN_TESTS -eq 1 ]]; then
    step "后端单元测试 + JaCoCo 覆盖率" \
      mvn $MAVEN_CLI_OPTS -Dcheckstyle.skip=true -Dpmd.skip=true \
      org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test \
      org.jacoco:jacoco-maven-plugin:0.8.12:report
  fi

  if [[ $RUN_STATIC -eq 1 ]]; then
    step "后端静态检查（Checkstyle / PMD，Skipped tests）" \
      mvn $MAVEN_CLI_OPTS -DskipTests verify
  fi
fi

if [[ "$TARGET" == "all" || "$TARGET" == "frontend" ]]; then
  require npm "请安装 Node.js（20 或 22 LTS）与 npm。"

  pushd aps-frontend >/dev/null
  step "前端依赖安装（npm ci）" npm ci
  if [[ $RUN_STATIC -eq 1 ]]; then
    step "ESLint（脚本缺失时跳过）" npm run lint --if-present
    step "Prettier 格式检查（脚本缺失时跳过）" npm run format:check --if-present
  fi
  step "前端类型检查 + 构建" npm run build
  popd >/dev/null
fi

# ----------------------------- 产物汇总 --------------------------------------
echo ""
echo "构建产物："
JAR="$(ls -1 aps-admin/target/aps-admin-*.jar 2>/dev/null | head -n 1 || true)"
if [[ -n "$JAR" ]]; then
  echo "  jar  : $JAR ($(du -h "$JAR" | cut -f1))"
else
  echo "  警告：未找到 aps-admin/target/aps-admin-*.jar" >&2
fi

if [[ -f aps-frontend/dist/index.html ]]; then
  echo "  dist : aps-frontend/dist ($(du -sh aps-frontend/dist | cut -f1))"
else
  echo "  警告：未找到 aps-frontend/dist/index.html" >&2
fi

echo "完成。"
