package com.zengbohan.aurora.rpc.proxy;

import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.registry.RegistryService;
import com.zengbohan.aurora.rpc.registry.ServiceInstance;
import com.zengbohan.aurora.rpc.transport.RpcServer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端导出器：把实现挂到传输层并注册进注册中心。
 * <p>
 * 职责链：注册（服务名=接口全名 → 实现）→ 启动 Netty（随机端口）→ 注册中心上报实例。
 * 业务异常在本层被捕获并编进 {@link InvocationResult}（status=OK + exceptionType），
 * 不断连——与传输层"处理器异常=系统失败"的语义区分。
 * 传输沿用内部密钥握手（与 HTTP 侧同源配置），本类不另设免鉴权通道。
 */
public class RpcServiceExporter {

    private final RegistryService registry;
    private final ProtocolCodec codec;
    private final String host;
    private final String internalSecret;
    /** 服务名（接口全名）→ 实现。 */
    private final Map<String, Object> catalog = new ConcurrentHashMap<>();

    private RpcServer server;
    private int port = -1;

    public RpcServiceExporter(RegistryService registry, ProtocolCodec codec,
            String host, String internalSecret) {
        this.registry = registry;
        this.codec = codec;
        this.host = host;
        this.internalSecret = internalSecret;
    }

    /** 注册一个服务实现；服务名 = 接口全名。 */
    public RpcServiceExporter export(Class<?> api, Object impl) {
        if (!api.isInterface()) {
            throw new IllegalArgumentException("api must be an interface: " + api.getName());
        }
        if (!api.isInstance(impl)) {
            throw new IllegalArgumentException(impl.getClass().getName() + " does not implement " + api.getName());
        }
        catalog.put(api.getName(), impl);
        return this;
    }

    /** 启动传输（随机端口）并把本机每个服务注册进注册中心。 */
    public synchronized int start() {
        if (server != null) {
            throw new IllegalStateException("exporter already started on port " + port);
        }
        server = new RpcServer(internalSecret, this::dispatch);
        try {
            port = server.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("exporter start interrupted", e);
        }
        for (String service : catalog.keySet()) {
            registry.register(new ServiceInstance(service, host, port));
        }
        return port;
    }

    public synchronized void stop() {
        if (server != null) {
            for (String service : catalog.keySet()) {
                registry.unregister(new ServiceInstance(service, host, port));
            }
            server.stop();
            server = null;
            port = -1;
        }
    }

    public int port() {
        return port;
    }

    /**
     * 单请求分发：解码 Invocation → 还原参数类型 → 反射调用 → 编码结果/业务异常。
     * 找不到服务/方法或解码失败按系统异常抛出（传输层转 ERROR status 回传）。
     */
    private byte[] dispatch(byte[] body) throws Exception {
        Invocation invocation = codec.deserialize(body, Invocation.class);
        Object impl = catalog.get(invocation.interfaceName());
        if (impl == null) {
            throw new IllegalStateException("service not exported: " + invocation.interfaceName());
        }
        Class<?>[] parameterTypes = Arrays.stream(invocation.parameterTypeNames())
                .map(TypeNames::resolve).toArray(Class<?>[]::new);
        Object[] args = new Object[invocation.args().length];
        for (int i = 0; i < args.length; i++) {
            // JSON 泛化回来的参数节点按声明的具体类型还原（泛型擦除防护）
            args[i] = codec.convertValue(invocation.args()[i],
                    TypeNames.resolve(invocation.parameterTypeNames()[i]));
        }
        Method method = impl.getClass().getMethod(invocation.methodName(), parameterTypes);
        try {
            Object result = method.invoke(impl, args);
            Class<?> returnType = TypeNames.resolve(invocation.returnTypeName());
            return codec.serialize(InvocationResult.of(
                    void.class.equals(returnType) ? null : codec.convertValue(result, returnType)));
        } catch (InvocationTargetException e) {
            // 业务异常：类型名 + 消息编进载荷，客户端还原为 RpcRemoteException
            Throwable cause = e.getCause();
            return codec.serialize(InvocationResult.failure(
                    cause.getClass().getName(), String.valueOf(cause.getMessage())));
        }
    }
}
