"""V2 事件总线：请求级 context-var 队列（修 D6 并发串话）。

V1 events.py 是全局单 queue.Queue，并发请求间事件互相串话。
V2 改为：
- 每个请求绑定自己的队列（contextvars.ContextVar，天然随请求线程/协程隔离）；
- EventHub 提供跨端点订阅（POST 执行 / GET SSE 读取同一请求队列）；
- 事件 type 枚举与 V1 完全一致（agent_start/tool_start/tool_end/llm_start/
  agent_end/final/error），前端零改动。

多 worker 扩展（REDIS_URL 配置且 Redis 可达时自动启用）：
- 事件写入本地队列的同时广播到 Redis Stream（``mfg:ev:{request_id}``），
  SSE 端点从 Stream 读取，POST 查询与 GET SSE 落在不同 worker 也能收到事件；
- Stream 从 '0' 起读，events/open 预开后、SSE 订阅前产生的事件不丢失；
- Redis 不可用时完全退回进程内队列（单 worker 行为不变，fail-open）。
"""
from __future__ import annotations

import json
import logging
import queue
import threading
from contextvars import ContextVar, Token
from typing import Any, AsyncIterator, Callable, Optional

logger = logging.getLogger(__name__)

# 请求级事件队列：默认 None（未绑定时 push 静默丢弃，保证 CLI/测试无副作用）
_current_queue: ContextVar[Optional[queue.Queue]] = ContextVar(
    "mfg_v2_event_queue", default=None)

# Redis Stream 兜底过期（正常由 hub.close 主动清理；防异常路径残留）
_STREAM_TTL_S = 900
# events/open 预开标记过期（多 worker 下 SSE 判定请求已开）
_OPEN_TTL_S = 600


def _redis_client() -> Any | None:
    """复用 core.cache 的惰性 Redis 连接（未配置/不可用时返回 None）。"""
    from . import cache
    try:
        return cache.get_client()
    except Exception:  # noqa: BLE001 - 任何异常都退回进程内模式
        return None


def _stream_key(request_id: str) -> str:
    return f"mfg:ev:{request_id}"


def _open_key(request_id: str) -> str:
    return f"mfg:evopen:{request_id}"


def stream_mode() -> bool:
    """是否处于 Redis 广播模式（多 worker SSE 走 Redis Stream 读取）。"""
    return _redis_client() is not None


def sse_ready(request_id: str) -> bool:
    """多 worker 下判断请求是否已 open（Redis 标记或事件流任一存在）。

    用于 SSE 端点 404 判定：本地无队列但其它 worker 已 open 时不算 404。
    """
    r = _redis_client()
    if r is None:
        return False
    try:
        return bool(r.exists(_open_key(request_id), _stream_key(request_id)))
    except Exception:  # noqa: BLE001
        return False


def bind_queue() -> tuple[queue.Queue, Token]:
    """为当前请求上下文创建并绑定事件队列，返回 (队列, 解绑令牌)。"""
    q: queue.Queue = queue.Queue()
    token = _current_queue.set(q)
    return q, token


def bind_existing(q: queue.Queue) -> Token:
    """把 EventHub 中已存在的请求队列绑定到当前上下文（API 双端点模式）。"""
    return _current_queue.set(q)


def unbind(token: Token) -> None:
    """解绑当前请求上下文的事件队列。"""
    _current_queue.reset(token)


def push(event: dict) -> None:
    """向当前请求队列推送事件（V1 兼容签名；未绑定时丢弃）。"""
    q = _current_queue.get()
    if q is not None:
        q.put(event)


class BroadcastQueue(queue.Queue):
    """进程内队列 + Redis Stream 广播（多 worker SSE 的跨进程通道）。

    put() 同时写本地队列（单 worker 直读路径）与 Redis Stream（跨 worker
    SSE 读取路径）；Redis 写失败仅告警，不影响本地事件交付。
    """

    def __init__(self, request_id: str, redis_client: Any = None) -> None:
        super().__init__()
        self._rid = request_id
        self._redis = redis_client

    def put(self, item, block=True, timeout=None) -> None:  # type: ignore[override]
        super().put(item, block=block, timeout=timeout)
        r = self._redis
        if r is None:
            return
        try:
            payload = json.dumps(item, ensure_ascii=False, default=str)
            key = _stream_key(self._rid)
            r.xadd(key, {"d": payload})
            r.expire(key, _STREAM_TTL_S)
        except Exception as exc:  # noqa: BLE001 - 广播失败不影响本地事件
            logger.warning("[events] redis 广播失败（仅本地队列生效）: %s", exc)


