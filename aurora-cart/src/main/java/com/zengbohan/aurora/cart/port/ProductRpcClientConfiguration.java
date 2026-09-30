package com.zengbohan.aurora.cart.port;

import com.zengbohan.aurora.api.product.ProductRpcApi;
import com.zengbohan.aurora.rpc.proxy.RpcProxyFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RPC 开启时的消费端代理 bean：ProductRpcApi 经工厂生成动态代理。
 * （aurora-rpc 自动装配提供工厂本体，按业务接口生成代理放在消费侧。）
 */
@Configuration
@ConditionalOnProperty(name = "aurora.rpc.enabled", havingValue = "true")
public class ProductRpcClientConfiguration {

    @Bean
    public ProductRpcApi productRpcApi(RpcProxyFactory factory) {
        return factory.create(ProductRpcApi.class);
    }
}
