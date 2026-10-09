package com.zengbohan.aurora.rpc.registry;

import com.zengbohan.aurora.rpc.lb.LoadBalancer;
import com.zengbohan.aurora.rpc.transport.RpcUnavailableException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 消费端发现缓存：订阅 + 本地快照 + 挑选。
 * <p>
 * 并发安全：快照是不可变列表，监听线程整体替换（写时复制）——读路径无锁，
 * 不存在「遍历到一半列表变了」的数据竞争（M3 T6 验收项）。
 */
public class ServiceDiscovery {

    private static final System.Logger log = System.getLogger(ServiceDiscovery.class.getName());

    private final RegistryService registry;
    // service -> 最近一次推送的不可变全量快照。
    private final Map<String, List<ServiceInstance>> cache = new ConcurrentHashMap<>();
    // service -> 挂到注册中心的 listener（注销时按同一身份摘除）。
    private final Map<String, Consumer<List<ServiceInstance>>> wired = new ConcurrentHashMap<>();
    // 推送到达时的钩子（在本地缓存刷新之后触发）：供上层剪掉下线实例的连接等。
    private final List<BiConsumer<String, List<ServiceInstance>>> changeHooks = new CopyOnWriteArrayList<>();

    public ServiceDiscovery(RegistryService registry) {
        this.registry = registry;
    }

    // 订阅服务：缓存随注册中心推送自动更新。
    public void subscribe(String service) {
        Consumer<List<ServiceInstance>> listener = new Consumer<>() {
            @Override
            public void accept(List<ServiceInstance> instances) {
                cache.put(service, instances);
                fireChangeHooks(service, instances);
            }
        };
        if (wired.putIfAbsent(service, listener) != null) {
            return;
        }
        try {
            registry.subscribe(service, listener);
        } catch (RuntimeException e) {
            // 注册中心没接上：撤回占位。否则这次失败会被记成"已接线"，后续 subscribe
            // 直接返回、永远走不到注册中心（NacosRegistry 失败时已清好自身状态，就等这次重试）
            wired.remove(service, listener);
            throw e;
        }
    }

    public void unsubscribe(String service) {
        Consumer<List<ServiceInstance>> listener = wired.remove(service);
        if (listener != null) {
            registry.unsubscribe(service, listener);
        }
        cache.remove(service);
    }

    /**
     * 注册中心推送到达时回调（在本地缓存刷新之后）。
     * 钩子内部异常只记日志：推送线程同时喂着同服务的其他订阅者，一个钩子不该把它们全带崩。
     */
    public void addChangeHook(BiConsumer<String, List<ServiceInstance>> hook) {
        changeHooks.add(hook);
    }

    /**
     * 所有已订阅服务的实例并集——驱逐下线实例时的「存活集合」。
     * 必须用并集而非单个服务的快照：{@code RpcClientPool} 按地址共用连接，
     * 只按一个服务的快照剪会误杀别的服务正在用的连接。
     */
    public Set<ServiceInstance> knownInstances() {
        Set<ServiceInstance> all = new HashSet<>();
        for (List<ServiceInstance> snapshot : cache.values()) {
            all.addAll(snapshot);
        }
        return all;
    }

    // 当前快照（可能是空列表——注册中心还没推过或全部下线）。
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

    private void fireChangeHooks(String service, List<ServiceInstance> instances) {
        for (BiConsumer<String, List<ServiceInstance>> hook : changeHooks) {
            try {
                hook.accept(service, instances);
            } catch (RuntimeException e) {
                log.log(System.Logger.Level.WARNING, "change hook failed for " + service, e);
            }
        }
    }
}
