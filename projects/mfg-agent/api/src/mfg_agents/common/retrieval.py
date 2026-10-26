"""轻量本地检索组件：字符二元组 BM25 + RRF 融合。

为什么自建而不是引 jieba/rank_bm25
---------------------------------
- 语料只有几百到两千 chunk，全量索引在内存里、查询 <10ms；
- 中文按「单字 + 相邻二元组」切词，不依赖词典，专有名词（锡膏/8D/VPN）
  也能命中；
- 只为了 RRF 的名次，不做花哨的语义扩展——语义部分交给云端 embedding。

用法
----
>>> bm = BM25Index.build(["制度文本一", "制度文本二"])
>>> bm.search("报销时限", k=5)      # -> [(doc_index, score), ...]
"""
from __future__ import annotations

import math
import re
from collections import Counter
from typing import Iterable, Sequence

__all__ = ["tokenize", "BM25Index", "rrf_fuse"]

_LATIN_RE = re.compile(r"[a-z0-9]+")
_CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def tokenize(text: str) -> list[str]:
    """中文：单字 + 相邻二元组；拉丁/数字：整词。"""
    raw = str(text or "").lower()
    tokens: list[str] = _LATIN_RE.findall(raw)
    chars = _CJK_RE.findall(raw)
    tokens.extend(chars)
    tokens.extend(a + b for a, b in zip(chars, chars[1:]))
    return tokens


class BM25Index:
    """内存 BM25（k1=1.5, b=0.75），适合 ≤ 5 万 chunk 的演示/单机场景。"""

    def __init__(self, docs: Sequence[str], *, k1: float = 1.5,
                 b: float = 0.75) -> None:
        self._docs = list(docs)
        self._k1 = float(k1)
        self._b = float(b)
        self._tf: list[Counter[str]] = []
        self._len: list[int] = []
        df: Counter[str] = Counter()
        for doc in self._docs:
            counts = Counter(tokenize(doc))
            self._tf.append(counts)
            self._len.append(sum(counts.values()))
            df.update(counts.keys())
        self._df = df
        self._avgdl = (sum(self._len) / len(self._len)) if self._len else 0.0
        self._n = len(self._docs)

    @classmethod
    def build(cls, docs: Sequence[str], **kwargs: float) -> "BM25Index":
        return cls(docs, **kwargs)

    def __len__(self) -> int:
        return self._n

    def search(self, query: str, k: int = 10) -> list[tuple[int, float]]:
        if not self._n or k <= 0:
            return []
        q_tokens = tokenize(query)
        if not q_tokens:
            return []
        q_unique = set(q_tokens)
        scored: list[tuple[int, float]] = []
        avgdl = self._avgdl or 1.0
        for idx, counts in enumerate(self._tf):
            dl = self._len[idx] or 1
            score = 0.0
            for token in q_unique:
                tf = counts.get(token, 0)
                if not tf:
                    continue
                df = self._df.get(token, 0) or 1
                idf = math.log(1 + (self._n - df + 0.5) / (df + 0.5))
                denom = tf + self._k1 * (1 - self._b + self._b * dl / avgdl)
                score += idf * (tf * (self._k1 + 1)) / denom
            if score > 0:
                scored.append((idx, round(score, 6)))
        scored.sort(key=lambda x: (-x[1], x[0]))
        return scored[:k]


def rrf_fuse(rankings: Iterable[Sequence[str]], *, k: int = 60,
             weights: Sequence[float] | None = None) -> dict[str, float]:
    """Reciprocal Rank Fusion：``score(d) = Σ w_i / (k + rank_i(d))``。

    只看名次，不看原始分数——cosine 与 BM25 量纲不可比，这样换模型/换语料
    都不需要重标融合参数。
    """
    weight_list = list(weights or [1.0] * len(list(rankings)))
    fused: dict[str, float] = {}
    for lane_idx, ranking in enumerate(rankings):
        weight = weight_list[lane_idx] if lane_idx < len(weight_list) else 1.0
        for rank, doc_id in enumerate(ranking, start=1):
            fused[doc_id] = fused.get(doc_id, 0.0) + weight / (k + rank)
    return fused
