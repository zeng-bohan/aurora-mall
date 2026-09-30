package com.zengbohan.aurora.rpc.registry;

import com.zengbohan.aurora.rpc.lb.LoadBalancer;
import com.zengbohan.aurora.rpc.transport.RpcUnavailableException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 消费端发现缓存：订阅 + 本地快照 + 挑选。
 * <p>
 * 并发安全：快照是不可变列表，监听线程整体替换（写时复制）——读路径无锁，
 * 不存在「遍历到一半列表变了」的数据竞争（M3 T6 验收项）。
 */
public class ServiceDiscovery {

    private final RegistryService registry;
    /** service -> 最近一次推送的不可变全量快照。 */
    private final Map<String, List<ServiceInstance>> cache = new ConcurrentHashMap<>();
    /** service -> 挂到注册中心的 listener（注销时按同一身份摘除）。 */
    private final Map<String, Consumer<List<ServiceInstance>>> wired = new ConcurrentHashMap<>();

    public ServiceDiscovery(RegistryService registry) {
        this.registry = registry;
    }

    /** 订阅服务：缓存随注册中心推送自动更新。 */
    public void subscribe(String service) {
        Consumer<List<ServiceInstance>> listener = new Consumer<>() {
            @Override
            public void accept(List<ServiceInstance> instances) {
                cache.put(service, instances);
            }
        };
        if (wired.putIfAbsent(service, listener) == null) {
            registry.subscribe(service, listener);
        }
    }

    public void unsubscribe(String service) {
        Consumer<List<ServiceInstance>> listener = wired.remove(service);
        if (listener != null) {
            registry.unsubscribe(service, listener);
        }
        cache.remove(service);
    }

    /** 当前快照（可能是空列表——注册中心还没推过或全部下线）。 */
    public List<ServiceInstance> snapshot(String service) {
        return cache.getOrDefault(service, List.of());
    }

    /**
     * 挑一个实例调用。本地快照为空（订阅首帧未达的启动窗口）时同步拉取兜底。
     *
     * @throws RpcUnavailableException 服务没有任何可用实例
     */
    public ServiceInstance pick(String service, LoadBalancer loadBalancer) {
        List<ServiceInstance> snapshot = snapshot(service);
        if (snapshot.isEmpty()) {
            // 订阅推送是异步的，启动后第一跳可能早于首帧——同步拉取消除竞态
            snapshot = registry.discover(service);
            if (!snapshot.isEmpty()) {
                cache.put(service, snapshot);
            }
        }
        if (snapshot.isEmpty()) {
            throw new RpcUnavailableException("no instances available for " + service);
        }
        return loadBalancer.pick(snapshot);
    }
}
