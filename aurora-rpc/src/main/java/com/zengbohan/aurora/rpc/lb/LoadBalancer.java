package com.zengbohan.aurora.rpc.lb;

import com.zengbohan.aurora.rpc.registry.ServiceInstance;

import java.util.List;

/**
 * 负载均衡 SPI：从已发现的实例列表里挑一个。
 * 实现必须无状态或仅持原子计数（被并发调用），不允许阻塞。
 */
public interface LoadBalancer {

    // 从非空实例列表中挑选；列表由调用方保证非空。
    ServiceInstance pick(List<ServiceInstance> instances);
}
