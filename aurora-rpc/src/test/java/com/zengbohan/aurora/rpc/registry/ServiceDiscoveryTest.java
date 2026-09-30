package com.zengbohan.aurora.rpc.registry;

import com.zengbohan.aurora.rpc.lb.RoundRobinLoadBalancer;
import com.zengbohan.aurora.rpc.transport.RpcUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发现缓存：订阅即缓存；并发读写无数据竞争（写时复制，读永远看到完整快照）。
 */
class ServiceDiscoveryTest {

    private static ServiceInstance instance(int port) {
        return new ServiceInstance("svc", "127.0.0.1", port);
    }

    @Test
    void pickThrowsWhenNoInstances() {
        ServiceDiscovery discovery = new ServiceDiscovery(new InMemoryRegistry());
        discovery.subscribe("svc");

        assertThatThrownBy(() -> discovery.pick("svc", new RoundRobinLoadBalancer()))
                .isInstanceOf(RpcUnavailableException.class)
                .hasMessageContaining("svc");
    }

    @Test
    void emptySnapshotIsExposedExplicitly() {
        ServiceDiscovery discovery = new ServiceDiscovery(new InMemoryRegistry());
        discovery.subscribe("svc");
        assertThat(discovery.snapshot("svc")).isEmpty();
    }

    @Test
    void concurrentRegisterUnregisterAndPickNeverSeesTornState() throws Exception {
        // 验收项：订阅变更与本地缓存并发安全——写线程反复上下线，读线程并发挑选，
        // 任何一次 pick 拿到的必须是「三个已知实例之一」，绝不能是撕裂/未知状态
        InMemoryRegistry registry = new InMemoryRegistry();
        ServiceDiscovery discovery = new ServiceDiscovery(registry);
        discovery.subscribe("svc");

        // 起始两个实例，全程保证至少一个在线
        registry.register(instance(1));
        registry.register(instance(2));
        registry.register(instance(3));

        int readers = 8;
        int writerTurns = 500;
        AtomicInteger picks = new AtomicInteger();
        Set<Integer> pickedPorts = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers + 1);
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);

        for (int r = 0; r < readers; r++) {
            pool.execute(() -> {
                try {
                    start.await();
                    // 均衡器与真实客户端一致：每读线程持有一个，而不是每次 pick 新建
                    RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
                    for (int i = 0; i < 2000; i++) {
                        ServiceInstance picked = discovery.pick("svc", lb);
                        if (pickedPorts.size() < 5) {
                            System.err.println("[DBG] pick#" + picks.get() + " -> " + picked.port()
                                + " snapshot=" + discovery.snapshot("svc"));
                        }
                        pickedPorts.add(picked.port()); // 只可能是 1/2/3，其余即撕裂证据
                        picks.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        pool.execute(() -> {
            try {
                start.await();
                for (int i = 1; i <= writerTurns; i++) {
                    registry.unregister(instance(i % 3 + 1)); // 摘一个
                    registry.register(instance(i % 3 + 1));   // 补回同一个，窗口内仍有 ≥2 个
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(picks.get()).isEqualTo(readers * 2000);
        assertThat(pickedPorts).containsExactlyInAnyOrder(1, 2, 3);
    }
}
