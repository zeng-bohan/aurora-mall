package com.zengbohan.aurora.cart.service;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.cart.dto.CartItem;
import com.zengbohan.aurora.cart.dto.CartItemRequest;
import com.zengbohan.aurora.cart.port.ProductPort;
import com.zengbohan.aurora.cart.store.CartStore;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CartService {

    private final CartStore cartStore;
    private final ProductPort productPort;

    public CartService(CartStore cartStore, ProductPort productPort) {
        this.cartStore = cartStore;
        this.productPort = productPort;
    }

    // 重复添加同一 sku 会累加数量（redis HINCRBY）。
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

    // 行项目与商品快照，通过端口批量查询拼装。
    public List<CartItem> view(long userId) {
        Map<Long, Long> lines = cartStore.entries(userId);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<ProductSnapshot> batch = productPort.batch(new ArrayList<>(lines.keySet()));
        Map<Long, ProductSnapshot> snapshots = new java.util.HashMap<>();
        for (ProductSnapshot snapshot : batch) {
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
        // 端口语义：null=不存在；服务不可用由适配器翻译成 SYSTEM_ERROR 业务异常
        ProductSnapshot snapshot = productPort.detail(skuId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品不存在");
        }
    }
}
