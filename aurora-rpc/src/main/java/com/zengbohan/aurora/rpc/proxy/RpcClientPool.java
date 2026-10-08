package com.zengbohan.aurora.rpc.proxy;

import com.zengbohan.aurora.rpc.registry.ServiceInstance;
import com.zengbohan.aurora.rpc.transport.RpcClient;
import com.zengbohan.aurora.rpc.transport.RpcUnavailableException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消费端连接池：按实例地址缓存 {@link RpcClient}，避免每次调用重建连接。
 * 新实例首连是异步的，取用前同步暖机——冷实例的第一跳调用不会白 fail。
 * <p>
 * 已知边界：每个 client 自带独立 IO 线程组——实例数量少（个位数）时可接受；
 * 跨实例共享线程组留到观测期按实测决定（M4）。
 */
public class RpcClientPool implements AutoCloseable {

    private static final long WARMUP_TIMEOUT_MILLIS = 2000;

    private final String internalSecret;
    private final long requestTimeoutMillis;
    private final Map<String, RpcClient> clients = new ConcurrentHashMap<>();

    public RpcClientPool(String internalSecret, long requestTimeoutMillis) {
        this.internalSecret = internalSecret;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    // 取（或建）到指定实例的连接；不可达时抛 RpcUnavailableException 并回收条目。
    public RpcClient get(ServiceInstance instance) {
        String key = instance.host() + ":" + instance.port();
        RpcClient client = clients.computeIfAbsent(key, k -> {
            RpcClient created = new RpcClient(instance.host(), instance.port(),
                    internalSecret, requestTimeoutMillis, 30_000, 500);
            created.start();
            return created;
        });
        try {
            if (!client.awaitConnected(WARMUP_TIMEOUT_MILLIS)) {
                throw new RpcUnavailableException("instance unreachable: " + key);
            }
            return client;
        } catch (RpcUnavailableException | InterruptedException e) {
            clients.remove(key, client);
            // 两个失败分支都必须停掉 client：Netty 线程组 + 重连任务否则永久泄漏
            client.stop();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new RpcUnavailableException("interrupted while connecting to " + key);
            }
            throw (RpcUnavailableException) e;
        }
    }

    @Override
    public void close() {
        clients.values().forEach(RpcClient::stop);
        clients.clear();
    }
}
