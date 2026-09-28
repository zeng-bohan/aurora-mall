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
    void falsePositiveRateStaysWithinOrderOfMagnitude() {
        for (double target : new double[]{0.01, 0.001}) {
            long insertions = 10_000;
            StringBloomFilter filter = new StringBloomFilter(insertions, target);
            for (long i = 0; i < insertions; i++) {
                filter.put("sku-" + i);
            }
            int falsePositives = 0;
            int probes = 50_000;
            for (long i = insertions; i < insertions + probes; i++) {
                if (filter.mightContain("sku-" + i)) {
                    falsePositives++;
                }
            }
            double measured = falsePositives / (double) probes;
            // order of magnitude check: within 3x of the target, never above 0.1
            assertThat(measured)
                    .as("measured FPR %s vs target %s", measured, target)
                    .isLessThan(Math.min(target * 3, 0.1));
        }
    }

    @Test
    void neverContainsBeforeInsert() {
        StringBloomFilter filter = new StringBloomFilter(1_000, 0.01);
        assertThat(filter.mightContain("never-added")).isFalse();
    }
}
