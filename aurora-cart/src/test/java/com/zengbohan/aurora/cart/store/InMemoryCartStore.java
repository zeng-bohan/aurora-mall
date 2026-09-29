package com.zengbohan.aurora.cart.store;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory CartStore for unit tests. */
public class InMemoryCartStore implements CartStore {

    private final Map<Long, Map<Long, Long>> carts = new HashMap<>();

    @Override
    public long increment(long userId, long skuId, long delta) {
        Map<Long, Long> cart = carts.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        long next = cart.getOrDefault(skuId, 0L) + delta;
        if (next <= 0) {
            // mirrors the redis lua: a line at or below zero leaves the cart
            cart.remove(skuId);
            return 0L;
        }
        cart.put(skuId, next);
        return next;
    }

    @Override
    public void put(long userId, long skuId, long quantity) {
        Map<Long, Long> cart = carts.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        if (quantity <= 0) {
            cart.remove(skuId);
        } else {
            cart.put(skuId, quantity);
        }
    }

    @Override
    public void remove(long userId, long skuId) {
        Map<Long, Long> cart = carts.get(userId);
        if (cart != null) {
            cart.remove(skuId);
        }
    }

    @Override
    public void clear(long userId) {
        carts.remove(userId);
    }

    @Override
    public Map<Long, Long> entries(long userId) {
        return new LinkedHashMap<>(carts.getOrDefault(userId, new LinkedHashMap<>()));
    }
}
