"""全量重建向量索引（换 embedding 模型后必须执行）。

用法（在 backend/ 目录）::

    python scripts/rebuild_index.py --source all
    python scripts/rebuild_index.py --source kbqa
    python scripts/rebuild_index.py --source case

为什么需要单独脚本：embedding 模型一变，向量空间就完全不同，旧索引
（384 维 ONNX / BGE）与新索引（1024 维云端）不可混用。此脚本会先删除
collection，再按当前配置全量重算，避免「半新半旧」的脏索引。
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
os.chdir(ROOT)  # 让 data/ 的相对路径与容器内一致


def _rebuild_knowledge() -> None:
    from mfg_agents.common.kbqa_seed import run_seed as run_kbqa_seed
    from mfg_agents.common.rag_tools import collection_stats, purge_collections
    from mfg_agents.common.seed import run_seed

    purge_collections()
    run_seed()
    run_kbqa_seed()
    print(f"[rebuild] knowledge done: {collection_stats()}")


def _rebuild_cases() -> None:
    from mfg_agents.common.config import settings
    from mfg_agents.common.rag_tools import _client
    from mfg_agents.v2.contracts.adapters.case_chroma import CHROMA_COLLECTION
    from mfg_agents.v2.services.case_runtime import CaseRuntime

    settings.CASE_VECTOR_BACKEND = "chroma"
    try:
        _client().delete_collection(CHROMA_COLLECTION)
    except Exception:  # noqa: BLE001 - 不存在即忽略
        pass

    runtime = CaseRuntime.build(backend="chroma", auto_seed=True)
    health = runtime.health()
    print(f"[rebuild] cases done: {health}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default="all",
                        choices=("all", "knowledge", "kbqa", "case"))
    args = parser.parse_args()

    if args.source in ("all", "knowledge", "kbqa"):
        _rebuild_knowledge()
    if args.source in ("all", "case"):
        _rebuild_cases()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
