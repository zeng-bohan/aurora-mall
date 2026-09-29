package com.zengbohan.aurora.rpc.lb;

import com.zengbohan.aurora.rpc.registry.ServiceInstance;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轮询策略：原子计数严格轮转。列表长度变化时取模自然适配
 * （不追求跨变更的绝对均匀——短窗口内的不均没有语义意义）。
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public ServiceInstance pick(List<ServiceInstance> instances) {
        int index = (int) Math.floorMod(counter.getAndIncrement(), instances.size());
        return instances.get(index);
    }
}
