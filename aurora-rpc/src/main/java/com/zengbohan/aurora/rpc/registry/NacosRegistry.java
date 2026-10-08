package com.zengbohan.aurora.rpc.registry;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Nacos 注册中心实现：nacos-client 直连（SCA BOM 管版本），注册到默认分组。
 * <p>
 * 实例过滤：只推送 enabled 且 healthy 的实例——注册中心侧已把不健康节点挡在发现结果外，
 * 消费端负载均衡不必再判断。推送语义与 {@link RegistryService} 一致（全量列表）。
 * <p>
 * 订阅实现：每个 service 向 nacos 挂一条适配订阅（服务消费端通常一条链），
 * 其后挂任意多个消费 listener；最后一个 listener 取消时才向 nacos 注销。
 */
public class NacosRegistry implements RegistryService, AutoCloseable {

    private static final Logger log = System.getLogger(NacosRegistry.class.getName());

    public static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    private final NamingService naming;
    private final String group;
    // service -> 该服务的消费 listener 列表。
    private final Map<String, CopyOnWriteArrayList<Consumer<List<ServiceInstance>>>> listeners =
            new ConcurrentHashMap<>();
    // service -> 已挂到 nacos 的适配器（注销时需要原对象）。
    private final Map<String, EventListener> adapters = new ConcurrentHashMap<>();

    public NacosRegistry(String serverAddr) throws NacosException {
        this(NamingFactory.createNamingService(serverAddr), DEFAULT_GROUP);
    }

    // 注入 NamingService 的构造器，供测试替换实现。
    NacosRegistry(NamingService naming, String group) {
        this.naming = naming;
        this.group = group;
    }

    @Override
    public void register(ServiceInstance instance) {
        try {
            naming.registerInstance(instance.service(), group, instance.host(), instance.port());
        } catch (NacosException e) {
            throw new IllegalStateException("nacos register failed for " + instance, e);
        }
    }

    @Override
    public void unregister(ServiceInstance instance) {
        try {
            naming.deregisterInstance(instance.service(), group, instance.host(), instance.port());
        } catch (NacosException e) {
            throw new IllegalStateException("nacos deregister failed for " + instance, e);
        }
    }

    @Override
    public void subscribe(String service, Consumer<List<ServiceInstance>> listener) {
        CopyOnWriteArrayList<Consumer<List<ServiceInstance>>> list =
                listeners.computeIfAbsent(service, k -> new CopyOnWriteArrayList<>());
        if (!list.addIfAbsent(listener)) {
            return; // 重复订阅以去重为准
        }
        if (!adapters.containsKey(service)) {
            subscribeToNacos(service);
        }
    }

    private void subscribeToNacos(String service) {
        EventListener adapter = event -> {
            if (!(event instanceof NamingEvent namingEvent)) {
                return;
            }
            List<ServiceInstance> aurora = toAuroraInstances(service, namingEvent.getInstances());
            for (Consumer<List<ServiceInstance>> listener : listeners.getOrDefault(service,
                    new CopyOnWriteArrayList<>())) {
                listener.accept(aurora);
            }
        };
        // putIfAbsent 是唯一挂订阅的关口：并发 subscribe 同一服务只挂一条适配器
        if (adapters.putIfAbsent(service, adapter) != null) {
            return; // 已有并发订阅者挂好，它负责调 nacos
        }
        try {
            naming.subscribe(service, group, adapter);
        } catch (NacosException e) {
            // 失败路径清掉占位让下一次能重试；只摘本次的 listener，不株连同服务其他订阅者
            adapters.remove(service, adapter);
            CopyOnWriteArrayList<Consumer<List<ServiceInstance>>> list = listeners.get(service);
            if (list != null && list.isEmpty()) {
                listeners.remove(service);
            }
            throw new IllegalStateException("nacos subscribe failed for " + service, e);
        }
    }

    @Override
    public void unsubscribe(String service, Consumer<List<ServiceInstance>> listener) {
        CopyOnWriteArrayList<Consumer<List<ServiceInstance>>> list = listeners.get(service);
        if (list == null || !list.remove(listener)) {
            return;
        }
        if (list.isEmpty()) {
            EventListener adapter = adapters.remove(service);
            if (adapter != null) {
                try {
                    naming.unsubscribe(service, group, adapter);
                } catch (NacosException e) {
                    log.log(Level.WARNING, "nacos unsubscribe failed for " + service + ": " + e);
                }
            }
        }
    }

    @Override
    public List<ServiceInstance> discover(String service) {
        try {
            // 只取健康实例，与订阅推送同口径
            return toAuroraInstances(service, naming.selectInstances(service, group, true));
        } catch (NacosException e) {
            throw new IllegalStateException("nacos discover failed for " + service, e);
        }
    }

    // 只保留 enabled + healthy 的实例。
    private static List<ServiceInstance> toAuroraInstances(String service, List<Instance> instances) {
        if (instances == null) {
            return List.of();
        }
        return instances.stream()
                .filter(i -> i.isEnabled() && i.isHealthy())
                .map(i -> new ServiceInstance(service, i.getIp(), i.getPort()))
                .toList();
    }

    @Override
    public void close() throws Exception {
        naming.shutDown();
    }
}
