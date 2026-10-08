package com.zengbohan.aurora.cart.port;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.cart.client.ProductClient;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OpenFeign 适配器（默认形态）：把 HTTP 信封语义翻译成端口语义。
 * {@code aurora.rpc.enabled} 缺省/false 时装配。
 */
@Component
@ConditionalOnProperty(name = "aurora.rpc.enabled", havingValue = "false", matchIfMissing = true)
public class FeignProductAdapter implements ProductPort {

    private final ProductClient productClient;

    public FeignProductAdapter(ProductClient productClient) {
        this.productClient = productClient;
    }

    @Override
    public ProductSnapshot detail(long id) {
        Result<ProductSnapshot> result = RemoteCall.invoke("商品", "sku " + id,
                () -> productClient.detail(id));
        if (result.code() == ErrorCode.NOT_FOUND.getCode()) {
            return null; // 业务缺失，翻译为端口语义
        }
        return RemoteCall.data(result, "商品");
    }

    @Override
    public List<ProductSnapshot> batch(List<Long> ids) {
        Result<List<ProductSnapshot>> batch = RemoteCall.invoke("商品", ids.size() + " 个 sku",
                () -> productClient.batch(ids));
        return RemoteCall.data(batch, "商品");
    }
}
