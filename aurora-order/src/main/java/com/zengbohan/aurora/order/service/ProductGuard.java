package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.order.client.ProductClient;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.stereotype.Component;

// 两种下单策略共用的商品价格/状态守卫。
@Component
public class ProductGuard {

    private final ProductClient productClient;

    public ProductGuard(ProductClient productClient) {
        this.productClient = productClient;
    }

    public ProductSnapshot load(long skuId) {
        Result<ProductSnapshot> result = RemoteCall.invoke("商品", "sku " + skuId,
                () -> productClient.detail(skuId));
        if (result.code() == ErrorCode.NOT_FOUND.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品不存在");
        }
        ProductSnapshot snapshot = RemoteCall.data(result, "商品");
        if (snapshot.status() == null || snapshot.status() != ProductSnapshot.STATUS_ON_SALE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品已下架");
        }
        return snapshot;
    }
}
