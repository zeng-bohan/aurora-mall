package com.zengbohan.aurora.rpc.proxy;

import com.zengbohan.aurora.ratelimit.circuit.CircuitBreaker;
import com.zengbohan.aurora.ratelimit.circuit.CircuitBreakerConfig;
import com.zengbohan.aurora.ratelimit.circuit.CircuitBreakerState;
import com.zengbohan.aurora.rpc.lb.LoadBalancer;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.registry.ServiceDiscovery;
import com.zengbohan.aurora.rpc.transport.RpcRemoteException;
import com.zengbohan.aurora.rpc.transport.RpcTimeoutException;
import com.zengbohan.aurora.rpc.transport.RpcUnauthorizedException;
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
 *   → 业务失败(RpcRemoteException) / 不可达或过载(RpcUnavailableException) /
 *     握手或密钥类配置错误(RpcUnauthorizedException) / 成功还原返回值
 * </pre>
 * 熔断包裹整个远程段：OPEN 时在发现之前快速失败，不浪费连接与等待。
 * 熔断只统计"对端不健康"类失败——业务失败不经熔断抛出，配置错误单独成型并被排除在失败率外。
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
                // 握手/密钥类配置错误不代表对端不健康（重试也不会好转）：单独成型且不计失败率
                .ignoreFailures(t -> t instanceof RpcUnauthorizedException)
                .build();
    }

    // 测试观察：指定接口的熔断器当前状态。
    CircuitBreakerState breakerStateForTest(Class<?> api) {
        CircuitBreaker breaker = breakers.get(api);
        return breaker == null ? null : breaker.state();
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
                // 熔断只包传输段：业务失败（载荷异常）在熔断外还原抛出，不污染失败率
                InvocationResult payload = breaker.executeSupplier(() -> callRemote(invocation));
                if (payload.isBusinessFailure()) {
                    throw new RpcRemoteException(
                            payload.exceptionType() + ": " + payload.exceptionMessage());
                }
                Object result = payload.result();
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

    // 传输段：返回载荷（可能含业务失败标记）；可用性异常计入熔断。
    private InvocationResult callRemote(Invocation invocation) {
        var instance = discovery.pick(invocation.interfaceName(), loadBalancer);
        var client = clientPool.get(instance);
        try {
            byte[] request = codec.serialize(invocation);
            byte[] response = client.invoke(request);
            return codec.deserialize(response, InvocationResult.class);
        } catch (RpcUnavailableException | RpcTimeoutException | RpcUnauthorizedException e) {
            // 三类语义分明的失败原样上抛：不可达/超时（计入熔断）与配置错误（不计入）
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

    // 原始类型返回值：按声明装箱（null 会以 NPE 暴露——语义与本地调用一致）。
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
            return ((Boolean) value).booleanValue();
        }
        if (returnType == byte.class) {
            return ((Number) value).byteValue();
        }
        if (returnType == short.class) {
            return ((Number) value).shortValue();
        }
        if (returnType == char.class) {
            // 数字按 ASCII/码位转，字符串取首字符（JSON 字符通常落为 String 或 Number）
            if (value instanceof Number n) {
                return (char) n.intValue();
            }
            String s = (String) value;
            if (s.isEmpty()) {
                throw new IllegalStateException("remote returned empty string for char");
            }
            return s.charAt(0);
        }
        return value;
    }
}
