package com.zengbohan.aurora.rpc.lb;

import com.zengbohan.aurora.rpc.registry.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负载均衡分布：轮询严格轮转、随机覆盖全实例。
 */
class LoadBalancerTest {

    private static ServiceInstance instance(int port) {
        return new ServiceInstance("svc", "127.0.0.1", port);
    }

    @Test
    void roundRobinRotatesStrictly() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        List<ServiceInstance> instances = List.of(instance(1), instance(2), instance(3));

        // 两个完整周期：顺序严格 1,2,3,1,2,3
        int[] expected = {1, 2, 3, 1, 2, 3};
        for (int expectedPort : expected) {
            assertThat(lb.pick(instances).port()).isEqualTo(expectedPort);
        }
    }

    @Test
    void roundRobinAdaptsToShrinkingList() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        // 计数到 3 时列表缩到 2：取模适配，不越界、不漏实例
        List<ServiceInstance> three = List.of(instance(1), instance(2), instance(3));
        lb.pick(three);
        lb.pick(three);

        List<ServiceInstance> two = List.of(instance(1), instance(2));
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            ports.add(lb.pick(two).port());
        }
        assertThat(ports).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void randomCoversAllInstances() {
        RandomLoadBalancer lb = new RandomLoadBalancer();
        List<ServiceInstance> instances = List.of(instance(1), instance(2), instance(3));
        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(lb.pick(instances).port());
        }
        assertThat(picked).as("200 次随机应覆盖全部实例").containsExactlyInAnyOrder(1, 2, 3);
    }
}
