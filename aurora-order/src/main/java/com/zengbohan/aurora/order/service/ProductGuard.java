package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.order.client.ProductClient;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shared product price/status guard for both placement strategies. */
@Component
public class ProductGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductGuard.class);

    private final ProductClient productClient;

    public ProductGuard(ProductClient productClient) {
        this.productClient = productClient;
    }

    public ProductSnapshot load(long skuId) {
        Result<ProductSnapshot> result;
        try {
            result = productClient.detail(skuId);
        } catch (RuntimeException e) {
            log.warn("product detail call failed for sku {}", skuId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
        if (result.code() == ErrorCode.NOT_FOUND.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品不存在");
        }
        if (result.code() != ErrorCode.SUCCESS.getCode() || result.data() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
        if (result.data().status() == null || result.data().status() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品已下架");
        }
        return result.data();
    }
}
