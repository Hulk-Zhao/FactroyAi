"""RAG 知识库工具：Chroma 持久化 + 云端 embedding + 本地 BM25 混合排序。

改造要点（相对旧版）
--------------------
- **移除 Milvus / BGE-M3 / Reranker**：向量由云端 ``text-embedding-v4`` 计算，
  维度 1024；Milvus 适配器与本地模型加载全部删除；
- **两路召回 + RRF**：dense（云端 cosine）与 lexical（本地 BM25）各自排名，
  用 Reciprocal Rank Fusion 融合。这样不需要交叉编码器，也避免 cosine 与
  BM25 分数不可比的问题；
- **collection 改名**：``*_dsv4``。Chroma 的维度在首次写入时固定，旧库
  （384 维 ONNX）不能复用，换名是最安全的隔离方式；
- **显式传向量**：写入/查询都直接传 ``embeddings`` / ``query_embeddings``，
  不依赖 Chroma 的默认 embedding function（否则容器内会下载 ONNX 模型）。
"""
from __future__ import annotations

import logging
import re
import threading
from typing import Any

from .config import settings
from .embedding import ChromaEmbedding, get_embedder
from .rerank import get_reranker
from .retrieval import BM25Index, rrf_fuse

logger = logging.getLogger(__name__)

#: collection 名（后缀 dsv4 = DashScope text-embedding-v4，不可与旧索引混用）
COLLECTION_FAILURE_CASES = "failure_cases_dsv4"
COLLECTION_ENTERPRISE_FAQ = "enterprise_faq_dsv4"

_collections: dict[str, Any] = {}
_corpus_cache: dict[str, tuple[int, list[str], list[str], list[dict], BM25Index]] = {}
_cache_lock = threading.Lock()
_write_lock = threading.Lock()


# ---------------------------------------------------------------- 客户端


def _client() -> Any:
    """Chroma 客户端：远端 CHROMA_HOST → HttpClient；否则本地持久化。"""
    import chromadb

    host = (settings.CHROMA_HOST or "").strip()
    if host and host not in ("localhost", "127.0.0.1", "::1"):
        return chromadb.HttpClient(host=host, port=settings.CHROMA_PORT)

    import os
    persist_dir = str(settings.CHROMA_DIR)
    os.makedirs(persist_dir, exist_ok=True)
    return chromadb.PersistentClient(path=persist_dir)


def _get_collection(name: str = COLLECTION_FAILURE_CASES) -> Any:
    """按名获取/创建 collection（惰性、进程内缓存）。"""
    if name in _collections:
        return _collections[name]
    with _cache_lock:
        if name in _collections:
            return _collections[name]
        client = _client()
        ef = ChromaEmbedding()
        try:
            col = client.get_or_create_collection(
                name=name,
                embedding_function=ef,
                configuration={"hnsw": {"space": "cosine"}},
            )
        except TypeError:
            # 兼容 chromadb 0.5.x（无 configuration 参数）
            col = client.get_or_create_collection(
                name=name, embedding_function=ef,
                metadata={"hnsw:space": "cosine"})
        _collections[name] = col
        return col


def reset_collections() -> None:
    """测试/重建索引后清空进程内缓存。"""
    with _cache_lock:
        _collections.clear()
        _corpus_cache.clear()


# ---------------------------------------------------------------- 语料缓存


def _corpus(name: str) -> tuple[list[str], list[str], list[dict], BM25Index]:
    """拉取 collection 全量文本并建 BM25（带 count 失效）。"""
    col = _get_collection(name)
    count = int(col.count())
    with _cache_lock:
        cached = _corpus_cache.get(name)
        if cached and cached[0] == count:
            return cached[1], cached[2], cached[3], cached[4]

    raw = col.get(include=["documents", "metadatas"])
    ids = [str(i) for i in (raw.get("ids") or [])]
    docs = [str(d or "") for d in (raw.get("documents") or [])]
    metas = [dict(m or {}) for m in (raw.get("metadatas") or [])]
    bm25 = BM25Index.build(docs)
    with _cache_lock:
        _corpus_cache[name] = (count, ids, docs, metas, bm25)
    return ids, docs, metas, bm25


