package com.zengbohan.aurora.id;

import java.time.Clock;

/**
 * Classic 64-bit snowflake layout (41 timestamp / 10 worker / 12 sequence)
 * with a clock-rollback guard: a small backwards jump is waited out, a large
 * one is refused loudly instead of handing out duplicate ids.
 *
 * The main loop re-evaluates after every wait: while waiting for the next
 * millisecond other threads may have claimed ids there, so the sequence is
 * re-derived from the (possibly advanced) clock instead of reusing the
 * wrapped-around value.
 */
public class SnowflakeIdGenerator implements IdGenerator {

    /** 2025-01-01T00:00:00Z — ids stay positive for ~69 years from here. */
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
                    // this millisecond is exhausted: wait for the next one and
                    // re-derive — another thread may have claimed ids there
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
        // small drift: wait it out, then re-check from the top
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
