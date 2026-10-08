package com.zengbohan.aurora.rpc.spring;

import com.alibaba.nacos.api.exception.NacosException;
import com.zengbohan.aurora.rpc.proxy.AuroraRpcService;
import com.zengbohan.aurora.rpc.proxy.RpcServiceExporter;
import com.zengbohan.aurora.rpc.lb.RandomLoadBalancer;
import com.zengbohan.aurora.rpc.lb.RoundRobinLoadBalancer;
import com.zengbohan.aurora.rpc.lb.LoadBalancerProperties;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.proxy.RpcClientPool;
import com.zengbohan.aurora.rpc.proxy.RpcProxyFactory;
import com.zengbohan.aurora.rpc.registry.NacosRegistry;
import com.zengbohan.aurora.rpc.registry.ServiceDiscovery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * aurora-rpc 的 Spring 胶水（核心零依赖，装配在此可选包）。
 * {@code aurora.rpc.enabled=true} 时装配注册发现 + 代理工厂全家桶；
 * 注册中心/负载均衡均可被使用方自己的 bean 覆盖（@ConditionalOnMissingBean）。
 */
@Configuration
@ConditionalOnProperty(name = "aurora.rpc.enabled", havingValue = "true")
@EnableConfigurationProperties({AuroraRpcProperties.class, LoadBalancerProperties.class})
public class AuroraRpcAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(NacosRegistry.class)
    public NacosRegistry nacosRegistry(AuroraRpcProperties properties) throws NacosException {
        return new NacosRegistry(properties.getRegistryAddr());
    }

    @Bean
    @ConditionalOnMissingBean
    public ServiceDiscovery serviceDiscovery(NacosRegistry registry) {
        return new ServiceDiscovery(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProtocolCodec rpcProtocolCodec() {
        return new ProtocolCodec();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RpcClientPool rpcClientPool(@Value("${aurora.internal.secret}") String internalSecret,
            AuroraRpcProperties properties) {
        return new RpcClientPool(internalSecret, properties.getTimeoutMillis());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.zengbohan.aurora.rpc.lb.LoadBalancer rpcLoadBalancer(
            LoadBalancerProperties loadBalancerProperties) {
        return "random".equalsIgnoreCase(loadBalancerProperties.getStrategy())
                ? new RandomLoadBalancer()
                : new RoundRobinLoadBalancer();
    }

    /**
     * 注解驱动导出：扫描全部 {@link AuroraRpcService} 标记的 bean，随上下文启动
     * 导出器（Netty 监听 properties.port + 注册中心上报），关闭时注销并停服。
     * 业务侧只需标注解，不再手写导出装配。
     * <p>
     * 纯消费方（没有任何 @AuroraRpcService）不启动监听器：这类服务只出站调用，
     * 开监听既无意义，又会让多个消费方抢同一个 {@code aurora.rpc.port}——
     * cart 与 product 同机部署时会 BindException（2026-10-08 实测）。
     */
    @Bean
    public SmartLifecycle rpcExporterLifecycle(ApplicationContext context, NacosRegistry registry,
            ProtocolCodec codec, AuroraRpcProperties properties,
            @Value("${aurora.internal.secret}") String internalSecret) {
        Map<String, Object> providers = context.getBeansWithAnnotation(AuroraRpcService.class);
        if (providers.isEmpty()) {
            // 消费方：不占端口、不注册，生命周期为空操作
            return new SmartLifecycle() {
                @Override
                public void start() {
                }

                @Override
                public void stop() {
                }

                @Override
                public boolean isRunning() {
                    return false;
                }
            };
        }
        RpcServiceExporter exporter = new RpcServiceExporter(registry, codec,
                properties.getHost(), internalSecret);
        providers.values()
                .forEach(bean -> {
                    // AOP/CGLIB 代理上直接 getAnnotation 拿不到声明类上的注解——
                    // @Idempotent 等切面生效后 provider bean 必为代理
                    AuroraRpcService annotation =
                            AopUtils.getTargetClass(bean).getAnnotation(AuroraRpcService.class);
                    if (annotation == null) {
                        throw new IllegalStateException(
                                "@AuroraRpcService missing on provider bean " + bean.getClass());
                    }
                    exporter.export(annotation.value(), bean);
                });
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                // start(int) 已把受检异常转为非受检（IllegalStateException）
                exporter.start(properties.getPort());
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                exporter.stop();
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                // 晚于普通 bean 启动、早于它们关闭
                return Integer.MAX_VALUE - 100;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public RpcProxyFactory rpcProxyFactory(ServiceDiscovery discovery,
            com.zengbohan.aurora.rpc.lb.LoadBalancer loadBalancer,
            RpcClientPool clientPool, ProtocolCodec codec,
            @Value("${aurora.internal.secret}") String internalSecret) {
        return new RpcProxyFactory(discovery, loadBalancer, clientPool, codec, internalSecret);
    }
}
