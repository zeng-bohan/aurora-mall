package com.zengbohan.aurora.common.idempotent;

/** Dedup-table guard backed by a (biz_type, biz_key) unique index. */
public interface DedupStore {

    /** Returns true when the record was inserted (first sight). */
    boolean tryInsert(String bizType, String bizKey);
}
