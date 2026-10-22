package com.zengbohan.aurora.product.rpc;

import com.zengbohan.aurora.api.product.ProductRpcApi;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.product.service.ProductQueryService;
import com.zengbohan.aurora.rpc.proxy.AuroraRpcService;
import com.zengbohan.aurora.rpc.proxy.BusinessFailureException;
import org.springframework.stereotype.Component;

import java.util.List;

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
            return queryService.snapshot(id);
        } catch (BusinessException e) {
            if (e.getErrorCode().getCode() == ErrorCode.NOT_FOUND.getCode()) {
                return null;
            }
            throw new BusinessFailureException(e.getClass().getName(), e.getMessage());
        }
    }

    @Override
    public List<ProductSnapshot> batch(List<Long> ids) {
        return queryService.batchSnapshots(ids);
    }
}
