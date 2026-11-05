package com.hengce.aps.service.mrp;

import com.hengce.aps.common.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MrpSuggestionCache} 并发语义单元测试：single-flight、stale-while-revalidate、失效优先。
 */
class MrpSuggestionCacheTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID SANDBOX_ID = UUID.fromString("e9000000-0000-0000-0000-000000000401");

    private static final int HORIZON_DAYS = 30;

    private static final long WAIT_MILLIS = 5_000L;

    private MutableClock clock;

    private MrpSuggestionCache cache;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.ofEpochMilli(1_000_000L));
        cache = new MrpSuggestionCache(45L, 300L, WAIT_MILLIS, clock);
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Test
    void concurrentRequestsForSameKeyComputeOnlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> loader = () -> {
            loads.incrementAndGet();
            sleep(100L);
            return suggestions("RM-0001");
        };
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<MrpAnalysisResponse.MrpSuggestionView>>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return load(loader);
                }));
            }
            start.countDown();
            for (Future<List<MrpAnalysisResponse.MrpSuggestionView>> future : futures) {
                assertEquals("RM-0001", future.get(10, TimeUnit.SECONDS).get(0).materialCode());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loads.get(), "同一 key 的并发请求只应触发一次计算");
    }

    @Test
    void expiredEntryServesStaleAndRefreshesOnlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> initialLoader = () -> {
            loads.incrementAndGet();
            return suggestions("RM-OLD");
        };
        assertEquals("RM-OLD", load(initialLoader).get(0).materialCode());

        clock.advance(Duration.ofSeconds(46L));
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch allowRefresh = new CountDownLatch(1);
        Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> refreshLoader = () -> {
            loads.incrementAndGet();
            refreshStarted.countDown();
            awaitQuietly(allowRefresh);
            return suggestions("RM-NEW");
        };

        assertEquals("RM-OLD", load(refreshLoader).get(0).materialCode(),
                "TTL 到期后的首个请求应返回旧值");
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS), "应触发一次后台刷新");
        for (int index = 0; index < 10; index++) {
            assertEquals("RM-OLD", load(refreshLoader).get(0).materialCode(),
                    "刷新期间仍应返回旧值");
        }
        assertEquals(2, loads.get(), "旧值期间并发请求不得重复触发刷新");

        allowRefresh.countDown();
        sleep(100L);
        assertEquals("RM-NEW", load(() -> suggestions("RM-NEW")).get(0).materialCode(),
                "后台刷新完成后应返回新值");
        assertEquals(2, loads.get(), "后台刷新总共只应执行一次");
    }

    @Test
    void invalidateEnsuresNextRequestGetsNewValue() {
        AtomicInteger loads = new AtomicInteger();
        assertEquals("RM-OLD", load(() -> {
            loads.incrementAndGet();
            return suggestions("RM-OLD");
        }).get(0).materialCode());

        cache.invalidate(TENANT_ID);

        assertEquals("RM-NEW", load(() -> {
            loads.incrementAndGet();
            return suggestions("RM-NEW");
        }).get(0).materialCode(), "失效后的下一个请求必须重新计算，不得返回旧值");
        assertEquals(2, loads.get());
    }

    @Test
    void beyondStaleMaxWaitsForNewValueInsteadOfServingStale() {
        AtomicInteger loads = new AtomicInteger();
        assertEquals("RM-OLD", load(() -> {
            loads.incrementAndGet();
            return suggestions("RM-OLD");
        }).get(0).materialCode());

        // TTL 45s + 旧值上限 300s 后再多 1s：旧值已不可用，必须等待新值。
        clock.advance(Duration.ofSeconds(346L));
        assertEquals("RM-NEW", load(() -> {
            loads.incrementAndGet();
            return suggestions("RM-NEW");
        }).get(0).materialCode(), "超过旧值可用上限后不得返回旧值");
        assertEquals(2, loads.get());
    }

    @Test
    void waiterIsBoundedWhenComputationExceedsWaitTimeout() throws Exception {
        MrpSuggestionCache shortWait = new MrpSuggestionCache(45L, 300L, 100L, clock);
        CountDownLatch computationStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<MrpAnalysisResponse.MrpSuggestionView>> leader = pool.submit(() ->
                    shortWait.getOrLoad(TENANT_ID, SANDBOX_ID, HORIZON_DAYS, List.of(), () -> {
                        computationStarted.countDown();
                        sleep(500L);
                        return suggestions("RM-0001");
                    }));
            assertTrue(computationStarted.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            BizException exception = assertThrows(BizException.class, () ->
                    shortWait.getOrLoad(TENANT_ID, SANDBOX_ID, HORIZON_DAYS, List.of(),
                            () -> suggestions("RM-0001")));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertEquals(50300, exception.getErrorCode().getCode(), "超时应返回 50300");
            assertTrue(elapsedMillis < 500L, "等待必须有界，不得跟随计算无限挂起");
            leader.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            shortWait.shutdown();
        }
    }

    private List<MrpAnalysisResponse.MrpSuggestionView> load(
            Supplier<List<MrpAnalysisResponse.MrpSuggestionView>> loader) {
        return cache.getOrLoad(TENANT_ID, SANDBOX_ID, HORIZON_DAYS, List.of(), loader);
    }

    private static List<MrpAnalysisResponse.MrpSuggestionView> suggestions(String materialCode) {
        return List.of(new MrpAnalysisResponse.MrpSuggestionView(materialCode, materialCode,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneOffset.UTC),
                List.of("SO-001"),
                OffsetDateTime.of(2026, 11, 8, 0, 0, 0, 0, ZoneOffset.UTC),
                BigDecimal.ONE, null));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", exception);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", exception);
        }
    }

    /**
     * 可手动推进的时钟。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
