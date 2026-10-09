package com.zengbohan.aurora.rpc.proxy;

import com.zengbohan.aurora.ratelimit.circuit.CircuitBreakerConfig;
import com.zengbohan.aurora.ratelimit.circuit.CircuitBreakerState;
import com.zengbohan.aurora.ratelimit.circuit.CircuitOpenException;
import com.zengbohan.aurora.rpc.lb.RoundRobinLoadBalancer;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.registry.InMemoryRegistry;
import com.zengbohan.aurora.rpc.registry.ServiceDiscovery;
import com.zengbohan.aurora.rpc.registry.ServiceInstance;
import com.zengbohan.aurora.rpc.transport.RpcClient;
import com.zengbohan.aurora.rpc.transport.RpcRemoteException;
import com.zengbohan.aurora.rpc.transport.RpcUnauthorizedException;
import com.zengbohan.aurora.rpc.transport.RpcUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 代理全链路集成测试：接口调用经 发现→均衡→熔断→编解码→真 Netty 往返，
 * 返回值/业务异常/不可用/熔断四类结果正确穿透。内存注册中心 + 随机端口，CI 可跑。
 */
class RpcProxyFullChainIntegrationTest {

    private static final String SECRET = "it-secret-0123456789";

    // 样例服务接口。
    interface EchoApi {
        String echo(String msg);

        int add(int a, int b);

        Pojo roundTrip(EchoApi.Pojo pojo);

        void fireAndForget();

        String boom();

        // 泛型容器返回值：回归 List<ProductSnapshot> 形态的擦除还原。
        List<Pojo> listPojo();

        // byte/short/char/boolean 返回值：代理侧按声明类型装箱（审查九）。
        default byte flag() { return 42; }

        default short small() { return 1000; }

        default char grade() { return 'A'; }

        default boolean ok() { return true; }

        record Pojo(String name, List<Integer> values) {
        }
    }

    private final InMemoryRegistry registry = new InMemoryRegistry();
    private final ServiceDiscovery discovery = new ServiceDiscovery(registry);
    private final ProtocolCodec codec = new ProtocolCodec();
    private final RpcClientPool pool = new RpcClientPool(SECRET, 3000);
    private final RpcServiceExporter exporter = new RpcServiceExporter(registry, codec, "127.0.0.1", SECRET);

    @AfterEach
    void tearDown() {
        pool.close();
        exporter.stop();
    }

    @Test
    void fullCallChainReturnsTypedResultsThroughProxy() {
        AtomicInteger fired = new AtomicInteger();
        exporter.export(EchoApi.class, new EchoApi() {
            @Override
            public String echo(String msg) {
                return "echo:" + msg;
            }

            @Override
            public int add(int a, int b) {
                return a + b;
            }

            @Override
            public Pojo roundTrip(EchoApi.Pojo pojo) {
                return new Pojo(pojo.name().toUpperCase(), pojo.values());
            }

            @Override
            public void fireAndForget() {
                fired.incrementAndGet();
            }

            @Override
            public String boom() {
                throw new IllegalStateException("unused");
            }

            @Override
            public List<Pojo> listPojo() {
                return List.of(new Pojo("in-list", List.of(9)));
            }
        });
        exporter.start();

        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, SECRET);
        EchoApi api = factory.create(EchoApi.class);

