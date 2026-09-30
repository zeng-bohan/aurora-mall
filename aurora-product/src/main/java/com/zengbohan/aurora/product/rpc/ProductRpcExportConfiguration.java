package com.zengbohan.aurora.product.rpc;

import com.zengbohan.aurora.api.product.ProductRpcApi;
import com.zengbohan.aurora.rpc.proxy.RpcServiceExporter;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.registry.NacosRegistry;
import com.zengbohan.aurora.rpc.spring.AuroraRpcProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * product 的 RPC 导出生命周期：{@code aurora.rpc.enabled=true} 时，
 * 随上下文启动导出器（Netty 监听 + 注册中心上报），关闭时注销并停服。
 */
@Configuration
@ConditionalOnProperty(name = "aurora.rpc.enabled", havingValue = "true")
public class ProductRpcExportConfiguration {

    @Bean
    public SmartLifecycle productRpcExporterLifecycle(
            com.zengbohan.aurora.product.rpc.ProductRpcService rpcService,
            NacosRegistry registry, ProtocolCodec codec, AuroraRpcProperties properties,
            @Value("${aurora.internal.secret}") String internalSecret) {
        RpcServiceExporter exporter = new RpcServiceExporter(registry, codec,
                properties.getHost(), internalSecret);
        exporter.export(ProductRpcApi.class, rpcService);
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                exporter.start();
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
                // 晚于普通 bean 启动、早于它们关闭，导出在依赖就绪之后
                return Integer.MAX_VALUE - 100;
            }
        };
    }
}
