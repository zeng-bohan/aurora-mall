package com.zengbohan.aurora.rpc.registry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 内存注册表：测试与 CI 用，零外部依赖。
 * <p>
 * 实例与监听器都放 CopyOnWriteArrayList——快照构建免锁、监听器迭代免
 * ConcurrentModificationException；对外永远推不可变全量列表（写时复制）。
 */
public class InMemoryRegistry implements RegistryService {

    private final Map<String, CopyOnWriteArrayList<ServiceInstance>> instances = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<List<ServiceInstance>>>> listeners =
            new ConcurrentHashMap<>();

    @Override
    public void register(ServiceInstance instance) {
        boolean added = instances.computeIfAbsent(instance.service(), k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(instance);
        if (added) {
            notifyListeners(instance.service());
        }
    }

    @Override
    public void unregister(ServiceInstance instance) {
        CopyOnWriteArrayList<ServiceInstance> list = instances.get(instance.service());
        if (list != null && list.remove(instance)) {
            notifyListeners(instance.service());
        }
    }

    @Override
    public void subscribe(String service, Consumer<List<ServiceInstance>> listener) {
        CopyOnWriteArrayList<Consumer<List<ServiceInstance>>> list =
                listeners.computeIfAbsent(service, k -> new CopyOnWriteArrayList<>());
        if (list.addIfAbsent(listener)) {
            // 订阅即送达当前快照，消费端无需先 discover 再 subscribe 两步
            listener.accept(snapshot(service));
        }
    }

    @Override
    public void unsubscribe(String service, Consumer<List<ServiceInstance>> listener) {
        CopyOnWriteArrayList<Consumer<List<ServiceInstance>>> list = listeners.get(service);
        if (list != null) {
            list.remove(listener);
        }
    }

    @Override
    public List<ServiceInstance> discover(String service) {
        return snapshot(service);
    }

    private void notifyListeners(String service) {
        List<ServiceInstance> snapshot = snapshot(service);
        CopyOnWriteArrayList<Consumer<List<ServiceInstance>>> list = listeners.get(service);
        if (list != null) {
            for (Consumer<List<ServiceInstance>> listener : list) {
                listener.accept(snapshot);
            }
        }
    }

    private List<ServiceInstance> snapshot(String service) {
        CopyOnWriteArrayList<ServiceInstance> list = instances.get(service);
        return list == null ? List.of() : List.copyOf(list);
    }
}
