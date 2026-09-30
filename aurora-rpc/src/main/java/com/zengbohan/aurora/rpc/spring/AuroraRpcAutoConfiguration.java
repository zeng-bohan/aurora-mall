package com.zengbohan.aurora.rpc.spring;

import com.alibaba.nacos.api.exception.NacosException;
import com.zengbohan.aurora.rpc.lb.RandomLoadBalancer;
import com.zengbohan.aurora.rpc.lb.RoundRobinLoadBalancer;
import com.zengbohan.aurora.rpc.loadbalance.LoadBalancerProperties;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.proxy.RpcClientPool;
import com.zengbohan.aurora.rpc.proxy.RpcProxyFactory;
import com.zengbohan.aurora.rpc.registry.NacosRegistry;
import com.zengbohan.aurora.rpc.registry.ServiceDiscovery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * aurora-rpc 的 Spring 胶水（ADR-0008：核心零依赖，装配在此可选包）。
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

    @Bean
    @ConditionalOnMissingBean
    public RpcProxyFactory rpcProxyFactory(ServiceDiscovery discovery,
            com.zengbohan.aurora.rpc.lb.LoadBalancer loadBalancer,
            RpcClientPool clientPool, ProtocolCodec codec,
            @Value("${aurora.internal.secret}") String internalSecret) {
        return new RpcProxyFactory(discovery, loadBalancer, clientPool, codec, internalSecret);
    }
}
