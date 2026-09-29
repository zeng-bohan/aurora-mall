package com.zengbohan.aurora.rpc.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存注册表：订阅即送达当前快照、变更推送全量、按服务隔离、注销停推。
 */
class InMemoryRegistryTest {

    private final InMemoryRegistry registry = new InMemoryRegistry();

    private static ServiceInstance instance(int port) {
        return new ServiceInstance("aurora-product", "127.0.0.1", port);
    }

    @Test
    void subscribeDeliversCurrentSnapshotThenPushesChanges() {
        registry.register(instance(8001));
        List<List<ServiceInstance>> seen = new CopyOnWriteArrayList<>();
        Consumer<List<ServiceInstance>> listener = seen::add;

        registry.subscribe("aurora-product", listener);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).containsExactly(instance(8001)); // 订阅即拿到现状

        registry.register(instance(8002)); // 上线推送
        assertThat(seen.get(1)).containsExactlyInAnyOrder(instance(8001), instance(8002));

        registry.unregister(instance(8001)); // 下线推送
        assertThat(seen.get(2)).containsExactly(instance(8002));
    }

    @Test
    void duplicateRegisterDoesNotPushTwice() {
        List<List<ServiceInstance>> seen = new CopyOnWriteArrayList<>();
        registry.subscribe("aurora-product", seen::add);
        registry.register(instance(8001));
        registry.register(instance(8001)); // 同一实例幂等

        assertThat(seen).hasSize(2); // 订阅快照 + 一次上线
        assertThat(seen.get(1)).containsExactly(instance(8001));
    }

    @Test
    void servicesAreIsolated() {
        List<List<ServiceInstance>> productSeen = new CopyOnWriteArrayList<>();
        List<List<ServiceInstance>> orderSeen = new CopyOnWriteArrayList<>();
        registry.subscribe("aurora-product", productSeen::add);
        registry.subscribe("aurora-order", orderSeen::add);

        registry.register(new ServiceInstance("aurora-order", "127.0.0.1", 9001));

        assertThat(productSeen).allSatisfy(List::isEmpty);
        // orderSeen: [订阅快照(空), 上线推送(1)]
        assertThat(orderSeen).hasSize(2);
        assertThat(orderSeen.get(1)).hasSize(1);
    }

    @Test
    void unsubscribeStopsPushes() {
        List<List<ServiceInstance>> seen = new CopyOnWriteArrayList<>();
        Consumer<List<ServiceInstance>> listener = seen::add;
        registry.subscribe("aurora-product", listener);
        registry.unsubscribe("aurora-product", listener);

        registry.register(instance(8001));
        assertThat(seen).hasSize(1); // 只有订阅时的那次
    }

    @Test
    void instanceValidationRejectsBadArgs() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServiceInstance("", "127.0.0.1", 8000));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServiceInstance("svc", "127.0.0.1", 0));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServiceInstance("svc", "127.0.0.1", 65536));
    }
}
