package com.zengbohan.aurora.cart.port;

import com.zengbohan.aurora.api.product.ProductSnapshot;

import java.util.List;

/**
 * 购物车对商品的领域端口（M3 T8）：业务只依赖本接口，传输形态
 * （OpenFeign / 手写 RPC）由适配器按 {@code aurora.rpc.enabled} 切换。
 * <p>
 * 语义与 HTTP 侧对齐：detail 不存在返回 null；batch 缺失的 id 不在结果中。
 */
public interface ProductPort {

    /** 商品详情；不存在返回 null。 */
    ProductSnapshot detail(long id);

    /** 批量查询；缺失的 id 不在结果中。 */
    List<ProductSnapshot> batch(List<Long> ids);
}
