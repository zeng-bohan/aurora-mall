package com.zengbohan.aurora.cart.port;

import com.zengbohan.aurora.api.product.ProductRpcApi;
import com.zengbohan.aurora.cart.client.ProductClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双适配器装配条件（M3 T8 验收项）：{@code aurora.rpc.enabled} 开关切换
 * Feign/RPC 适配器，业务代码零改动。RPC 侧的代理工厂用 stub 顶替（不连 nacos）。
 */
class ProductAdapterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FeignSupport.class, RpcSupport.class,
                    FeignProductAdapter.class, RpcProductAdapter.class);

    /** Feign client 用 mock 顶替：装配测试的对象是适配器条件，不是 Feign 本身。 */
    @Configuration
    static class FeignSupport {
        @Bean
        ProductClient productClient() {
            return org.mockito.Mockito.mock(ProductClient.class);
        }
    }

    /** RPC 开启时的最小支撑：ProductRpcApi 代理用 stub（装配条件测试不连 nacos）。 */
    @Configuration
    static class RpcSupport {
        @Bean
        ProductRpcApi productRpcApi() {
            return new ProductRpcApi() {
                @Override
                public com.zengbohan.aurora.api.product.ProductSnapshot detail(long id) {
                    return null;
                }

                @Override
                public java.util.List<com.zengbohan.aurora.api.product.ProductSnapshot> batch(java.util.List<Long> ids) {
                    return java.util.List.of();
                }
            };
        }
    }

    @Test
    void feignAdapterByDefaultRpcAbsent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ProductPort.class);
            assertThat(context.getBean(ProductPort.class)).isInstanceOf(FeignProductAdapter.class);
            assertThat(context).doesNotHaveBean(RpcProductAdapter.class);
        });
    }

    @Test
    void rpcAdapterWhenEnabledFeignAbsent() {
        runner.withPropertyValues("aurora.rpc.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ProductPort.class);
            assertThat(context.getBean(ProductPort.class)).isInstanceOf(RpcProductAdapter.class);
            assertThat(context).doesNotHaveBean(FeignProductAdapter.class);
        });
    }

    @Test
    void explicitFalseKeepsFeign() {
        runner.withPropertyValues("aurora.rpc.enabled=false").run(context -> {
            assertThat(context.getBean(ProductPort.class)).isInstanceOf(FeignProductAdapter.class);
        });
    }

    @Test
    void autoConfigurationProvidesRpcStackWhenEnabled() {
        // aurora-rpc 的自动装配整栈在开关打开时到位（注册中心用 mock 顶替，不连 nacos）
        new ApplicationContextRunner()
                .withPropertyValues("aurora.rpc.enabled=true")
                .withConfiguration(AutoConfigurations.of(
                        com.zengbohan.aurora.rpc.spring.AuroraRpcAutoConfiguration.class))
                .withBean(com.zengbohan.aurora.rpc.registry.NacosRegistry.class,
                        () -> org.mockito.Mockito.mock(
                                com.zengbohan.aurora.rpc.registry.NacosRegistry.class))
                .run(context -> {
                    assertThat(context).hasBean("rpcProxyFactory");
                    assertThat(context).hasBean("serviceDiscovery");
                    assertThat(context).hasBean("rpcClientPool");
                });
    }
}
