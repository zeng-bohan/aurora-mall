package com.zengbohan.aurora.api.product;

import java.util.List;

/**
 * 商品查询 RPC 服务契约（简化 RPC 的首个真实服务接口）。
 * <p>
 * 消费端（cart）经 {@link com.zengbohan.aurora.rpc.proxy.RpcProxyFactory} 生成代理，
 * 提供端（product）以 {@code @AuroraRpcService(ProductRpcApi.class)} 导出。
 * 语义：不存在返回 null（对齐 HTTP 侧 code=40400 的业务缺失语义）。
 */
public interface ProductRpcApi {

    // 商品详情；不存在返回 null。
    ProductSnapshot detail(long id);

    // 批量查询；缺失的 id 不在结果中。
    List<ProductSnapshot> batch(List<Long> ids);
}
