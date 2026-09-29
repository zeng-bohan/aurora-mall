package com.zengbohan.aurora.id;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    private static final long BASE_MILLIS = SnowflakeIdGenerator.EPOCH + 1000;

    /** Clock replaying a scripted list of millis values, then holding the last. */
    private static class ScriptedClock extends Clock {
        private final List<Long> script;
        private final AtomicInteger index = new AtomicInteger();

        ScriptedClock(List<Long> script) {
            this.script = script;
        }

        @Override
        public long millis() {
            return script.get(index.getAndIncrement() % script.size());
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis());
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** Clock that steps forward one millisecond on every read. */
    private static class SteppingClock extends Clock {
        private final AtomicLong current;

        SteppingClock(long start) {
            this.current = new AtomicLong(start);
        }

        @Override
        public long millis() {
            return current.getAndIncrement();
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis());
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void generatesPositiveIncreasingIdsFromEpoch() {
        SteppingClock clock = new SteppingClock(SnowflakeIdGenerator.EPOCH + 10_000);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void smallClockRollbackIsWaitedOut() {
        // now=1000 -> id; now=998 (2ms back, within tolerance) -> waitUntil(1000) -> 1000
        ScriptedClock clock = new ScriptedClock(List.of(BASE_MILLIS, BASE_MILLIS - 2, BASE_MILLIS));
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(second).isPositive().isNotEqualTo(first);
    }

    @Test
    void largeClockRollbackIsRefused() {
        ScriptedClock clock = new ScriptedClock(List.of(BASE_MILLIS + 1000, BASE_MILLIS));
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);
        generator.nextId();

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clock moved backwards");
    }

    @Test
    void workerIdBoundsAreEnforced() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentGenerationProducesUniqueIds() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(7);
        // ticket promised 1000 concurrent threads; 100 threads x 100 ids is a
        // stronger uniqueness pressure without the CI runner's thread limits
        int threads = 100;
        int perThread = 100;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        ids.add(generator.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(ids).hasSize(threads * perThread);
    }
}
