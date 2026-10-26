"""云端 Embedding 客户端（阿里云百炼 DashScope / 任意 OpenAI 兼容接口）。

设计要点
--------
- **唯一的 embedding 出口**：业务代码不直接调 SDK，换模型只改环境变量；
- **批量**：DashScope 单请求最多 10 条，``encode`` 自动分批；
- **重试**：仅对 429 / 5xx / 超时重试（指数退避），4xx 立即失败；
- **缓存**：查询侧 LRU，重复问题不重复计费；索引侧不走缓存；
- **fail-closed**：没有 key 或调用失败时抛 ``EmbeddingUnavailable``，
  上层必须显式降级（返回 degraded / 拒答），不允许静默返回零向量。

适配 Chroma
-----------
``ChromaEmbedding`` 实现了 Chroma 1.x 需要的 ``__call__ / name /
get_config / build_from_config``，因此 collection 不会退回默认 ONNX 模型
（那会在容器里偷偷下载模型并引入第二套维度）。
"""
from __future__ import annotations

import hashlib
import logging
import threading
import time
from collections import OrderedDict
from typing import Any, Iterable, Sequence

import httpx

from .config import settings

logger = logging.getLogger(__name__)

__all__ = [
    "EmbeddingUnavailable",
    "CloudEmbedder",
    "ChromaEmbedding",
    "get_embedder",
    "reset_embedder",
]

#: DashScope text-embedding-v3/v4 单请求硬上限（文档口径 10 条）
_MAX_BATCH = 10


class EmbeddingUnavailable(RuntimeError):
    """embedding 不可用（未配置 key / 鉴权失败 / 网络失败 / 维度不符）。"""


