package com.zengbohan.aurora.rpc.registry;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 Nacos 往返：本地 compose 起 Nacos 后跑（注册 → 订阅推送 → 注销推送）。
 * Nacos 不可达时自动跳过（同 StockLuaIntegrationTest 模式），CI 无 Nacos 保持绿。
 * 本地运行：docker compose up -d nacos
 */
class NacosRegistryIntegrationTest {

    private static final String NACOS_ADDR =
            System.getenv().getOrDefault("NACOS_ADDR", "localhost:8848");
    private static final String SERVICE = "aurora-rpc-it";

    private NacosRegistry registry;

    private static boolean nacosReachable() {
        String[] parts = NACOS_ADDR.split(":");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(nacosReachable(),
                "no nacos at " + NACOS_ADDR + "; skipping nacos integration test");
        registry = new NacosRegistry(NACOS_ADDR);
    }

    @Test
    void registerDiscoverAndDeregisterRoundTrip() throws Exception {
        List<List<ServiceInstance>> seen = new CopyOnWriteArrayList<>();
        registry.subscribe(SERVICE, seen::add);

        ServiceInstance me = new ServiceInstance(SERVICE, "127.0.0.1", 28001);
        registry.register(me);

        assertThat(awaitSnapshot(seen, me, true))
                .as("注册后订阅推送应包含本实例").isTrue();

        registry.unregister(me);
        assertThat(awaitSnapshot(seen, me, false))
                .as("注销后推送应不再包含本实例").isTrue();

        registry.close();
    }

    private boolean awaitSnapshot(List<List<ServiceInstance>> seen, ServiceInstance target,
            boolean expectPresent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (List<ServiceInstance> snapshot : seen) {
                boolean contains = snapshot.contains(target);
                if (contains == expectPresent) {
                    return true;
                }
            }
            Thread.sleep(200);
        }
        return false;
    }
}
