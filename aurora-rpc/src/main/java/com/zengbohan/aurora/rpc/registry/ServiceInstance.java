package com.zengbohan.aurora.rpc.registry;

/**
 * 一个 RPC 服务实例：服务名 + 地址。record 值语义让注册表天然按内容去重。
 */
public record ServiceInstance(String service, String host, int port) {

    public ServiceInstance {
        if (service == null || service.isEmpty()) {
            throw new IllegalArgumentException("service must not be empty");
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }
}
