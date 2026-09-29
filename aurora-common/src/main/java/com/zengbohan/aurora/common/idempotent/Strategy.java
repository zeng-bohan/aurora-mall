package com.zengbohan.aurora.common.idempotent;

public enum Strategy {
    /** Redis SETNX guard: duplicate calls fail fast with DUPLICATE_REQUEST. */
    REDIS,
    /** Dedup-table guard: duplicate calls are skipped silently (MQ consumers). */
    DB_DEDUP
}