class QueueSink:
    """事件 sink：把事件送入请求队列与可选回调（CLI 打印 / SSE 用）。

    修复 BUG-5：handler 在 ThreadPoolExecutor worker 线程执行时
    contextvar 不传播，经 contextvar 的 push 会静默丢事件。
    现在 sink 可**显式持有队列引用**（构造传入），不依赖 contextvar；
    未显式传入时回退 contextvar（兼容旧调用方式）。
    """

    def __init__(self, queue: Optional[queue.Queue] = None,
                 callback: Optional[Callable[[dict], None]] = None) -> None:
        self._queue = queue
        self._callback = callback

    def emit(self, event: dict) -> None:
        target = self._queue if self._queue is not None else _current_queue.get()
        if target is not None:
            target.put(event)
        if self._callback is not None:
            self._callback(event)


async def aread_stream(request_id: str, wait_s: float) -> AsyncIterator[dict]:
    """从 Redis Stream 依序读取事件（多 worker SSE 路径）。

    从 '0' 起读：SSE 订阅前已产生的事件（events/open 预开场景）不丢失。
    xread 以 200ms 阻塞读为节拍，避免忙轮询；异常时短暂退避后重试。
    """
    import asyncio

    r = _redis_client()
    if r is None:
        return
    key = _stream_key(request_id)
    loop = asyncio.get_event_loop()
    deadline = loop.time() + wait_s
    last = "0"
    while loop.time() < deadline:
        try:
            resp = await asyncio.to_thread(r.xread, {key: last}, count=50, block=200)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[events] xread 失败: %s", exc)
            await asyncio.sleep(0.2)
            continue
        if not resp:
            continue
        for _key, entries in resp:
            for entry_id, fields in entries:
                last = entry_id
                raw = fields.get("d")
                if not raw:
                    continue
                try:
                    yield json.loads(raw)
                except Exception:  # noqa: BLE001 - 脏数据跳过
                    continue


class EventHub:
    """跨端点事件枢纽：request_id → 请求队列（供 SSE 端点订阅）。

    修 D6：不再有全局单队列；每个请求一个队列，按 request_id 寻址，
    并发请求之间物理隔离。队列在读取完成后（或超过 TTL 数量上限）回收。
    Redis 可达时队列为 BroadcastQueue（本地 + Stream 双写），并在 Redis
    留 open 标记，供其它 worker 的 SSE 端点判定请求存在。
    """

    def __init__(self, max_pending: int = 256) -> None:
        self._queues: dict[str, queue.Queue] = {}
        self._lock = threading.Lock()
        self._max_pending = max_pending

    def open(self, request_id: str) -> queue.Queue:
        with self._lock:
            if request_id not in self._queues:
                self._evict_if_needed()
                self._queues[request_id] = self._new_queue(request_id)
            q = self._queues[request_id]
        self._mark_open(request_id)
        return q

    def _new_queue(self, request_id: str) -> queue.Queue:
        r = _redis_client()
        if r is not None:
            return BroadcastQueue(request_id, r)
        return queue.Queue()

    def _mark_open(self, request_id: str) -> None:
        r = _redis_client()
        if r is None:
            return
        try:
            r.set(_open_key(request_id), "1", ex=_OPEN_TTL_S)
        except Exception as exc:  # noqa: BLE001
            logger.debug("[events] open 标记写入失败: %s", exc)

    def get(self, request_id: str) -> Optional[queue.Queue]:
        with self._lock:
            return self._queues.get(request_id)

    def close(self, request_id: str) -> None:
        with self._lock:
            self._queues.pop(request_id, None)
        r = _redis_client()
        if r is None:
            return
        try:
            r.delete(_stream_key(request_id), _open_key(request_id))
        except Exception as exc:  # noqa: BLE001
            logger.debug("[events] 清理事件流失败: %s", exc)

    def _evict_if_needed(self) -> None:
        while len(self._queues) >= self._max_pending:
            oldest = next(iter(self._queues))
            self._queues.pop(oldest, None)


# 进程级单例（仅索引请求队列，不承载业务状态）
hub = EventHub()
