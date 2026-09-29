package com.zengbohan.aurora.rpc.registry;

import java.util.List;
import java.util.function.Consumer;

/**
 * 注册中心 SPI：注册/注销/订阅。
 * <p>
 * 订阅语义：注册 {@code listener} 后立即回调一次当前全量实例列表，
 * 此后每次变更（上线/下线）推送新的全量列表——消费端无需再轮询 discover。
 * 变更推送可能来自外部线程，listener 实现方自行保证线程安全。
 * <p>
 * 不复用 Spring Cloud Discovery 抽象的理由（ADR-0008）：那个抽象绑定
 * Spring 生态与 LoadBalancer Client，无法承载本库「纯 Java 核心 + 可选胶水」
 * 的分层；本 SPI 只有四个方法，Nacos/内存两个实现即足以证明其够用。
 */
public interface RegistryService {

    void register(ServiceInstance instance);

    void unregister(ServiceInstance instance);

    /** 订阅：先回调当前列表，再推送后续变更。重复订阅同一 listener 以去重为准。 */
    void subscribe(String service, Consumer<List<ServiceInstance>> listener);

    void unsubscribe(String service, Consumer<List<ServiceInstance>> listener);
}
