package com.hengce.aps.service.mrp;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code /mrp/suggestions} 结果缓存（按租户隔离，TTL 30–60 秒）。
 *
 * <p>缓存键 {@code (tenantId, sandboxId, horizonDays, orderNos)}；同租户内写订单、
 * 改库存、改动 BOM 时由对应服务主动调用 {@link #invalidate(UUID)}，不依赖 TTL 过期。</p>
 *
 * <p>并发语义（M3-QA-02 single-flight 修复）：</p>
 * <ul>
 *   <li><b>single-flight</b>：同一 key 同时只允许一次计算；其余线程等待同一个结果，
 *       等待上限由 {@code aps.mrp.suggestion-cache-wait-millis} 控制，超时返回 50300。</li>
 *   <li><b>stale-while-revalidate</b>：TTL 到期后先返回旧值，并由唯一的后台线程刷新；
 *       旧值最多可继续使用 {@code aps.mrp.suggestion-cache-stale-max-seconds} 秒，
 *       超过该窗口后请求必须等待新值（不再返回旧值）。</li>
 *   <li><b>失效优先</b>：{@link #invalidate(UUID)} 递增租户纪元并移除在途 flight，
 *       计算完成时若纪元已变化则丢弃结果，保证写库存后的下一个请求不会拿到旧值。</li>
 * </ul>
 */
@Component
public class MrpSuggestionCache {

    /**
     * 默认 TTL（秒）：落在 30–60 区间内。
     */
    static final long DEFAULT_TTL_SECONDS = 45L;

    /**
     * 默认旧值可用上限（秒）：TTL 到期后最多再服务 300 秒。
     */
    static final long DEFAULT_STALE_MAX_SECONDS = 300L;

    /**
     * 默认等待新值的上限（毫秒）。
     */
    static final long DEFAULT_WAIT_MILLIS = 10_000L;

    private static final Comparator<String> ORDER_NO_COMPARATOR = Comparator.nullsFirst(Comparator.naturalOrder());

    private static final UUID NULL_TENANT = new UUID(0L, 0L);

    private final long ttlSeconds;

    private final long staleMaxSeconds;

    private final long waitMillis;

    private final Clock clock;

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Key, CompletableFuture<List<MrpAnalysisResponse.MrpSuggestionView>>> flights
            = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, AtomicLong> tenantEpochs = new ConcurrentHashMap<>();

    private final ExecutorService refreshExecutor;

    /**
     * Spring 构造。
     *
     * @param ttlSeconds 缓存 TTL（秒），{@code aps.mrp.suggestion-cache-ttl-seconds}，默认 45
     * @param staleMaxSeconds TTL 到期后旧值最长可服务时长（秒），
     *        {@code aps.mrp.suggestion-cache-stale-max-seconds}，默认 300
     * @param waitMillis 等待新值的上限（毫秒），{@code aps.mrp.suggestion-cache-wait-millis}，默认 10000
     */
    @Autowired
    public MrpSuggestionCache(
            @Value("${aps.mrp.suggestion-cache-ttl-seconds:45}") long ttlSeconds,
            @Value("${aps.mrp.suggestion-cache-stale-max-seconds:300}") long staleMaxSeconds,
            @Value("${aps.mrp.suggestion-cache-wait-millis:10000}") long waitMillis) {
        this(ttlSeconds, staleMaxSeconds, waitMillis, Clock.systemUTC());
    }

    /**
     * 测试便捷构造：TTL 使用 45 秒，旧值上限与等待上限使用默认值。
     *
     * @param ttlSeconds 缓存 TTL（秒）
     */
    public MrpSuggestionCache(long ttlSeconds) {
        this(ttlSeconds, DEFAULT_STALE_MAX_SECONDS, DEFAULT_WAIT_MILLIS, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造，便于测试 TTL 与旧值上限。
     *
     * @param ttlSeconds 缓存 TTL（秒）
     * @param staleMaxSeconds TTL 到期后旧值最长可服务时长（秒）
     * @param waitMillis 等待新值的上限（毫秒）
     * @param clock 时钟
     */
    MrpSuggestionCache(long ttlSeconds, long staleMaxSeconds, long waitMillis, Clock clock) {
        this.ttlSeconds = Math.max(0L, ttlSeconds);
        this.staleMaxSeconds = Math.max(0L, staleMaxSeconds);
        this.waitMillis = Math.max(0L, waitMillis);
        this.clock = clock;
        this.refreshExecutor = Executors.newSingleThreadExecutor(daemonFactory());
    }

    /**
     * 读取缓存；命中返回结果，未命中或已超过旧值上限时以 single-flight 方式加载。
     *
     * @param tenantId 租户
     * @param sandboxId 场景
     * @param horizonDays 需求窗口天数
     * @param orderNos 订单号范围；空列表表示场景全量
     * @param loader 缓存未命中时的计算逻辑
     * @return 建议列表
     */
    public List<MrpAnalysisResponse.MrpSuggestionView> getOrLoad(
            UUID tenantId, UUID sandboxId, int horizonDays, List<String> orderNos,
            Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> loader) {
        Key key = key(tenantId, sandboxId, horizonDays, orderNos);
        long epoch = epochOf(tenantId);
        long now = clock.millis();
        Entry entry = entries.get(key);
        if (entry != null && entry.epoch() == epoch) {
            if (now < entry.expiresAtMillis()) {
                return entry.suggestions();
            }
            if (now < entry.staleDeadlineMillis()) {
                triggerRefresh(key, tenantId, epoch, loader);
                return entry.suggestions();
            }
        }
        return awaitLoad(key, tenantId, epoch, loader);
    }

    /**
     * 失效指定租户下的全部建议缓存（写订单 / 改库存 / 改动 BOM 后调用）。
     *
     * @param tenantId 租户
     */
    public void invalidate(UUID tenantId) {
        if (tenantId == null) {
            return;
        }
        tenantEpochs.computeIfAbsent(tenantId, key -> new AtomicLong()).incrementAndGet();
        entries.keySet().removeIf(key -> tenantId.equals(key.tenantId()));
        // 移除在途 flight，使失效后的请求立即开启新计算，而不是等待失效前的旧计算。
        flights.keySet().removeIf(key -> tenantId.equals(key.tenantId()));
    }

    /**
     * 清空全部缓存，仅供测试隔离使用。
     */
    public void clear() {
        entries.clear();
        flights.clear();
        tenantEpochs.clear();
    }

    /**
     * 停止后台刷新线程，供容器关闭与测试使用。
     */
    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    private List<MrpAnalysisResponse.MrpSuggestionView> awaitLoad(
            Key key, UUID tenantId, long epoch,
            Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> loader) {
        CompletableFuture<List<MrpAnalysisResponse.MrpSuggestionView>> created = new CompletableFuture<>();
        CompletableFuture<List<MrpAnalysisResponse.MrpSuggestionView>> existing = flights.putIfAbsent(key, created);
        if (existing == null) {
            return computeAndStore(key, tenantId, epoch, loader, created);
        }
        return await(existing);
    }

    private List<MrpAnalysisResponse.MrpSuggestionView> computeAndStore(
            Key key, UUID tenantId, long epoch,
            Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> loader,
            CompletableFuture<List<MrpAnalysisResponse.MrpSuggestionView>> flight) {
        try {
            List<MrpAnalysisResponse.MrpSuggestionView> loaded = loader.get();
            store(key, tenantId, epoch, loaded);
            flight.complete(loaded);
            return loaded;
        } catch (RuntimeException exception) {
            flight.completeExceptionally(exception);
            throw exception;
        } finally {
            flights.remove(key, flight);
        }
    }

    private List<MrpAnalysisResponse.MrpSuggestionView> await(
            CompletableFuture<List<MrpAnalysisResponse.MrpSuggestionView>> flight) {
        try {
            return flight.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "MRP 建议结果正在计算，等待超过 " + waitMillis + " ms，请稍后重试");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "等待 MRP 建议结果时被中断");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BizException(ErrorCode.INTERNAL_ERROR, "MRP 建议计算失败");
        }
    }

    private void triggerRefresh(Key key, UUID tenantId, long epoch,
                                Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> loader) {
        CompletableFuture<List<MrpAnalysisResponse.MrpSuggestionView>> created = new CompletableFuture<>();
        if (flights.putIfAbsent(key, created) != null) {
            return;
        }
        try {
            refreshExecutor.execute(() -> refresh(key, tenantId, epoch, loader, created));
        } catch (RejectedExecutionException exception) {
            flights.remove(key, created);
            created.completeExceptionally(exception);
        }
    }

    private void refresh(Key key, UUID tenantId, long epoch,
                         Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> loader,
                         CompletableFuture<List<MrpAnalysisResponse.MrpSuggestionView>> flight) {
        try {
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            }
            List<MrpAnalysisResponse.MrpSuggestionView> loaded = loader.get();
            store(key, tenantId, epoch, loaded);
            flight.complete(loaded);
        } catch (RuntimeException exception) {
            flight.completeExceptionally(exception);
        } finally {
            TenantContext.clear();
            flights.remove(key, flight);
        }
    }

    private void store(Key key, UUID tenantId, long epoch,
                       List<MrpAnalysisResponse.MrpSuggestionView> suggestions) {
        // 计算期间发生过写失效（纪元变化）时丢弃结果，避免把旧值写回缓存。
        if (epochOf(tenantId) != epoch) {
            return;
        }
        long now = clock.millis();
        long expiresAt = now + ttlSeconds * 1000L;
        entries.put(key, new Entry(suggestions, expiresAt, expiresAt + staleMaxSeconds * 1000L, epoch));
    }

    private long epochOf(UUID tenantId) {
        AtomicLong epoch = tenantEpochs.get(tenantId == null ? NULL_TENANT : tenantId);
        return epoch == null ? 0L : epoch.get();
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "mrp-suggestion-refresh");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Key key(UUID tenantId, UUID sandboxId, int horizonDays, List<String> orderNos) {
        List<String> normalized = orderNos == null || orderNos.isEmpty()
                ? List.of()
                : orderNos.stream().sorted(ORDER_NO_COMPARATOR).toList();
        return new Key(tenantId, sandboxId, horizonDays, normalized);
    }

    private record Key(UUID tenantId, UUID sandboxId, int horizonDays, List<String> orderNos) {
    }

    private record Entry(List<MrpAnalysisResponse.MrpSuggestionView> suggestions, long expiresAtMillis,
                         long staleDeadlineMillis, long epoch) {
    }
}
