"""云端重排（Qwen-Rerank / DashScope 原生接口）。

为什么用它
----------
embedding 只负责“召回”，rerank 负责“在候选里排对顺序”。制度条文与质量案例
高度同构，纯 cosine 常把相邻条款排反（例如“年假怎么休”把“调休”排到第一）。
Qwen-Rerank 单次只对 top-N 候选打分，用量很小（约几千 token/次），失败时
**返回 None 让调用方回落到 RRF/确定性重排**，不阻断检索。

接口
----
``POST {base}/services/rerank/text-rerank/text-rerank``
请求：``{"model": "...", "input": {"query": "...", "documents": [...]},
        "parameters": {"return_documents": false, "top_n": N}}``
响应：``{"output": {"results": [{"index": 0, "relevance_score": 0.94}]}}``
"""
from __future__ import annotations

import hashlib
import logging
import threading
import time
from collections import OrderedDict
from typing import Sequence

import httpx

from .config import settings

logger = logging.getLogger(__name__)

__all__ = ["CloudReranker", "get_reranker", "reset_reranker"]


class CloudReranker:
    """Qwen-Rerank 客户端（同步、尽力而为、失败即回落）。"""

    def __init__(self, *, model: str | None = None,
                 api_key: str | None = None,
                 base_url: str | None = None,
                 timeout: float | None = None,
                 retries: int | None = None,
                 cache_size: int = 512,
                 enabled: bool | None = None) -> None:
        self.enabled = bool(getattr(settings, "RERANK_ENABLED", True)
                            if enabled is None else enabled)
        self.model = str(model or getattr(settings, "RERANK_MODEL",
                                          "qwen3.7-text-rerank")).strip()
        self.api_key = str(
            api_key if api_key is not None
            else (getattr(settings, "RERANK_API_KEY", "")
                  or getattr(settings, "DASHSCOPE_API_KEY", "")
                  or getattr(settings, "QW_KEY", "")) or "").strip()
        self.base_url = str(
            base_url or getattr(settings, "RERANK_BASE_URL", "")
            or getattr(settings, "DASHSCOPE_BASE_URL", "")
            or "").strip().rstrip("/")
        self.timeout = float(timeout or getattr(settings, "RERANK_TIMEOUT_S", 8))
        self.retries = max(0, int(retries if retries is not None
                                  else getattr(settings, "RERANK_RETRIES", 1)))
        self._cache: OrderedDict[str, list[float]] = OrderedDict()
        self._cache_limit = max(0, int(cache_size))
        self._lock = threading.Lock()
        self._client: httpx.Client | None = None

    @property
    def available(self) -> bool:
        return bool(self.enabled and self.api_key)

    @property
    def endpoint(self) -> str:
        base = (self.base_url
                or "https://cn-beijing.maas.qianwenaiapi.com/api/v1").rstrip("/")
        return base + "/services/rerank/text-rerank/text-rerank"

    def rerank(self, query: str, documents: Sequence[str]) -> list[float] | None:
        """返回与 ``documents`` 顺序对齐的 relevance 分数；失败返回 None。"""
        docs = [str(d or "") for d in documents]
        if not self.available or not query or not docs:
            return None
        key = self._cache_key(query, docs)
        cached = self._cache_get(key)
        if cached is not None:
            return cached

        payload = {
            "model": self.model,
            "input": {"query": str(query), "documents": docs},
            "parameters": {"return_documents": False, "top_n": len(docs)},
        }
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        for attempt in range(self.retries + 1):
            try:
                resp = self._client_or_create().post(
                    self.endpoint, json=payload, headers=headers)
                if resp.status_code in (429, 500, 502, 503, 504):
                    raise httpx.HTTPStatusError(
                        f"HTTP {resp.status_code}: {resp.text[:200]}",
                        request=resp.request, response=resp)
                if resp.status_code >= 400:
                    raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                data = resp.json()
                results = (data.get("output") or {}).get("results") or []
                scores = [0.0] * len(docs)
                seen: set[int] = set()
                for item in results:
                    idx = int(item.get("index", -1))
                    if 0 <= idx < len(scores):
                        scores[idx] = float(item.get("relevance_score") or 0.0)
                        seen.add(idx)
                if len(seen) != len(docs):
                    # top_n 截断或返回不全时，未返回项按 0 分处理，不丢顺序
                    logger.debug("[rerank] 返回 %s/%s 条", len(seen), len(docs))
                self._cache_put(key, scores)
                return scores
            except Exception as exc:  # noqa: BLE001 - 重排失败必须可回落
                if attempt >= self.retries:
                    logger.warning("[rerank] 调用失败，回落 RRF 排序：%s", exc)
                    return None
                time.sleep(min(2.0, 0.5 * (2 ** attempt)))
        return None

    def health(self) -> tuple[str, str]:
        if not self.enabled:
            return "disabled", "RERANK_ENABLED=false"
        if not self.api_key:
            return "disabled", "未配置 rerank 凭据"
        ok = self.rerank("健康检查", ["制度条款示例"]) is not None
        return ("ok", f"{self.model}") if ok else ("down", "rerank 调用失败")

    # ---------- 内部 ----------

    def _client_or_create(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(timeout=self.timeout)
        return self._client

    @staticmethod
    def _cache_key(query: str, docs: Sequence[str]) -> str:
        digest = hashlib.sha256(
            "|".join([str(query)] + list(docs)).encode("utf-8")).hexdigest()
        return f"{query[:32]}|{digest}"

    def _cache_get(self, key: str) -> list[float] | None:
        if not self._cache_limit:
            return None
        with self._lock:
            value = self._cache.get(key)
            if value is not None:
                self._cache.move_to_end(key)
            return value

    def _cache_put(self, key: str, value: list[float]) -> None:
        if not self._cache_limit:
            return
        with self._lock:
            self._cache[key] = value
            self._cache.move_to_end(key)
            while len(self._cache) > self._cache_limit:
                self._cache.popitem(last=False)


_RERANKER: CloudReranker | None = None
_LOCK = threading.Lock()


def get_reranker() -> CloudReranker:
    global _RERANKER
    if _RERANKER is None:
        with _LOCK:
            if _RERANKER is None:
                _RERANKER = CloudReranker()
    return _RERANKER


def reset_reranker() -> None:
    global _RERANKER
    _RERANKER = None
