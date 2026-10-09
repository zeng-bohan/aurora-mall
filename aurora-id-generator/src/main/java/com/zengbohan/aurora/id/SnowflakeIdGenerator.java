package com.zengbohan.aurora.id;

import java.time.Clock;

/**
 * 经典 64 位雪花布局（41 位时间戳 / 10 位 worker / 12 位序列），带时钟回拨
 * 守卫：小幅回拨等待恢复，大幅回拨直接报错拒绝，而不是发出重复 id。
 *
 * 主循环在每次等待后重新求值：等待下一毫秒期间，其他线程可能已经占用了
 * 那一毫秒的 id，因此序列号要从（可能已推进的）时钟重新推导，
 * 而不是复用回绕后的旧值。
 */
public class SnowflakeIdGenerator implements IdGenerator {

    // 2025-01-01T00:00:00Z —— 从此刻起 id 在约 69 年内保持为正数。
    static final long EPOCH = 1735689600000L;
    static final long MAX_BACKWARD_MS = 5;

    static final int WORKER_BITS = 10;
    static final int SEQUENCE_BITS = 12;
    static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);
    static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private final Clock clock;
    private long lastTimestamp = -1L;
    private long sequence;

    /** 便捷构造：workerId 经 {@link SnowflakeWorkerIdAssigner#assign()} 自动解析。 */
    public SnowflakeIdGenerator() {
        this(SnowflakeWorkerIdAssigner.assign());
    }

    public SnowflakeIdGenerator(long workerId) {
        this(workerId, Clock.systemUTC());
    }

    public SnowflakeIdGenerator(long workerId, Clock clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be within [0, " + MAX_WORKER_ID + "]");
        }
        this.workerId = workerId;
        this.clock = clock;
    }

    @Override
    public synchronized long nextId() {
        long now;
        long sequenceValue;
        for (;;) {
            now = clock.millis();
            if (now < lastTimestamp) {
                now = handleRollback(now);
            }
            if (now == lastTimestamp) {
                sequenceValue = (sequence + 1) & SEQUENCE_MASK;
                if (sequenceValue == 0) {
                    // 本毫秒的序列已用尽：等待下一毫秒并重新推导——
                    // 其他线程可能已经占用了那里的 id
                    waitUntil(lastTimestamp + 1);
                    continue;
                }
            } else {
                sequenceValue = 0;
            }
            break;
        }
        lastTimestamp = now;
        sequence = sequenceValue;
        return ((now - EPOCH) << (WORKER_BITS + SEQUENCE_BITS)) | (workerId << SEQUENCE_BITS) | sequenceValue;
    }

    private long handleRollback(long now) {
        long backwardMs = lastTimestamp - now;
        if (backwardMs > MAX_BACKWARD_MS) {
            throw new IllegalStateException(
                    "clock moved backwards by " + backwardMs + "ms; refusing to generate ids");
        }
        // 小幅回拨：等过去，然后从头重新检查
        waitUntil(lastTimestamp);
        if (clock.millis() < lastTimestamp) {
            throw new IllegalStateException(
                    "clock still behind after waiting; refusing to generate ids");
        }
        return clock.millis();
    }

    private void waitUntil(long target) {
        long now = clock.millis();
        while (now < target) {
            try {
                wait(target - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for clock", e);
            }
            now = clock.millis();
        }
    }
}
