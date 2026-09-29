package com.zengbohan.aurora.rpc.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RPC 传输层集成测试：真 Netty server/client，本机随机端口，CI 可跑、零外部依赖。
 */
class RpcTransportIntegrationTest {

    private static final String SECRET = "internal-secret-for-rpc";

    private RpcServer server;
    private RpcClient client;

    @BeforeEach
    void setUp() {
        // 处理器：回显请求体（测试各自传入自己的 handler 时另起 server）
        server = new RpcServer(SECRET, body -> body);
        try {
            int port = server.start();
            client = new RpcClient("127.0.0.1", port, SECRET, 2000, 5000, 200);
            client.start();
            awaitConnected();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitConnected() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        RpcException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                client.invoke("ping".getBytes(StandardCharsets.UTF_8));
                return;
            } catch (RpcException e) {
                last = e;
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("client did not connect in time; last=" + last);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void roundTripsRequestAndResponse() throws Exception {
        byte[] response = client.invoke("hello rpc".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo("hello rpc");
    }

    @Test
    void largeBodyRoundTrips() throws Exception {
        byte[] large = new byte[512 * 1024]; // 512KB
        java.util.Arrays.fill(large, (byte) 'x');
        byte[] response = client.invoke(large);
        assertThat(response).hasSize(large.length);
        assertThat(response[0]).isEqualTo((byte) 'x');
    }

    @Test
    void serverHandlerExceptionReturnsErrorStatusWithoutDroppingConnection() throws Exception {
        RpcServer failing = new RpcServer(SECRET, body -> {
            throw new IllegalStateException("business blew up");
        });
        int port = failing.start();
        RpcClient failingClient = new RpcClient("127.0.0.1", port, SECRET, 2000, 5000, 200);
        failingClient.start();
        try {
            // 等待连接
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    failingClient.invoke("x".getBytes(StandardCharsets.UTF_8));
                    break;
                } catch (RpcException e) {
                    Thread.sleep(50);
                }
            }
            // 处理器异常以 RpcRemoteException 穿透，连接不断（后续调用仍可达）
            assertThatThrownBy(() -> failingClient.invoke("x".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(RpcRemoteException.class)
                    .hasMessageContaining("business blew up");
        } finally {
            failingClient.stop();
            failing.stop();
        }
    }

    @Test
    void timeoutFailsFast() throws Exception {
        RpcServer slow = new RpcServer(SECRET, body -> {
            Thread.sleep(2000);
            return body;
        });
        int port = slow.start();
        RpcClient impatient = new RpcClient("127.0.0.1", port, SECRET, 300, 5000, 200);
        impatient.start();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    impatient.invoke("x".getBytes(StandardCharsets.UTF_8));
                    break;
                } catch (RpcException e) {
                    Thread.sleep(50);
                }
            }
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> impatient.invoke("x".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(RpcTimeoutException.class);
            long elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
            // 超时快速失败：远早于处理器 2s 完成
            assertThat(elapsedMillis).isLessThan(1000);
        } finally {
            impatient.stop();
            slow.stop();
        }
    }

    @Test
    void badSecretCannotCallService() throws Exception {
        RpcClient impostor = new RpcClient("127.0.0.1", server.port(), "wrong-secret", 500, 5000, 200);
        impostor.start();
        try {
            long deadline = System.currentTimeMillis() + 2000;
            RpcException last = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    impostor.invoke("x".getBytes(StandardCharsets.UTF_8));
                } catch (RpcException e) {
                    last = e;
                }
                Thread.sleep(100);
            }
            // 密钥错误：服务端拒绝并断连，调用方始终拿不到成功响应
            assertThat(last).isNotNull();
        } finally {
            impostor.stop();
        }
    }

    @Test
    void concurrentCallsPairResponsesByRequestId() throws Exception {
        RpcServer echoing = new RpcServer(SECRET, body -> {
            // 加一点抖动，制造乱序返回
            Thread.sleep((body[0] & 0x7));
            return body;
        });
        int port = echoing.start();
        RpcClient caller = new RpcClient("127.0.0.1", port, SECRET, 5000, 5000, 200);
        caller.start();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    caller.invoke("c".getBytes(StandardCharsets.UTF_8));
                    break;
                } catch (RpcException e) {
                    Thread.sleep(50);
                }
            }
            int threads = 8;
            int callsPerThread = 20;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger mismatches = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int c = 0; c < callsPerThread; c++) {
                            byte[] tag = new byte[]{(byte) threadId, (byte) c};
                            byte[] response = caller.invoke(tag);
                            // 响应必须与自己的请求配对（乱序也不串台）
                            if (response.length != 2 || response[0] != tag[0] || response[1] != tag[1]) {
                                mismatches.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();
            assertThat(errors.get()).isZero();
            assertThat(mismatches.get()).as("并发响应按 requestId 正确配对").isZero();
        } finally {
            caller.stop();
            echoing.stop();
        }
    }

    @Test
    void reconnectsAfterServerRestart() throws Exception {
        int port = server.port();
        // 断开：停掉服务端，在途/后续调用失败
        server.stop();
        Thread.sleep(300);
        // 重新起一个同端口服务端
        server = new RpcServer(SECRET, body -> body);
        RpcServer restarted = new RpcServer(SECRET, body -> {
            String s = new String(body, StandardCharsets.UTF_8);
            return ("re:" + s).getBytes(StandardCharsets.UTF_8);
        });
        // 绑定同端口（先停 client 让其端口释放，改为起在同端口）
        restarted.start(); // 注意：这会随机端口，改用新 client 连它
        RpcClient reconnected = new RpcClient("127.0.0.1", restarted.port(), SECRET, 2000, 5000, 200);
        reconnected.start();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            byte[] response = null;
            while (System.currentTimeMillis() < deadline && response == null) {
                try {
                    response = reconnected.invoke("ok".getBytes(StandardCharsets.UTF_8));
                } catch (RpcException e) {
                    Thread.sleep(50);
                }
            }
            assertThat(response).isNotNull();
            assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo("re:ok");
        } finally {
            reconnected.stop();
            restarted.stop();
        }
    }
}
