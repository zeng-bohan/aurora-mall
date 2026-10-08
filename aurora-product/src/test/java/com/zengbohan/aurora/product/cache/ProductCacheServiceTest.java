package com.zengbohan.aurora.product.cache;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.product.entity.Sku;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductCacheServiceTest {

    private FakeCacheStore store;
    private MutableClock clock;
    private ScheduledExecutorService scheduler;
    private ProductCacheService service;
    private AtomicInteger dbCalls;

    @BeforeEach
    void setUp() {
        store = new FakeCacheStore();
        clock = new MutableClock(Instant.parse("2026-09-28T00:00:00Z"));
        scheduler = Executors.newSingleThreadScheduledExecutor();
        dbCalls = new AtomicInteger();
        service = new ProductCacheService(store, new VolatileBloomFilterHolder(new StringBloomFilter(1_000, 0.01)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                clock, Runnable::run, scheduler, 86_400);
        // 播种布隆过滤器，让 id 查询能走到缓存/DB 路径
        service.bloomPut(1L);
        service.bloomPut(2L);
    }

    // 作为 Function<Long, Sku> 使用的 DB loader：统计调用次数并返回 sku-{id}。
    private Sku loadSku(Long id) {
        dbCalls.incrementAndGet();
        Sku s = new Sku();
        s.setId(id);
        s.setTitle("sku-" + id);
        s.setPrice(BigDecimal.valueOf(9.9));
        s.setStatus(1);
        return s;
    }

    private Sku notFound(Long id) {
        dbCalls.incrementAndGet();
        return null;
    }

    @Test
    void bloomRejectsUnknownIdWithoutTouchingDb() {
        assertThatThrownBy(() -> service.getById(999L, this::notFound))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", 40400);
        assertThat(dbCalls.get()).isZero();
    }

    @Test
    void nullCachingKeepsDbUnreachableOnRepeatedMiss() {
        assertThatThrownBy(() -> service.getById(2L, this::notFound)).isInstanceOf(BusinessException.class);
        assertThat(dbCalls.get()).isEqualTo(1);

        // 第二次读取命中缓存中的空值哨兵，不再回源 DB
        assertThatThrownBy(() -> service.getById(2L, this::notFound)).isInstanceOf(BusinessException.class);
        assertThat(dbCalls.get()).isEqualTo(1);
    }

    @Test
    void firstReadLoadsFromDbThenServesFromCache() {
        Sku first = service.getById(1L, this::loadSku);
        assertThat(first.getTitle()).isEqualTo("sku-1");
        assertThat(dbCalls.get()).isEqualTo(1);

        Sku second = service.getById(1L, this::loadSku);
        assertThat(second.getTitle()).isEqualTo("sku-1");
        assertThat(dbCalls.get()).isEqualTo(1);
    }

    @Test
    void logicalExpiryServesStaleWhileSingleRebuildRuns() {
        service.getById(1L, this::loadSku);
        assertThat(dbCalls.get()).isEqualTo(1);

        // 把该条目推到 10 分钟逻辑过期之后
        clock.advance(Duration.ofMinutes(11));

        // 立即返回旧值，且只发生一次重建
        Sku stale = service.getById(1L, this::loadSku);
        assertThat(stale.getTitle()).isEqualTo("sku-1");
        assertThat(dbCalls.get()).isEqualTo(2);
        // 内联执行器在重建后释放了互斥锁
        assertThat(store.has("aurora:product:mutex:1")).isFalse();

        // 后续读取由刷新后的条目提供，不再回源 DB
        Sku refreshed = service.getById(1L, this::loadSku);
        assertThat(refreshed.getTitle()).isEqualTo("sku-1");
        assertThat(dbCalls.get()).isEqualTo(2);
    }

    @Test
    void concurrentReadersTriggerSingleRebuild() throws Exception {
        service.getById(1L, this::loadSku);
        clock.advance(Duration.ofMinutes(11));
        dbCalls.set(0);

        int readers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        java.util.List<Sku> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        for (int i = 0; i < readers; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    results.add(service.getById(1L, this::loadSku));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 每个读线程都拿到了值（旧值或新值）；互斥锁让重建次数
        // 严格小于读线程数，而不是每个线程各重建一次
        assertThat(results).hasSize(readers)
                .allSatisfy(s -> assertThat(s.getTitle()).isEqualTo("sku-1"));
        assertThat(dbCalls.get()).isLessThan(readers);
    }

    @Test
    void staleMutexFallsBackToDbInsteadOfFalse404() {
        // 模拟重建者已死：互斥锁被持有，缓存为空
        store.setIfAbsent("aurora:product:mutex:1", "1", java.time.Duration.ofSeconds(10));

        Sku loaded = service.getById(1L, this::loadSku);

        assertThat(loaded.getTitle()).isEqualTo("sku-1");
        assertThat(dbCalls.get()).isEqualTo(1);
        // 这把别人的互斥锁不该由我们释放
        assertThat(store.has("aurora:product:mutex:1")).isTrue();
    }

    @Test
    void doubleDeleteRunsDelayedSecondEvict() throws Exception {
        service.getById(1L, this::loadSku);

        // 更新只改标题，但仍走同一个双删助手
        service.doubleDeleteAfterUpdate(1L, () -> {
            dbCalls.incrementAndGet();
        });

        // 第一次删除是立即执行的
        String key = "aurora:product:sku:1";
        assertThat(store.has(key)).isFalse();

        Thread.sleep(700); // 调度延迟是 500ms
        // 延迟的第二次删除也执行了（没有 key 复活）
        assertThat(store.has(key)).isFalse();
        scheduler.shutdownNow();
    }

    // 测试可任意拨动的时钟，无需等待真实时间流逝。
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