        assertThat(api.echo("hello")).isEqualTo("echo:hello");
        // 泛型容器返回值元素还原为具体 record（而非 Map）
        assertThat(api.listPojo()).containsExactly(new EchoApi.Pojo("in-list", List.of(9)));
        assertThat(api.add(2, 3)).isEqualTo(5); // 原始类型返回
        assertThat(api.flag()).isEqualTo((byte) 42); // byte 返回值按类型还原
        assertThat(api.small()).isEqualTo((short) 1000); // short 返回值按类型还原
        assertThat(api.grade()).isEqualTo('A'); // char 返回值（JSON 字符串）还原
        assertThat(api.ok()).isTrue(); // boolean 返回值
        assertThat(api.roundTrip(new EchoApi.Pojo("abc", List.of(1, 2))))
                .isEqualTo(new EchoApi.Pojo("ABC", List.of(1, 2))); // 嵌套 record + 集合往返
        api.fireAndForget();
        assertThat(fired.get()).isEqualTo(1); // void 方法
    }

    @Test
    void businessFailureSurfacesAsRpcRemoteExceptionWithRemoteType() {
        exporter.export(EchoApi.class, new EchoApi() {
            @Override
            public String echo(String msg) {
                return msg;
            }

            @Override
            public int add(int a, int b) {
                return 0;
            }

            @Override
            public Pojo roundTrip(EchoApi.Pojo pojo) {
                return pojo;
            }

            @Override
            public void fireAndForget() {
            }

            @Override
            public String boom() {
                // 业务失败：显式标记 → 服务端转 payload → 客户端 RpcRemoteException
                throw new com.zengbohan.aurora.rpc.proxy.BusinessFailureException(
                        "java.lang.IllegalArgumentException", "bad input");
            }

            @Override
            public List<Pojo> listPojo() {
                return List.of();
            }
        });
        exporter.start();

        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, SECRET);
        EchoApi api = factory.create(EchoApi.class);

        // 业务失败（对端活着但业务抛异常）与不可用分层：RpcRemoteException 携带远端类型名
        assertThatThrownBy(api::boom)
                .isInstanceOf(RpcRemoteException.class)
                .hasMessageContaining(IllegalArgumentException.class.getName())
                .hasMessageContaining("bad input");
        // 连接不断：后续调用照常
        assertThat(api.echo("again")).isEqualTo("again");
    }

    @Test
    void noInstancesFailsFastWithUnavailable() {
        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, SECRET);
        EchoApi api = factory.create(EchoApi.class); // 订阅了但没有任何实例

        assertThatThrownBy(() -> api.echo("x"))
                .isInstanceOf(RpcUnavailableException.class)
                .hasMessageContaining(EchoApi.class.getName());
    }

    @Test
    void circuitBreakerOpensOnConsecutiveFailuresAndRecoversAfterProbe() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(false);
        AtomicInteger serverCalls = new AtomicInteger();
        exporter.export(EchoApi.class, new EchoApi() {
            @Override
            public String echo(String msg) {
                serverCalls.incrementAndGet();
                if (!healthy.get()) {
                    throw new IllegalStateException("downstream down");
                }
                return "ok";
            }

            @Override
            public int add(int a, int b) {
                return 0;
            }

            @Override
            public Pojo roundTrip(EchoApi.Pojo pojo) {
                return pojo;
            }

            @Override
            public void fireAndForget() {
            }

            @Override
            public String boom() {
                return "boom";
            }

            @Override
            public List<Pojo> listPojo() {
                return List.of();
            }
        });
        exporter.start();

        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .failureRateThreshold(50)
                .minRequestThreshold(2)
                .halfOpenPermittedCalls(1)
                .openDurationMillis(400)
                .build();
        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, SECRET, config);
        EchoApi api = factory.create(EchoApi.class);

        // 两次系统失败（100% ≥ 50%）→ OPEN（系统失败计入熔断，业务失败不计）
        assertThatThrownBy(() -> api.echo("a")).isInstanceOf(RpcUnavailableException.class);
        assertThatThrownBy(() -> api.echo("b")).isInstanceOf(RpcUnavailableException.class);
        int callsAtOpen = serverCalls.get();

        // OPEN 期间快速失败：不触达远端（计数不再涨），远早于网络往返
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> api.echo("c")).isInstanceOf(CircuitOpenException.class);
        long elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
        assertThat(serverCalls.get()).as("OPEN 期间零远端调用").isEqualTo(callsAtOpen);
        assertThat(elapsedMillis).as("快速失败").isLessThan(150);

        // OPEN 时长到达 → HALF_OPEN 放行试探；服务端此时恢复 → 试探成功回 CLOSED
        Thread.sleep(500);
        healthy.set(true);
        assertThat(api.echo("recover")).isEqualTo("ok");
        assertThat(api.echo("stable")).isEqualTo("ok"); // CLOSED 后正常调用
    }

    @Test
    void breakerIgnoresBusinessFailuresButCountsSystemFailures() throws Exception {
        // 业务失败（payload）不计熔断失败率；系统失败（ERROR status）计入
        AtomicBoolean mode = new AtomicBoolean(false); // false=业务失败 true=系统失败
        RpcServiceExporter mixed = new RpcServiceExporter(registry, codec, "127.0.0.1", SECRET);
        mixed.export(EchoApi.class, new EchoApi() {
            @Override
            public String echo(String msg) {
                if (msg.startsWith("sys")) {
                    throw new IllegalStateException("system down");
                }
                if (msg.startsWith("biz")) {
                    throw new com.zengbohan.aurora.rpc.proxy.BusinessFailureException(
                            "java.lang.IllegalArgumentException", "rejected");
                }
                return "ok";
            }

            @Override
            public int add(int a, int b) {
                return 0;
            }

            @Override
            public EchoApi.Pojo roundTrip(EchoApi.Pojo pojo) {
                return pojo;
            }

            @Override
            public void fireAndForget() {
            }

            @Override
            public String boom() {
                return "boom";
            }

            @Override
            public List<EchoApi.Pojo> listPojo() {
                return List.of();
            }
        });
        mixed.start();

        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .failureRateThreshold(50)
                .minRequestThreshold(4)
                .halfOpenPermittedCalls(1)
                .openDurationMillis(5000)
                .build();
        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, SECRET, config);
        EchoApi api = factory.create(EchoApi.class);

        // 4 次业务失败：RpcRemoteException 穿透且不计熔断，保持 CLOSED
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> api.echo("biz")).isInstanceOf(RpcRemoteException.class);
        }
        assertThat(breakerState(api, factory)).isEqualTo(CircuitBreakerState.CLOSED);

        // 4 次系统失败（样本 4 成功+4 失败=50%≥50%）→ OPEN
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> api.echo("sys")).isInstanceOf(RpcUnavailableException.class);
        }
        assertThat(breakerState(api, factory)).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void unauthorizedErrorSurfacesAsConfigFailureWithoutTrippingBreaker() {
        // 假连接池：不发真请求，直接抛"未授权"——等价于对端回 UNAUTHORIZED 的那条路径
        RpcClientPool unauthorizedPool = new RpcClientPool(SECRET, 3000) {
            @Override
            public RpcClient get(ServiceInstance instance) {
                return new RpcClient(instance.host(), instance.port(), SECRET, 1000, 30_000, 500) {
                    @Override
                    public byte[] invoke(byte[] requestBody) {
                        throw new RpcUnauthorizedException("not authorized by peer");
                    }
                };
            }
        };
        registry.register(new ServiceInstance(EchoApi.class.getName(), "127.0.0.1", 1));

        // 刻意用默认熔断配置：本用例锁的就是"它把配置类失败排除在失败率之外"
        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), unauthorizedPool, codec, SECRET);
        EchoApi api = factory.create(EchoApi.class);

        // 12 次 > minRequestThreshold(10)：若这些失败被计入，失败率 100% 必然 OPEN
        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> api.echo("x")).isInstanceOf(RpcUnauthorizedException.class);
        }
        assertThat(breakerState(api, factory))
                .as("握手/密钥类配置错误不该把链路打成熔断")
                .isEqualTo(CircuitBreakerState.CLOSED);
    }

    private CircuitBreakerState breakerState(EchoApi api, RpcProxyFactory factory) {
        return factory.breakerStateForTest(EchoApi.class);
    }

    @Test
    void poolEvictsClientsOfInstancesGoneFromRegistry() {
        RpcServiceExporter exporterA = new RpcServiceExporter(registry, codec, "127.0.0.1", SECRET);
        RpcServiceExporter exporterB = new RpcServiceExporter(registry, codec, "127.0.0.1", SECRET);
        exporterA.export(EchoApi.class, named("A"));
        exporterB.export(EchoApi.class, named("B"));
        exporterA.start();
        exporterB.start();

        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, SECRET);
        EchoApi api = factory.create(EchoApi.class);
        // 轮询 4 次：两个实例的连接都进池
        for (int i = 0; i < 4; i++) {
            api.echo("x");
        }
        assertThat(pool.keysForTest()).hasSize(2);

        // A 下线：注册中心推全量 → 钩子按"存活并集"剪掉 A 的连接，B 的保留
        exporterA.stop();
        assertThat(pool.keysForTest())
                .as("下线实例的连接被剪掉，仍在线的保留")
                .containsExactly("127.0.0.1:" + exporterB.port());
        // 剪完之后调用照常（只剩 B 一个实例）
        assertThat(api.echo("x")).startsWith("B:");
    }

    @Test
    void roundRobinDistributesAcrossTwoInstances() {
        RpcServiceExporter exporterA = new RpcServiceExporter(registry, codec, "127.0.0.1", SECRET);
        RpcServiceExporter exporterB = new RpcServiceExporter(registry, codec, "127.0.0.1", SECRET);
        exporterA.export(EchoApi.class, named("A"));
        exporterB.export(EchoApi.class, named("B"));
        exporterA.start();
        exporterB.start();

        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, SECRET);
        EchoApi api = factory.create(EchoApi.class);

        Set<String> prefixes = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 6; i++) {
            prefixes.add(api.echo("x").substring(0, 1));
        }
        assertThat(prefixes).as("轮询应覆盖两个实例").containsExactlyInAnyOrder("A", "B");
    }

    private EchoApi named(String prefix) {
        return new EchoApi() {
            @Override
            public String echo(String msg) {
                return prefix + ":" + msg;
            }

            @Override
            public int add(int a, int b) {
                return 0;
            }

            @Override
            public Pojo roundTrip(EchoApi.Pojo pojo) {
                return pojo;
            }

            @Override
            public void fireAndForget() {
            }

            @Override
            public String boom() {
                return prefix;
            }

            @Override
            public List<Pojo> listPojo() {
                return List.of();
            }
        };
    }
}
