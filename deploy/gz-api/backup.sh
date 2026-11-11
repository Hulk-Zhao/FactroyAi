#!/usr/bin/env bash
# =============================================================================
# 广州单机数据备份：APS PostgreSQL（逻辑导出）+ mfg SQLite（在线备份）+ hogo 运行时
#
# 用法（服务器上）：
#   bash deploy/gz-api/backup.sh                 # 默认写到 /opt/hulkai/backups
#   bash deploy/gz-api/backup.sh /data/backups   # 指定备份目录
#
# 特性：
#   - 不停止、不删除任何容器与数据，可在业务运行中执行；
#   - SQLite 走 sqlite3 backup API，避免直接 cp 正在写入的库；
#   - 只保留最近 14 天的备份文件；
#   - 任一步失败立即退出（set -e），不会留下"看起来成功"的空备份。
#
# 注意：恢复步骤见 deploy/data-persistence.md 第 5 节；禁止 docker compose down -v。
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"

BACKUP_DIR="${1:-/opt/hulkai/backups}"
STAMP="$(date +%F-%H%M%S)"
DB_USER="${APS_DB_USER:-aps}"
DB_NAME="${APS_DB_NAME:-aps}"
TAR_IMAGE="${BACKUP_TAR_IMAGE:-nginx:1.27-alpine}"

mkdir -p "$BACKUP_DIR"

echo "[1/3] APS PostgreSQL → aps-${STAMP}.dump"
# 本地 socket 连接，postgres 官方镜像默认对本地 trust，无需在命令行传口令
docker compose exec -T aps-postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$BACKUP_DIR/aps-${STAMP}.dump"
# 校验：自定义格式文件以 PGDMP 开头，且大小合理
if [ "$(stat -c %s "$BACKUP_DIR/aps-${STAMP}.dump")" -lt 1024 ]; then
  echo "备份文件过小，疑似 pg_dump 失败：$BACKUP_DIR/aps-${STAMP}.dump" >&2
  exit 1
fi
if [ "$(head -c 5 "$BACKUP_DIR/aps-${STAMP}.dump")" != "PGDMP" ]; then
  echo "备份文件不是 PostgreSQL 自定义格式：$BACKUP_DIR/aps-${STAMP}.dump" >&2
  exit 1
fi

echo "[2/3] mfg SQLite 在线备份 → mfg_agent-${STAMP}.db / case-${STAMP}.db"
docker compose exec -T app python -c "
import sqlite3
for name in ('mfg_agent', 'case'):
    src = sqlite3.connect(f'/app/data/{name}.db')
    dst = sqlite3.connect(f'/tmp/{name}.bak')
    src.backup(dst)
    dst.close()
    src.close()
print('sqlite backup ok')
"
docker cp mfg_app:/tmp/mfg_agent.bak "$BACKUP_DIR/mfg_agent-${STAMP}.db"
docker cp mfg_app:/tmp/case.bak "$BACKUP_DIR/case-${STAMP}.db"
docker compose exec -T app rm -f /tmp/mfg_agent.bak /tmp/case.bak

echo "[3/3] hogo 运行时密钥 → hogo_runtime-${STAMP}.tgz"
docker run --rm \
  -v hulkai_hogo_runtime:/data:ro \
  -v "$BACKUP_DIR":/backup \
  "$TAR_IMAGE" tar czf "/backup/hogo_runtime-${STAMP}.tgz" -C /data .

echo "清理 14 天前的备份"
find "$BACKUP_DIR" -maxdepth 1 -type f -mtime +14 -delete

echo "备份完成：$BACKUP_DIR"
ls -lh "$BACKUP_DIR" | tail -n 6
