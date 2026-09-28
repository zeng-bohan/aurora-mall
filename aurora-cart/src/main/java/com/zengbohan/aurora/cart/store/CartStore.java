package com.zengbohan.aurora.cart.store;

import java.util.Map;

/** Hash-shaped cart storage so service logic is testable without redis. */
public interface CartStore {

    /** Atomic quantity increment; creates the line when absent. */
    long increment(long userId, long skuId, long delta);

    /** Overwrite the quantity for an existing or new line. */
    void put(long userId, long skuId, long quantity);

    void remove(long userId, long skuId);

    void clear(long userId);

    Map<Long, Long> entries(long userId);
}
