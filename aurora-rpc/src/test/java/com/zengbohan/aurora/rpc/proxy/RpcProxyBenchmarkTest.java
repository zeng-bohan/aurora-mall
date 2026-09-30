package com.zengbohan.aurora.rpc.proxy;

import com.zengbohan.aurora.rpc.lb.RoundRobinLoadBalancer;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.registry.InMemoryRegistry;
import com.zengbohan.aurora.rpc.registry.ServiceDiscovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微基准：全链路代理往返（发现缓存命中 → 均衡 → 编解码 → 真 Netty 往返）。
 * 非 JMH（本机内存受限），回答"量级是否可用"；数字记入 docs/m3-rpc-comparison.md。
 */
class RpcProxyBenchmarkTest {

    interface BenchApi {
        String echo(String msg);
    }

    private final InMemoryRegistry registry = new InMemoryRegistry();
    private final ProtocolCodec codec = new ProtocolCodec();
    private final RpcClientPool pool = new RpcClientPool("bench-secret", 3000);
    private final RpcServiceExporter exporter = new RpcServiceExporter(registry, codec, "127.0.0.1", "bench-secret");

    @AfterEach
    void tearDown() {
        pool.close();
        exporter.stop();
    }

    @Test
    void proxyRoundTripThroughputIsSane() throws Exception {
        exporter.export(BenchApi.class, (BenchApi) msg -> msg);
        exporter.start();

        ServiceDiscovery discovery = new ServiceDiscovery(registry);
        RpcProxyFactory factory = new RpcProxyFactory(discovery,
                new RoundRobinLoadBalancer(), pool, codec, "bench-secret");
        BenchApi api = factory.create(BenchApi.class);
        api.echo("warmup"); // 连接暖机 + JIT 预热

        int threads = 8;
        long callsPerThread = 5_000;
        AtomicLong counter = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (long c = 0; c < callsPerThread; c++) {
                        if (api.echo("payload-" + c).startsWith("payload-")) {
                            counter.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        long elapsedNanos = System.nanoTime() - t0;
        pool.shutdownNow();

        long total = threads * callsPerThread;
        assertThat(counter.get()).isEqualTo(total); // 全部成功才算数
        double opsPerSecond = total / (elapsedNanos / 1_000_000_000.0);
        System.out.printf(Locale.ROOT,
                "[bench] rpc proxy round-trip: %d calls in %dms -> %.0f ops/s (%d threads)%n",
                total, elapsedNanos / 1_000_000, opsPerSecond, threads);
        assertThat(opsPerSecond).as("单机全链路吞吐量级健康下限").isGreaterThan(1_000);
    }
}
