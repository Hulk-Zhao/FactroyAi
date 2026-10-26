"""首次启动的数据初始化编排（供容器 entrypoint 调用）。

与 ``seed`` / ``kbqa_seed`` 的分工：这两个模块只负责「写」，
本模块按 ``SEED_MODE`` 决定「要不要写、要不要先清空」：

- ``skip``  ：完全不写（索引已由外部导入/备份恢复）；
- ``auto``  ：两个 collection 都非空则跳过；任一为空则补齐；
- ``force`` ：删除 collection 后全量重建（换 embedding 模型时必须用）。

所有写入都会调用云端 embedding，因此**绝不能在每次容器重启时无条件执行**。
"""
from __future__ import annotations

import sys

from .config import settings
from .rag_tools import collection_stats, purge_collections
from .seed import run_seed
from .kbqa_seed import run_seed as run_kbqa_seed


def ensure_ready(force: bool = False) -> dict[str, int]:
    mode = "force" if force else (settings.SEED_MODE or "auto").strip().lower()
    if mode == "skip":
        print("[bootstrap] SEED_MODE=skip，跳过种子")
        return collection_stats()

    if mode == "force":
        print("[bootstrap] SEED_MODE=force，清空并重建知识索引")
        purge_collections()
        stats = {"failure_cases": 0, "enterprise_faq": 0}
    else:
        stats = collection_stats()
        if stats["failure_cases"] and stats["enterprise_faq"]:
            print(f"[bootstrap] 索引已就绪，跳过种子：{stats}")
            return stats

    if not stats.get("failure_cases"):
        run_seed()
    if not stats.get("enterprise_faq"):
        run_kbqa_seed()

    final = collection_stats()
    _ensure_case_index()
    print(f"[bootstrap] 完成：{final}")
    return final


def _ensure_case_index() -> None:
    """案例索引预热（Chroma 后端）：避免首问现场等待 890 个 chunk 的 embedding。

    失败只告警——案例域自身会在首问时惰性重建，不阻断服务启动。
    """
    if (settings.CASE_VECTOR_BACKEND or "").strip().lower() != "chroma":
        return
    try:
        from ..v2.services.case_runtime import CaseRuntime

        runtime = CaseRuntime.build(backend="chroma", auto_seed=True)
        print(f"[bootstrap] 案例索引：{runtime.health()}")
    except Exception as exc:  # noqa: BLE001 - 预热失败不阻断启动
        print(f"[bootstrap] 案例索引预热失败（首问将重试）：{exc}")


if __name__ == "__main__":
    ensure_ready(force="--force" in sys.argv)
