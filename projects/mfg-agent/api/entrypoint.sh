#!/bin/bash
# 容器入口：演示种子库 → 知识库索引（按 SEED_MODE）→ FastAPI
set -e

DATA_DIR="${DATA_DIR:-/app/data}"
SEED_DIR="${SEED_DIR:-/app/seed}"
DB_FILE="${DB_FILE:-$DATA_DIR/mfg_agent.db}"
CASE_DB_FILE="${CASE_DB_FILE:-$DATA_DIR/case.db}"

mkdir -p "$DATA_DIR"

# 1) 业务/治理库：优先用随镜像分发的演示种子（含历史工单，演示效果与旧环境一致）；
#    没有种子才走 init_db 建空库。
if [ ! -f "$DB_FILE" ]; then
  if [ -f "$SEED_DIR/demo_seed/mfg_agent.db" ]; then
    echo "[entrypoint] seeding demo database from $SEED_DIR/demo_seed/mfg_agent.db"
    cp "$SEED_DIR/demo_seed/mfg_agent.db" "$DB_FILE"
  else
    echo "[entrypoint] no demo seed, initializing empty demo database..."
    python -m mfg_agents.common.init_db || echo "[entrypoint] init_db skipped"
  fi
else
  echo "[entrypoint] demo database exists: $DB_FILE (skip seed)"
fi

# 2) 案例库：同样优先种子
if [ ! -f "$CASE_DB_FILE" ] && [ -f "$SEED_DIR/demo_seed/case.db" ]; then
  echo "[entrypoint] seeding case database from $SEED_DIR/demo_seed/case.db"
  cp "$SEED_DIR/demo_seed/case.db" "$CASE_DB_FILE"
fi

# 3) 生态链演示数据：装载层读 $DATA_DIR/demo_storyline，但 volume 会遮挡镜像内
#    /app/seed，故从种子目录同步过去。两种情况都补齐：
#    a) 目录不存在           → 整目录复制；
#    b) 目录存在但缺 jsonl   → 逐文件 cp -n 补齐（如 volume 里只有运行期
#      demo_governance.db），不覆盖已有文件，保留运行期治理库与人工数据。
if [ -d "$SEED_DIR/demo_storyline" ]; then
  if [ ! -d "$DATA_DIR/demo_storyline" ]; then
    echo "[entrypoint] seeding demo storyline from $SEED_DIR/demo_storyline"
    cp -r "$SEED_DIR/demo_storyline" "$DATA_DIR/demo_storyline"
  elif ! ls "$DATA_DIR"/demo_storyline/*.jsonl >/dev/null 2>&1; then
    echo "[entrypoint] demo storyline exists but missing jsonl, backfilling (no overwrite)"
    cp -rn "$SEED_DIR/demo_storyline/." "$DATA_DIR/demo_storyline/" || true
  fi
fi

# 4) 知识索引：auto 已建则跳过；force 清空重建；skip 不写
echo "[entrypoint] ensuring knowledge index (SEED_MODE=${SEED_MODE:-auto})..."
python -m mfg_agents.common.bootstrap || echo "[entrypoint] bootstrap failed (service will report degraded)"

echo "[entrypoint] starting FastAPI on :8004 (workers=${UVICORN_WORKERS:-2}, SSE via Redis Stream)..."
exec python -m uvicorn mfg_agents.v2.demo.api:app \
    --host 0.0.0.0 \
    --port 8004 \
    --workers ${UVICORN_WORKERS:-2}
