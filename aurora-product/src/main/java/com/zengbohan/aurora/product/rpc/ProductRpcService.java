package com.zengbohan.aurora.product.rpc;

import com.zengbohan.aurora.api.product.ProductRpcApi;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.service.ProductQueryService;
import com.zengbohan.aurora.rpc.proxy.AuroraRpcService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品查询的 RPC 形态导出（M3 T8）：与 HTTP 形态共用
 * {@link ProductQueryService}（缓存三防同享），只是传输换手写 RPC。
 */
@Component
@AuroraRpcService(ProductRpcApi.class)
public class ProductRpcService implements ProductRpcApi {

    private final ProductQueryService queryService;

    public ProductRpcService(ProductQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public ProductSnapshot detail(long id) {
        try {
            Sku sku = queryService.detail(id);
            return sku == null ? null : toSnapshot(sku);
        } catch (BusinessException e) {
            // HTTP 形态的 miss 走异常→40400 信封；RPC 契约是返回 null，在此翻译
            if (e.getErrorCode().getCode() == ErrorCode.NOT_FOUND.getCode()) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public List<ProductSnapshot> batch(List<Long> ids) {
        return queryService.batch(ids).stream().map(ProductRpcService::toSnapshot).toList();
    }

    private static ProductSnapshot toSnapshot(Sku sku) {
        return new ProductSnapshot(sku.getId(), sku.getTitle(), sku.getPrice(),
                sku.getStock(), sku.getStatus());
    }
}
