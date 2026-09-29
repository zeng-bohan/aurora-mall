package com.zengbohan.aurora.rpc.registry;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Nacos 适配逻辑（不起真 Nacos）：健康过滤、订阅去重、最后一个取消才注销。
 */
class NacosRegistryTest {

    private final NamingService naming = mock(NamingService.class);
    private final NacosRegistry registry = new NacosRegistry(naming, "DEFAULT_GROUP");

    private static Instance nacosInstance(String ip, int port, boolean healthy) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setHealthy(healthy);
        instance.setEnabled(true);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private EventListener subscribedAdapter(String service) throws Exception {
        ArgumentCaptor<EventListener> captor = ArgumentCaptor.forClass(EventListener.class);
        verify(naming).subscribe(eq(service), eq("DEFAULT_GROUP"), captor.capture());
        return captor.getValue();
    }

    @Test
    void pushesOnlyEnabledHealthyInstances() throws Exception {
        List<List<ServiceInstance>> received = new CopyOnWriteArrayList<>();
        registry.subscribe("svc", snapshot -> received.add(snapshot));

        EventListener adapter = subscribedAdapter("svc");
        adapter.onEvent(new NamingEvent("svc", List.of(
                nacosInstance("127.0.0.1", 8001, true),
                nacosInstance("127.0.0.1", 8002, false))));

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).containsExactly(new ServiceInstance("svc", "127.0.0.1", 8001));
    }

    @Test
    void duplicateSubscriptionHitsNacosOnce() throws Exception {
        List<List<ServiceInstance>> received = new CopyOnWriteArrayList<>();
        Consumer<List<ServiceInstance>> listener = snapshot -> received.add(snapshot);
        registry.subscribe("svc", listener);
        registry.subscribe("svc", listener);

        verify(naming).subscribe(anyString(), anyString(), any()); // 恰一次（verify 默认次数 1）
    }

    @Test
    void lastUnsubscribeDetachesFromNacos() throws Exception {
        List<List<ServiceInstance>> received = new CopyOnWriteArrayList<>();
        Consumer<List<ServiceInstance>> listener = snapshot -> received.add(snapshot);
        registry.subscribe("svc", listener);
        registry.unsubscribe("svc", listener);

        verify(naming).unsubscribe(eq("svc"), eq("DEFAULT_GROUP"), any());
    }

    @Test
    void survivingSubscriptionKeepsNacosAttached() throws Exception {
        Consumer<List<ServiceInstance>> first = i -> {
        };
        Consumer<List<ServiceInstance>> second = i -> {
        };
        registry.subscribe("svc", first);
        registry.subscribe("svc", second);
        registry.unsubscribe("svc", first);

        verify(naming, never()).unsubscribe(anyString(), anyString(), any());
    }

    @Test
    void nullInstanceListPushesEmptySnapshot() throws Exception {
        List<List<ServiceInstance>> received = new CopyOnWriteArrayList<>();
        registry.subscribe("svc", snapshot -> received.add(snapshot));

        subscribedAdapter("svc").onEvent(new NamingEvent("svc", null));

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isEmpty();
    }
}
