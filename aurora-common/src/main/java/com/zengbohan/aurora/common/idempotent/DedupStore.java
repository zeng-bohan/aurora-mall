package com.zengbohan.aurora.common.idempotent;

/** Dedup-table guard backed by a (biz_type, biz_key) unique index. */
public interface DedupStore {

    /** Returns true when the record was inserted (first sight). */
    boolean tryInsert(String bizType, String bizKey);

    /** Removes a guard whose guarded call failed, so redelivery re-processes.
     *  Inside a caller-owned transaction the rollback already removes it; this
     *  covers the transaction-less case (bare @Idempotent(DB_DEDUP)). */
    void remove(String bizType, String bizKey);
}
