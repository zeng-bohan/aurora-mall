package com.zengbohan.aurora.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

import com.zengbohan.aurora.id.SegmentLoader.Segment;

class SegmentIdGeneratorTest {

    /** Deterministic loader serving queued segments and counting calls. */
    private static class FakeLoader implements SegmentLoader {
        private final ArrayDeque<Segment> segments = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        FakeLoader(SegmentLoader.Segment... segments) {
            for (Segment s : segments) {
                this.segments.add(s);
            }
        }

        @Override
        public Segment next(String bizTag) {
            calls.incrementAndGet();
            Segment s = segments.poll();
            if (s == null) {
                throw new IllegalStateException("no more segments");
            }
            return s;
        }
    }

    @Test
    void handsOutIdsInsideFirstSegment() {
        SegmentIdGenerator generator = new SegmentIdGenerator("order",
                new FakeLoader(new SegmentLoader.Segment(100, 100)));

        assertThat(generator.nextId()).isEqualTo(1);
        assertThat(generator.nextId()).isEqualTo(2);
    }

    @Test
    void switchesSegmentsTransparentlyWithPrefetch() {
        FakeLoader loader = new FakeLoader(
                new SegmentLoader.Segment(100, 100),
                new SegmentLoader.Segment(200, 100),
                new SegmentLoader.Segment(300, 100));
        SegmentIdGenerator generator = new SegmentIdGenerator("order", loader);

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 250; i++) {
            ids.add(generator.nextId());
        }

        // 1..250 across three segment boundaries, zero gaps or duplicates
        assertThat(ids).hasSize(250).contains(1L, 100L, 101L, 200L, 201L, 250L);
        // initial load + prefetch at 60% + prefetch again at exhaustion, no fourth load
        assertThat(loader.calls.get()).isEqualTo(3);
    }

    @Test
    void concurrentGenerationIsUniqueAcrossSegmentSwitches() throws Exception {
        FakeLoader loader = new FakeLoader(
                new SegmentLoader.Segment(1_000, 1_000),
                new SegmentLoader.Segment(2_000, 1_000),
                new SegmentLoader.Segment(10_000, 8_000));
        SegmentIdGenerator generator = new SegmentIdGenerator("order", loader);

        int threads = 20;
        int perThread = 400;
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
