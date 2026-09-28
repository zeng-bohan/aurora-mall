package com.zengbohan.aurora.cart.service;

import com.zengbohan.aurora.cart.client.ProductClient;
import com.zengbohan.aurora.cart.dto.CartItem;
import com.zengbohan.aurora.cart.dto.CartItemRequest;
import com.zengbohan.aurora.cart.dto.ProductSnapshot;
import com.zengbohan.aurora.cart.store.CartStore;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CartService {

    private final CartStore cartStore;
    private final ProductClient productClient;

    public CartService(CartStore cartStore, ProductClient productClient) {
        this.cartStore = cartStore;
        this.productClient = productClient;
    }

    /** Repeated adds of the same sku accumulate quantity (redis HINCRBY). */
    public void add(long userId, CartItemRequest request) {
        requireProductExists(request.skuId());
        cartStore.increment(userId, request.skuId(), request.quantity());
    }

    public void setQuantity(long userId, CartItemRequest request) {
        requireProductExists(request.skuId());
        cartStore.put(userId, request.skuId(), request.quantity());
    }

    public void remove(long userId, long skuId) {
        cartStore.remove(userId, skuId);
    }

    public void clear(long userId) {
        cartStore.clear(userId);
    }

    /** Line items with product snapshots joined via openfeign batch lookup. */
    public List<CartItem> view(long userId) {
        Map<Long, Long> lines = cartStore.entries(userId);
        if (lines.isEmpty()) {
            return List.of();
        }
        Result<List<ProductSnapshot>> batch =
                productClient.batch(new ArrayList<>(lines.keySet()));
        if (batch == null || batch.code() != ErrorCode.SUCCESS.getCode() || batch.data() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
        Map<Long, ProductSnapshot> snapshots = new java.util.HashMap<>();
        for (ProductSnapshot snapshot : batch.data()) {
            if (snapshot.id() != null) {
                snapshots.put(snapshot.id(), snapshot);
            }
        }
        List<CartItem> items = new ArrayList<>();
        lines.forEach((skuId, quantity) -> {
            ProductSnapshot snapshot = snapshots.get(skuId);
            if (snapshot != null) {
                items.add(new CartItem(skuId, Math.toIntExact(quantity),
                        snapshot.title(), snapshot.price(), snapshot.stock(), snapshot.status()));
            }
        });
        return items;
    }

    private void requireProductExists(long skuId) {
        Result<ProductSnapshot> result;
        try {
            result = productClient.detail(skuId);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
        if (result.code() == ErrorCode.SUCCESS.getCode() && result.data() != null) {
            return;
        }
        if (result.code() == ErrorCode.NOT_FOUND.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品不存在");
        }
        // any other envelope (system error upstream) is an outage, not a bad sku
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
    }
}
