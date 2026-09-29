package com.zengbohan.aurora.rpc.lb;

import com.zengbohan.aurora.rpc.registry.ServiceInstance;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 随机策略：ThreadLocalRandom 免竞争。 */
public class RandomLoadBalancer implements LoadBalancer {

    @Override
    public ServiceInstance pick(List<ServiceInstance> instances) {
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }
}
