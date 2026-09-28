package com.zengbohan.aurora.product.cache;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Hand-written bloom filter (ADR-0004): FNV-1a 64-bit base hash with double
 * hashing for the k probes, optimal m/k derived from target insertions and
 * false-positive rate. No false negatives by construction.
 */
public final class StringBloomFilter {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final long[] bits;
    private final int hashFunctions;

    public StringBloomFilter(long expectedInsertions, double falsePositiveRate) {
        long m = optimalBits(expectedInsertions, falsePositiveRate);
        this.bits = new long[(int) ((m + 63) / 64)];
        this.hashFunctions = optimalHashFunctions(expectedInsertions, m);
    }

    public void put(String value) {
        long hash = fnv1a64(value);
        long h1 = hash;
        long h2 = (hash >>> 32) | (hash << 32);
        for (int i = 0; i < hashFunctions; i++) {
            long combined = Math.abs((h1 + i * h2)) % (bits.length * 64L);
            bits[(int) (combined >>> 6)] |= 1L << (combined & 63);
        }
    }

    public boolean mightContain(String value) {
        long hash = fnv1a64(value);
        long h1 = hash;
        long h2 = (hash >>> 32) | (hash << 32);
        for (int i = 0; i < hashFunctions; i++) {
            long combined = Math.abs((h1 + i * h2)) % (bits.length * 64L);
            if ((bits[(int) (combined >>> 6)] & (1L << (combined & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    static long optimalBits(long n, double p) {
        return (long) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    static int optimalHashFunctions(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    private static long fnv1a64(String value) {
        long hash = FNV_OFFSET;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    long bitCount() {
        return Arrays.stream(bits).map(Long::bitCount).sum();
    }
}
