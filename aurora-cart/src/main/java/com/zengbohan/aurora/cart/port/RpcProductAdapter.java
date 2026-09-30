package com.zengbohan.aurora.cart.port;

import com.zengbohan.aurora.api.product.ProductRpcApi;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 手写 RPC 适配器（切换形态）：经 aurora-rpc 代理调用 product 的
 * {@link ProductRpcApi}。{@code aurora.rpc.enabled=true} 时装配——
 * 业务代码零改动即可换传输（M3 T8 验收项）。
 * <p>
 * RPC 侧不可用/超时/熔断统一翻译为与 Feign 侧一致的"商品服务不可用"业务异常，
 * 上层语义在两种传输下完全一致。
 */
@Component
@ConditionalOnProperty(name = "aurora.rpc.enabled", havingValue = "true")
public class RpcProductAdapter implements ProductPort {

    private static final Logger log = LoggerFactory.getLogger(RpcProductAdapter.class);

    private final ProductRpcApi rpcApi;

    public RpcProductAdapter(ProductRpcApi rpcApi) {
        this.rpcApi = rpcApi;
    }

    @Override
    public ProductSnapshot detail(long id) {
        try {
            return rpcApi.detail(id); // 不存在即 null，与端口语义天然一致
        } catch (RuntimeException e) {
            log.warn("product rpc detail failed for id {}", id, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
    }

    @Override
    public List<ProductSnapshot> batch(List<Long> ids) {
        try {
            List<ProductSnapshot> snapshots = rpcApi.batch(ids);
            return snapshots == null ? List.of() : snapshots;
        } catch (RuntimeException e) {
            log.warn("product rpc batch failed for {} ids", ids.size(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
        }
    }
}
