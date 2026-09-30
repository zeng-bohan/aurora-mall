package com.zengbohan.aurora.rpc.proxy;

import com.zengbohan.aurora.ratelimit.circuit.CircuitBreaker;
import com.zengbohan.aurora.ratelimit.circuit.CircuitBreakerConfig;
import com.zengbohan.aurora.rpc.lb.LoadBalancer;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.registry.ServiceDiscovery;
import com.zengbohan.aurora.rpc.transport.RpcRemoteException;
import com.zengbohan.aurora.rpc.transport.RpcUnavailableException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消费端代理工厂：接口 → JDK 动态代理。
 * <p>
 * 调用链（每代理一个熔断器实例，故障按接口隔离）：
 * <pre>
 * 接口方法 → Invocation 组装 → 熔断判定 → 发现挑实例（负载均衡）
 *   → 连接池取 client → 序列化 → Netty 往返 → 反序列化
 *   → 业务失败(RpcRemoteException) / 不可用(RpcUnavailableException) / 成功还原返回值
 * </pre>
 * 熔断包裹整个远程段：OPEN 时在发现之前快速失败，不浪费连接与等待。
 */
public class RpcProxyFactory {

    private final ServiceDiscovery discovery;
    private final LoadBalancer loadBalancer;
    private final RpcClientPool clientPool;
    private final ProtocolCodec codec;
    private final String internalSecret;
    private final CircuitBreakerConfig breakerConfig;
    private final Map<Class<?>, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public RpcProxyFactory(ServiceDiscovery discovery, LoadBalancer loadBalancer,
            RpcClientPool clientPool, ProtocolCodec codec, String internalSecret) {
        this(discovery, loadBalancer, clientPool, codec, internalSecret, defaultBreakerConfig());
    }

    public RpcProxyFactory(ServiceDiscovery discovery, LoadBalancer loadBalancer,
            RpcClientPool clientPool, ProtocolCodec codec, String internalSecret,
            CircuitBreakerConfig breakerConfig) {
        this.discovery = discovery;
        this.loadBalancer = loadBalancer;
        this.clientPool = clientPool;
        this.codec = codec;
        this.internalSecret = internalSecret;
        this.breakerConfig = breakerConfig;
    }

    private static CircuitBreakerConfig defaultBreakerConfig() {
        return CircuitBreakerConfig.builder()
                .failureRateThreshold(50)
                .minRequestThreshold(10)
                .halfOpenPermittedCalls(3)
                .openDurationMillis(10_000)
                .build();
    }

    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> api) {
        if (!api.isInterface()) {
            throw new IllegalArgumentException("rpc api must be an interface: " + api.getName());
        }
        discovery.subscribe(api.getName()); // 订阅即入缓存，后续调用走本地快照挑实例
        CircuitBreaker breaker = breakers.computeIfAbsent(api, k -> new CircuitBreaker(breakerConfig));
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(RpcProxyFactory.this, args);
                }
                Invocation invocation = new Invocation(api.getName(), method.getName(),
                        typeNames(method.getParameterTypes()), method.getReturnType().getName(),
                        args == null ? new Object[0] : args);
                Object result = breaker.executeSupplier(() -> callRemote(invocation));
                Class<?> returnType = method.getReturnType();
                if (returnType == void.class) {
                    return null;
                }
                if (returnType.isPrimitive()) {
                    return primitiveBoxed(result, returnType);
                }
                // 按泛型签名还原：List<ProductSnapshot> 等容器返回值的元素类型在 Class 里已擦除
                return codec.convertValue(result, method.getGenericReturnType());
            }
        };
        return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler);
    }

    private Object callRemote(Invocation invocation) {
        var instance = discovery.pick(invocation.interfaceName(), loadBalancer);
        var client = clientPool.get(instance);
        try {
            byte[] request = codec.serialize(invocation);
            byte[] response = client.invoke(request);
            InvocationResult result = codec.deserialize(response, InvocationResult.class);
            if (result.isBusinessFailure()) {
                // 业务失败与不可用分层：远端类型名 + 消息随 RpcRemoteException 穿透
                throw new RpcRemoteException(result.exceptionType() + ": " + result.exceptionMessage());
            }
            return result.result();
        } catch (RpcRemoteException | RpcUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcUnavailableException("rpc invocation failed: " + e, e);
        }
    }

    private static String[] typeNames(Class<?>[] types) {
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].getName();
        }
        return names;
    }

    /** 原始类型返回值：按声明装箱（null 会以 NPE 暴露——语义与本地调用一致）。 */
    private static Object primitiveBoxed(Object value, Class<?> returnType) {
        if (value == null) {
            throw new IllegalStateException("remote returned null for primitive " + returnType.getName());
        }
        if (returnType == int.class) {
            return ((Number) value).intValue();
        }
        if (returnType == long.class) {
            return ((Number) value).longValue();
        }
        if (returnType == double.class) {
            return ((Number) value).doubleValue();
        }
        if (returnType == float.class) {
            return ((Number) value).floatValue();
        }
        if (returnType == boolean.class) {
            return value;
        }
        return value;
    }
}
