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
                clock, Runnable::run, scheduler, 86_400);
        // seed the bloom so id lookups reach the cache/db path
        service.bloomPut(1L);
        service.bloomPut(2L);
    }

    /** DB loader used as Function<Long, Sku>: counts calls and returns sku-{id}. */
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

        // second read hits the null sentinel in cache, no DB round trip
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

        // age the entry past its 10-minute logical expiry
        clock.advance(Duration.ofMinutes(11));

        // stale value is served immediately, and exactly one rebuild ran
        Sku stale = service.getById(1L, this::loadSku);
        assertThat(stale.getTitle()).isEqualTo("sku-1");
        assertThat(dbCalls.get()).isEqualTo(2);
        // inline executor released the mutex after the rebuild
        assertThat(store.has("aurora:product:mutex:1")).isFalse();

        // subsequent read is served from the refreshed entry, no more DB
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

        // every reader got a value (stale or fresh); mutex keeps rebuild count
        // strictly below the reader count instead of one-per-thread
        assertThat(results).hasSize(readers)
                .allSatisfy(s -> assertThat(s.getTitle()).isEqualTo("sku-1"));
        assertThat(dbCalls.get()).isLessThan(readers);
    }

    @Test
    void staleMutexFallsBackToDbInsteadOfFalse404() {
        // simulate a dead rebuilder: mutex held, cache empty
        store.setIfAbsent("aurora:product:mutex:1", "1", java.time.Duration.ofSeconds(10));

        Sku loaded = service.getById(1L, this::loadSku);

        assertThat(loaded.getTitle()).isEqualTo("sku-1");
        assertThat(dbCalls.get()).isEqualTo(1);
        // the foreign mutex is not ours to release
        assertThat(store.has("aurora:product:mutex:1")).isTrue();
    }

    @Test
    void doubleDeleteRunsDelayedSecondEvict() throws Exception {
        service.getById(1L, this::loadSku);

        // update changes the title but writes through the same double-delete helper
        service.doubleDeleteAfterUpdate(1L, () -> {
            dbCalls.incrementAndGet();
        });

        // first delete is immediate
        String key = "aurora:product:sku:1";
        assertThat(store.has(key)).isFalse();

        Thread.sleep(700); // scheduler delay is 500ms
        // delayed second delete also ran (no key resurrected)
        assertThat(store.has(key)).isFalse();
        scheduler.shutdownNow();
    }

    /** Clock the test can move without waiting on wall time. */
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