class CloudEmbedder:
    """OpenAI 兼容的云端 embedding 客户端（同步）。"""

    def __init__(self, *, provider: str | None = None, model: str | None = None,
                 dim: int | None = None, api_key: str | None = None,
                 base_url: str | None = None,
                 api_style: str | None = None,
                 batch_size: int | None = None,
                 timeout: float | None = None,
                 retries: int | None = None,
                 cache_size: int | None = None) -> None:
        self.provider = str(provider or getattr(settings, "EMBEDDING_PROVIDER", "dashscope")).strip().lower()
        self.model = str(model or getattr(settings, "EMBEDDING_MODEL",
                                          "qwen3.7-text-embedding-flash")).strip()
        self.dim = int(dim or getattr(settings, "EMBEDDING_DIM", 1024))
        self.api_key = str(api_key if api_key is not None
                           else getattr(settings, "DASHSCOPE_API_KEY", "") or "").strip()
        self.base_url = str(base_url or getattr(settings, "DASHSCOPE_BASE_URL", "")
                            or "").strip().rstrip("/")
        self.api_style = str(api_style or getattr(settings, "EMBEDDING_API_STYLE", "dashscope")
                             or "dashscope").strip().lower()
        batch = int(batch_size or getattr(settings, "EMBEDDING_BATCH_SIZE", 10))
        self.batch_size = max(1, min(batch, _MAX_BATCH))
        self.timeout = float(timeout or getattr(settings, "EMBEDDING_TIMEOUT_S", 15))
        self.retries = max(0, int(retries if retries is not None
                                  else getattr(settings, "EMBEDDING_RETRIES", 3)))
        cache_size = int(cache_size if cache_size is not None
                         else getattr(settings, "EMBEDDING_CACHE_SIZE", 2048))
        self._cache: OrderedDict[str, list[float]] = OrderedDict()
        self._cache_limit = max(0, cache_size)
        self._lock = threading.Lock()
        self._client: httpx.Client | None = None
        self._fail_streak = 0
        self._open_until = 0.0

    # ---------- 对外 ----------

    @property
    def endpoint(self) -> str:
        base = (self.base_url
                or "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/")
        if self.api_style == "openai":
            return base + "/embeddings"
        # DashScope 原生接口（qwen3.7-text-embedding-flash 等私有 MaaS 只支持这一种）
        return base + "/services/embeddings/text-embedding/text-embedding"

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    def encode(self, texts: Sequence[str]) -> list[list[float]]:
        """按输入顺序返回向量；空输入返回空列表。"""
        items = [str(t or "") for t in texts]
        if not items:
            return []
        if not self.configured:
            raise EmbeddingUnavailable(
                "未配置 embedding 凭据：请在 .env 填 QW_KEY 或 DASHSCOPE_API_KEY")

        out: list[list[float] | None] = [None] * len(items)
        pending: list[tuple[int, str]] = []
        for idx, text in enumerate(items):
            cached = self._cache_get(text)
            if cached is not None:
                out[idx] = cached
            else:
                pending.append((idx, text))

        for start in range(0, len(pending), self.batch_size):
            part = pending[start:start + self.batch_size]
            vectors = self._request([t for _, t in part])
            if len(vectors) != len(part):
                raise EmbeddingUnavailable(
                    f"embedding 返回条数不符：请求 {len(part)}，返回 {len(vectors)}")
            for (idx, text), vec in zip(part, vectors):
                if len(vec) != self.dim:
                    raise EmbeddingUnavailable(
                        f"embedding 维度不符：期望 {self.dim}，实际 {len(vec)}"
                        f"（模型 {self.model}）")
                out[idx] = vec
                self._cache_put(text, vec)

        if any(v is None for v in out):
            raise EmbeddingUnavailable("embedding 结果不完整")
        return [list(v or []) for v in out]

    def encode_one(self, text: str) -> list[float]:
        return self.encode([text])[0]

    def health(self) -> tuple[str, str]:
        """轻量探针：未配置 → disabled；调用失败 → down。"""
        if not self.configured:
            return "disabled", "未配置 QW_KEY / DASHSCOPE_API_KEY（检索不可用）"
        if time.monotonic() < self._open_until:
            return "down", "熔断中（连续失败后短暂停止调用）"
        try:
            self.encode_one("健康检查")
        except Exception as exc:  # noqa: BLE001 - 健康检查不允许外抛
            return "down", f"{type(exc).__name__}: {str(exc)[:120]}"
        return "ok", f"{self.model} dim={self.dim}"

    # ---------- HTTP ----------

    def _client_or_create(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(timeout=self.timeout)
        return self._client

    def _request(self, texts: list[str]) -> list[list[float]]:
        if time.monotonic() < self._open_until:
            raise EmbeddingUnavailable("embedding 服务处于熔断窗口，稍后重试")
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        last_exc: Exception | None = None
        for attempt in range(self.retries + 1):
            try:
                if self.api_style == "openai":
                    vectors = self._call_openai(texts, headers)
                else:
                    vectors = self._call_dashscope(texts, headers)
                self._fail_streak = 0
                return vectors
            except EmbeddingUnavailable:
                raise
            except Exception as exc:  # noqa: BLE001
                last_exc = exc
                self._fail_streak += 1
                if attempt >= self.retries:
                    break
                sleep_s = min(8.0, 0.5 * (2 ** attempt))
                logger.warning("[embedding] 第 %s 次重试（%.1fs 后）：%s",
                               attempt + 1, sleep_s, exc)
                time.sleep(sleep_s)
        if self._fail_streak >= 5:
            self._open_until = time.monotonic() + 30
            self._fail_streak = 0
        raise EmbeddingUnavailable(f"embedding 调用失败：{last_exc}")

    def _post(self, headers: dict[str, str], payload: dict[str, Any]) -> Any:
        """发请求并做状态码分级：429/5xx 触发重试，4xx 直接失败。"""
        resp = self._client_or_create().post(
            self.endpoint, json=payload, headers=headers)
        if resp.status_code in (429, 500, 502, 503, 504):
            raise httpx.HTTPStatusError(
                f"HTTP {resp.status_code}: {resp.text[:200]}",
                request=resp.request, response=resp)
        if resp.status_code >= 400:
            raise EmbeddingUnavailable(
                f"embedding 请求失败 HTTP {resp.status_code}: {resp.text[:300]}")
        return resp.json()

    def _call_openai(self, texts: list[str],
                     headers: dict[str, str]) -> list[list[float]]:
        """/embeddings（OpenAI 兼容模式）。"""
        payload: dict[str, Any] = {
            "model": self.model,
            "input": texts,
            "encoding_format": "float",
            "dimensions": self.dim,
        }
        data = self._post(headers, payload)
        rows = sorted(data.get("data") or [],
                      key=lambda r: int(r.get("index", 0)))
        return [list(map(float, r.get("embedding") or [])) for r in rows]

    def _call_dashscope(self, texts: list[str],
                        headers: dict[str, str]) -> list[list[float]]:
        """DashScope 原生接口（qwen3.7-text-embedding-flash 等）。

        请求：``{"model": ..., "input": {"texts": [...]}}``
        响应：``{"output": {"embeddings": [{"embedding": [...], "text_index": 0}]}}``
        """
        payload = {"model": self.model, "input": {"texts": texts}}
        data = self._post(headers, payload)
        if isinstance(data, dict) and data.get("code"):
            raise EmbeddingUnavailable(
                f"embedding 接口返回错误 {data.get('code')}: {data.get('message')}")
        rows = (data.get("output") or {}).get("embeddings") or []
        rows = sorted(rows, key=lambda r: int(r.get("text_index", 0)))
        return [list(map(float, r.get("embedding") or [])) for r in rows]

    # ---------- 缓存 ----------

    def _cache_key(self, text: str) -> str:
        return hashlib.sha256(f"{self.model}|{text}".encode("utf-8")).hexdigest()

    def _cache_get(self, text: str) -> list[float] | None:
        if not self._cache_limit:
            return None
        key = self._cache_key(text)
        with self._lock:
            vec = self._cache.get(key)
            if vec is not None:
                self._cache.move_to_end(key)
            return vec

    def _cache_put(self, text: str, vec: list[float]) -> None:
        if not self._cache_limit:
            return
        key = self._cache_key(text)
        with self._lock:
            self._cache[key] = vec
            self._cache.move_to_end(key)
            while len(self._cache) > self._cache_limit:
                self._cache.popitem(last=False)


class ChromaEmbedding:
    """Chroma 1.x 的 EmbeddingFunction 适配器（不触发默认 ONNX 模型）。"""

    def __init__(self, embedder: CloudEmbedder | None = None) -> None:
        self._embedder = embedder or get_embedder()

    def __call__(self, input: Iterable[str]) -> list[list[float]]:  # noqa: A002
        return self._embedder.encode([str(x) for x in input])

    def embed_query(self, input: Iterable[str]) -> list[list[float]]:  # noqa: A002
        return self(input)

    @staticmethod
    def name() -> str:
        return "mfg-cloud-embedding"

    def get_config(self) -> dict[str, Any]:
        return {"model": self._embedder.model, "dim": self._embedder.dim}

    @staticmethod
    def build_from_config(config: dict[str, Any]) -> "ChromaEmbedding":
        model = (config or {}).get("model")
        dim = (config or {}).get("dim")
        return ChromaEmbedding(CloudEmbedder(model=model, dim=dim))

    def default_space(self) -> str:
        return "cosine"

    def supported_spaces(self) -> list[str]:
        return ["cosine"]


_EMBEDDER: CloudEmbedder | None = None
_LOCK = threading.Lock()


def get_embedder() -> CloudEmbedder:
    """进程级单例（连接池与 LRU 缓存共享）。"""
    global _EMBEDDER
    if _EMBEDDER is None:
        with _LOCK:
            if _EMBEDDER is None:
                _EMBEDDER = CloudEmbedder()
    return _EMBEDDER


def reset_embedder() -> None:
    """测试注入/切换配置后重置单例。"""
    global _EMBEDDER
    _EMBEDDER = None
