package com.zengbohan.aurora.cart.store;

import java.util.Map;

// 哈希结构的购物车存储，让服务层逻辑无需 redis 也能测试。
public interface CartStore {

    // 原子累加数量；行不存在时创建。
    long increment(long userId, long skuId, long delta);

    // 覆盖已有或新建行的数量。
    void put(long userId, long skuId, long quantity);

    void remove(long userId, long skuId);

    void clear(long userId);

    Map<Long, Long> entries(long userId);
}
