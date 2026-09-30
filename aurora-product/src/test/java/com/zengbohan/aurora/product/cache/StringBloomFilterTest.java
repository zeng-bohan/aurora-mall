package com.zengbohan.aurora.product.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringBloomFilterTest {

    @Test
    void noFalseNegativesForInsertedElements() {
        StringBloomFilter filter = new StringBloomFilter(10_000, 0.01);
        for (long i = 0; i < 10_000; i++) {
            filter.put(String.valueOf(i));
        }
        for (long i = 0; i < 10_000; i++) {
            assertThat(filter.mightContain(String.valueOf(i))).isTrue();
        }
    }

    @Test
    void neverContainsBeforeInsert() {
        StringBloomFilter filter = new StringBloomFilter(1_000, 0.01);
        assertThat(filter.mightContain("never-added")).isFalse();
    }

    @Test
    void concurrentPutsAreAllVisibleToConcurrentReaders() throws Exception {
        // 20 线程各 put 一万次（键空间交错），跑完后全部 must-contain（无假阴性是契约）
        StringBloomFilter filter = new StringBloomFilter(1_000_000, 0.01);
        int threads = 20;
        int perThread = 10_000;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        filter.put("key-" + (base + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        org.assertj.core.api.Assertions.assertThat(done.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        for (int i = 0; i < threads * perThread; i += 97) { // 步进抽样 20 万键的 ~1%
            assertThat(filter.mightContain("key-" + i)).isTrue();
        }
    }
}
