package com.zengbohan.aurora.product.cache;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 手写布隆过滤器：FNV-1a 64 位基础哈希 + 双哈希生成 k 个探针，
 * m/k 由目标插入量与假阳性率推导出最优值。
 * <p>
 * 线程安全：位图用 {@link AtomicLongArray}，写走 CAS 循环（| 语义），
 * 读走 get——播种线程/请求线程/管理写线程并发正确。
 * <p>
 * 多实例局限：每 JVM 一份本地位图，多实例部署时其他实例对新建商品直接 404
 * （无假阴性承诺只在单实例成立）。Redis 位图共享版留给 M5 秒杀；
 * 当前以定期重播种收敛窗口。
 */
public final class StringBloomFilter {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final AtomicLongArray bits;
    private final int hashFunctions;

    public StringBloomFilter(long expectedInsertions, double falsePositiveRate) {
        long m = optimalBits(expectedInsertions, falsePositiveRate);
        this.bits = new AtomicLongArray((int) ((m + 63) / 64));
        this.hashFunctions = optimalHashFunctions(expectedInsertions, m);
    }

    public void put(String value) {
        long hash = fnv1a64(value);
        long h1 = hash;
        long h2 = (hash >>> 32) | (hash << 32);
        for (int i = 0; i < hashFunctions; i++) {
            long combined = Math.floorMod(h1 + i * h2, bits.length() * 64L);
            int word = (int) (combined >>> 6);
            long mask = 1L << (combined & 63);
            // CAS 循环实现无锁按位 OR
            bits.updateAndGet(word, old -> old | mask);
        }
    }

    public boolean mightContain(String value) {
        long hash = fnv1a64(value);
        long h1 = hash;
        long h2 = (hash >>> 32) | (hash << 32);
        for (int i = 0; i < hashFunctions; i++) {
            long combined = Math.floorMod(h1 + i * h2, bits.length() * 64L);
            if ((bits.get((int) (combined >>> 6)) & (1L << (combined & 63))) == 0) {
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
        long total = 0;
        for (int i = 0; i < bits.length(); i++) {
            total += Long.bitCount(bits.get(i));
        }
        return total;
    }
}
