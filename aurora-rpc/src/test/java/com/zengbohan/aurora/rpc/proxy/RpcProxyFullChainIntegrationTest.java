package com.zengbohan.aurora.rpc.proxy;

import com.zengbohan.aurora.ratelimit.circuit.CircuitBreakerConfig;
import com.zengbohan.aurora.ratelimit.circuit.CircuitOpenException;
import com.zengbohan.aurora.rpc.lb.RoundRobinLoadBalancer;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.registry.InMemoryRegistry;
import com.zengbohan.aurora.rpc.registry.ServiceDiscovery;
import com.zengbohan.aurora.rpc.transport.RpcRemoteException;
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

    /** 样例服务接口。 */
    interface EchoApi {
        String echo(String msg);

        int add(int a, int b);

        Pojo roundTrip(EchoApi.Pojo pojo);

        void fireAndForget();

        String boom();

        /** 泛型容器返回值：回归 List<ProductSnapshot> 形态的擦除还原。 */
        List<Pojo> listPojo();

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
                throw new IllegalArgumentException("bad input");
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

        // 两次业务失败（100% ≥ 50%）→ OPEN
        assertThatThrownBy(() -> api.echo("a")).isInstanceOf(RpcRemoteException.class);
        assertThatThrownBy(() -> api.echo("b")).isInstanceOf(RpcRemoteException.class);
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