def _dense_ids(name: str, query: str, limit: int) -> tuple[list[str], dict[str, float]]:
    """云端向量召回，返回（按相似度降序的 id 列表, id→cosine）。"""
    col = _get_collection(name)
    vector = get_embedder().encode_one(query)
    raw = col.query(
        query_embeddings=[vector],
        n_results=max(1, int(limit)),
        include=["distances"],
    )
    ids = [str(i) for i in ((raw.get("ids") or [[]])[0])]
    dists = [float(d) for d in ((raw.get("distances") or [[]])[0])]
    scores = {doc_id: 1.0 - dist for doc_id, dist in zip(ids, dists)}
    return ids, scores


def _hybrid(name: str, query: str, k: int,
            *, how_to: bool = False) -> list[dict[str, Any]]:
    """dense + BM25 → RRF → 业务加权 → 去重，返回前 k 条。"""
    ids, docs, metas, bm25 = _corpus(name)
    if not ids:
        return []
    index_of = {doc_id: i for i, doc_id in enumerate(ids)}

    recall = max(int(settings.KBQA_RECALL_TOP_K), int(k) * 4)
    dense_ids, dense_scores = _dense_ids(name, query, recall)
    lexical = bm25.search(query, k=recall)
    lexical_ids = [ids[i] for i, _ in lexical if 0 <= i < len(ids)]

    # 只保留两路都存在的 id 的排名；RRF 只看名次
    fused = rrf_fuse([dense_ids, lexical_ids], k=int(settings.KBQA_RRF_K))

    # 候选池：开了重排就多取一些（重排只对池子打分），否则只取 2 倍
    reranker = get_reranker()
    pool_size = max(int(settings.RERANK_CANDIDATES), int(k)) \
        if reranker.available else max(int(k), int(k) * 2)
    ordered = sorted(fused.items(), key=lambda x: (-x[1], x[0]))[:pool_size]

    candidates: list[dict[str, Any]] = []
    for doc_id, fused_score in ordered:
        idx = index_of.get(doc_id)
        if idx is None:
            continue
        candidates.append({
            "id": doc_id,
            "document": docs[idx],
            "metadata": dict(metas[idx] or {}),
            "score": round(float(dense_scores.get(doc_id, 0.0)), 6),
            "rrf_score": round(float(fused_score), 8),
        })

    # 精排：Qwen-Rerank 成功 → 按 relevance 排序；失败 → 回落 RRF（不阻断）
    rerank_scores = reranker.rerank(
        query, [c["document"] for c in candidates]) if candidates else None
    if rerank_scores is not None:
        for cand, score in zip(candidates, rerank_scores):
            cand["rerank_score"] = round(float(score), 6)
            cand["score"] = round(float(score), 6)
        candidates.sort(key=lambda c: (-c["score"], c["rrf_score"] * -1, c["id"]))
    else:
        # 无重排时保留业务加权：how_to 优先「操作细则」
        if how_to:
            for cand in candidates:
                source = str((cand["metadata"] or {}).get("source") or "")
                if "操作细则" in source:
                    cand["rrf_score"] = round(cand["rrf_score"] + 0.008, 8)
        candidates.sort(key=lambda c: (-c["rrf_score"], c["id"]))

    # 同一 source 最多 2 块；相邻块合并上下文（small-to-big）
    picked: list[dict[str, Any]] = []
    per_source: dict[str, int] = {}
    for cand in candidates:
        source = str((cand["metadata"] or {}).get("source") or "")
        if source and per_source.get(source, 0) >= 2:
            continue
        if source:
            per_source[source] = per_source.get(source, 0) + 1
        picked.append(cand)
        if len(picked) >= max(1, int(k)):
            break

    return _merge_adjacent(picked)


def _merge_adjacent(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """同一 source 且 chunk_index 相邻的两块合并进前一条（保持原排序）。"""
    out: list[dict[str, Any]] = []
    used: set[int] = set()
    for i, row in enumerate(rows):
        if i in used:
            continue
        meta = row.get("metadata") or {}
        try:
            idx = int(meta.get("chunk_index"))
        except (TypeError, ValueError):
            out.append(row)
            continue
        for j in range(i + 1, len(rows)):
            if j in used:
                continue
            other_meta = rows[j].get("metadata") or {}
            if str(other_meta.get("source") or "") != str(meta.get("source") or ""):
                continue
            try:
                other_idx = int(other_meta.get("chunk_index"))
            except (TypeError, ValueError):
                continue
            if abs(other_idx - idx) != 1:
                continue
            merged = dict(row)
            a, b = (idx, other_idx) if idx < other_idx else (other_idx, idx)
            first, second = (row, rows[j]) if idx < other_idx else (rows[j], row)
            merged["document"] = f"{first['document']}\n{second['document']}"
            merged["metadata"] = {**row.get("metadata", {}),
                                  "chunk_index": a, "merged_chunks": 2}
            out.append(merged)
            used.add(j)
            break
        else:
            out.append(row)
    return out


# ---------------------------------------------------------------- 历史案例（FA）


def add_case(doc_id: str, document: str, metadata: dict[str, Any]) -> None:
    add_cases([(str(doc_id), document, metadata)])


def add_cases(rows: list[tuple[str, str, dict[str, Any]]]) -> int:
    """批量写入历史案例/知识条目（一次计算向量，分批 ≤10）。"""
    if not rows:
        return 0
    col = _get_collection(COLLECTION_FAILURE_CASES)
    ids = [str(r[0]) for r in rows]
    docs = [str(r[1]) for r in rows]
    metas = [dict(r[2] or {}) for r in rows]
    vectors = get_embedder().encode(docs)
    with _write_lock:
        col.upsert(ids=ids, documents=docs, metadatas=metas,
                   embeddings=vectors)
    _invalidate(COLLECTION_FAILURE_CASES)
    return len(ids)


def search_cases(query: str, n_results: int = 3) -> list[dict[str, Any]]:
    hits = _hybrid(COLLECTION_FAILURE_CASES, query, n_results)
    return [{"document": h["document"], "metadata": h["metadata"],
             "score": h["score"]} for h in hits]


# ---------------------------------------------------------------- 企业制度问答


def add_kb_chunks(ids: list[str], documents: list[str],
                  metadatas: list[dict[str, Any]]) -> int:
    """写入企业制度知识块（内部按 ≤10 条计算向量）。"""
    if not ids:
        return 0
    col = _get_collection(COLLECTION_ENTERPRISE_FAQ)
    vectors = get_embedder().encode([str(d) for d in documents])
    with _write_lock:
        col.upsert(ids=[str(i) for i in ids],
                   documents=[str(d) for d in documents],
                   metadatas=[dict(m or {}) for m in metadatas],
                   embeddings=vectors)
    _invalidate(COLLECTION_ENTERPRISE_FAQ)
    return len(ids)


_HOW_TO_RE = re.compile(r"怎么|如何|怎样|咋办")


def search_kb(query: str, n_results: int = 3,
              *, how_to: bool | None = None) -> list[dict[str, Any]]:
    """企业制度检索：dense + BM25 → RRF。

    ``how_to=None`` 时按问法自动识别（怎么/如何/怎样/咋办）；识别到则优先
    「操作细则」类文档。返回 ``[{document, metadata, score, rrf_score}]``，
    其中 ``score`` 为 cosine（展示/拒答用），``rrf_score`` 为排序依据。
    """
    if how_to is None:
        how_to = bool(_HOW_TO_RE.search(str(query or "")))
    hits = _hybrid(COLLECTION_ENTERPRISE_FAQ, query, n_results,
                   how_to=bool(how_to))
    if settings.KBQA_MIN_SCORE > 0:
        hits = [h for h in hits
                if float(h.get("score") or 0.0) >= settings.KBQA_MIN_SCORE]
    return hits


def kb_count() -> int:
    """企业制度知识库块数（前端展示/健康检查）。"""
    return int(_get_collection(COLLECTION_ENTERPRISE_FAQ).count())


def _invalidate(name: str) -> None:
    with _cache_lock:
        _corpus_cache.pop(name, None)


def purge_collections() -> None:
    """删除两个知识 collection（重建索引/force 种子时使用）。"""
    client = _client()
    for name in (COLLECTION_FAILURE_CASES, COLLECTION_ENTERPRISE_FAQ):
        try:
            client.delete_collection(name)
        except Exception:  # noqa: BLE001 - 不存在时忽略
            pass
    reset_collections()


def collection_stats() -> dict[str, int]:
    """索引概览（重建脚本与 health 用）。"""
    return {
        "failure_cases": int(_get_collection(COLLECTION_FAILURE_CASES).count()),
        "enterprise_faq": int(_get_collection(COLLECTION_ENTERPRISE_FAQ).count()),
    }
