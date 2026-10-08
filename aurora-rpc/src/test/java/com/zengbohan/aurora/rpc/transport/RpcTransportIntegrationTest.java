package com.zengbohan.aurora.rpc.transport;

import com.zengbohan.aurora.rpc.protocol.MessageType;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.protocol.RpcFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
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
            // 处理器异常=系统失败：ERROR status → RpcUnavailableException（不断连，计入熔断）
            assertThatThrownBy(() -> failingClient.invoke("x".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(RpcUnavailableException.class)
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
    void invokeRightAfterConnectionDropFailsFastNotByTimeout() throws Exception {
        // 断线事件先跑完、随后 invoke 才入 pending：活性复查让它立即失败而非等满超时
        server.stop();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && client.isConnected()) {
            Thread.sleep(50);
        }
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> client.invoke("x".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(RpcUnavailableException.class);
        long elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMillis).as("断连后的 invoke 应快速失败而非等满超时").isLessThan(500);
    }

    @Test
    void serverReapsIdleConnections() throws Exception {
        // 服务端读空闲回收：对端崩溃不发 FIN 时，半开连接不能永久占着文件描述符
        RpcServer reapServer = new RpcServer(SECRET, body -> body,
                java.util.concurrent.Executors.newFixedThreadPool(2), 300);
        int port = reapServer.start();
        // 重连退避设 10s：测试窗口内不重连，才能观察到"连接被服务端回收"
        RpcClient quiet = new RpcClient("127.0.0.1", port, SECRET, 2000, 5000, 10_000);
        quiet.start();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && !quiet.isConnected()) {
                Thread.sleep(50);
            }
            assertThat(quiet.isConnected()).isTrue();

            // 静默超过服务端空闲阈值（300ms），等待其回收
            long reapDeadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < reapDeadline && quiet.isConnected()) {
                Thread.sleep(50);
            }
            assertThat(quiet.isConnected()).as("服务端应在读空闲后回收连接").isFalse();
        } finally {
            quiet.stop();
            reapServer.stop();
        }
    }

    @Test
    void overloadIsRejectedWithStatusWithoutDroppingConnection() throws Exception {
        // 业务池 1 线程 + 队列 1：第 3 个起的请求应被 OVERLOADED 拒绝，而不是无界堆积
        ThreadPoolExecutor tinyPool = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1),
                r -> {
                    Thread t = new Thread(r, "rpc-test-biz");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch blockHandler = new CountDownLatch(1);
        RpcServer tinyServer = new RpcServer(SECRET, body -> {
            blockHandler.await();
            return body;
        }, tinyPool);
        int port = tinyServer.start();
        RpcClient burst = new RpcClient("127.0.0.1", port, SECRET, 5000, 5000, 200);
        burst.start();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && !burst.isConnected()) {
                Thread.sleep(50);
            }
            int parallel = 5;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(parallel);
            AtomicInteger overloaded = new AtomicInteger();
            AtomicInteger succeeded = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(parallel);
            for (int i = 0; i < parallel; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        burst.invoke("x".getBytes(StandardCharsets.UTF_8));
                        succeeded.incrementAndGet();
                    } catch (RpcUnavailableException e) {
                        if (String.valueOf(e.getMessage()).contains("overloaded")) {
                            overloaded.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 其他异常（超时等）不计
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            // 三个拒绝立即返回；两个被准入的调用阻塞在 handler 上——所以这里不能等全量 done，
            // 只等"拒绝数到位"，再放行 handler 收全量
            long settle = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < settle && overloaded.get() < 3) {
                Thread.sleep(50);
            }
            assertThat(overloaded.get()).as("池满的请求被 OVERLOADED 拒绝").isEqualTo(3);
            blockHandler.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(succeeded.get()).isEqualTo(2);

            // 拒绝不断连：释放后连接照常可用
            byte[] after = burst.invoke("after".getBytes(StandardCharsets.UTF_8));
            assertThat(new String(after, StandardCharsets.UTF_8)).isEqualTo("after");
        } finally {
            burst.stop();
            tinyServer.stop();
            tinyPool.shutdownNow();
        }
    }

    @Test
    void clientStopFailsPendingCallsImmediately() throws Exception {
        CountDownLatch blockHandler = new CountDownLatch(1);
        RpcServer slowServer = new RpcServer(SECRET, body -> {
            blockHandler.await();
            return body;
        });
        int port = slowServer.start();
        RpcClient stopping = new RpcClient("127.0.0.1", port, SECRET, 10_000, 5000, 200);
        stopping.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && !stopping.isConnected()) {
                Thread.sleep(50);
            }
            java.util.concurrent.Future<byte[]> pendingCall = caller.submit(
                    () -> stopping.invoke("x".getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(300); // 让请求发出、挂起在响应等待上

            long t0 = System.nanoTime();
            stopping.stop();
            try {
                pendingCall.get(2, TimeUnit.SECONDS);
                throw new AssertionError("pending call should fail on stop()");
            } catch (java.util.concurrent.ExecutionException e) {
                long elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
                // 不等 10s 超时：stop() 让在途调用立即失败
                assertThat(elapsedMillis).as("stop 应快速失败在途调用").isLessThan(1500);
                assertThat(e.getCause()).isInstanceOf(RpcUnavailableException.class);
            }
        } finally {
            caller.shutdownNow();
            blockHandler.countDown();
            stopping.stop();
            slowServer.stop();
        }
    }

    @Test
    void requestWithoutHandshakeGetsUnauthorizedResponse() throws Exception {
        // 协议级：不带握手直接发请求——服务端回 UNAUTHORIZED 错误帧再断连（客户端能拿到原因）
        try (java.net.Socket raw = new java.net.Socket("127.0.0.1", server.port())) {
            raw.setSoTimeout(2000);
            RpcFrame naked = RpcFrame.request(77L, MessageType.REQUEST, (byte) 1,
                    "hi".getBytes(StandardCharsets.UTF_8));
            raw.getOutputStream().write(ProtocolCodec.encode(naked));
            raw.getOutputStream().flush();

            byte[] resp = new byte[64];
            int read = raw.getInputStream().read(resp);
            assertThat(read).isGreaterThanOrEqualTo(ProtocolCodec.HEADER_LENGTH);
            byte[] frameBytes = java.util.Arrays.copyOf(resp, read);
            RpcFrame response = ProtocolCodec.decode(frameBytes);
            assertThat(response).isNotNull();
            assertThat(response.requestId()).isEqualTo(77L);
            assertThat(response.status()).isEqualTo(StatusCodes.UNAUTHORIZED);
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
    void sameClientReconnectsAfterServerRestart() throws Exception {
        // 同一个客户端：服务端重启（同端口）后，退避重连自动恢复调用——这才是断线重连的语义
        int port = freePort();
        RpcServer first = new RpcServer(SECRET, body -> body);
        first.start(port);
        RpcClient persistent = new RpcClient("127.0.0.1", port, SECRET, 2000, 5000, 100);
        persistent.start();
        try {
            assertThat(awaitFirstSuccess(persistent)).isTrue();

            first.stop(); // 服务端没了
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && persistent.isConnected()) {
                Thread.sleep(50);
            }

            // 同端口重启一个新 server 实例
            RpcServer second = new RpcServer(SECRET,
                    body -> ("re:" + new String(body, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8));
            second.start(port);
            try {
                // 不新建客户端：等重连成功、调用恢复
                deadline = System.currentTimeMillis() + 5000;
                boolean recovered = false;
                while (System.currentTimeMillis() < deadline && !recovered) {
                    try {
                        byte[] response = persistent.invoke("ok".getBytes(StandardCharsets.UTF_8));
                        assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo("re:ok");
                        recovered = true;
                    } catch (RpcException e) {
                        Thread.sleep(100);
                    }
                }
                assertThat(recovered).as("同一客户端应在服务端重启后自动重连并恢复调用").isTrue();
            } finally {
                second.stop();
            }
        } finally {
            persistent.stop();
        }
    }

    // 等到首次调用成功（连接 + 握手就绪）。
    private boolean awaitFirstSuccess(RpcClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            try {
                client.invoke("ping".getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (RpcException e) {
                Thread.sleep(50);
            }
        }
        return false;
    }

    // 申请一个空闲端口（绑定后立即释放，存在理论上的竞态，测试场景可接受）。
    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void heartbeatLossDetectsDeadConnection() throws Exception {
        // 客户端读空闲检测：连上一个"只收不发"的死端（如对端进程僵死），
        // 3 个心跳周期无任何响应后应主动断开（触发重连），而不是永远挂着
        try (java.net.ServerSocket deadServer = new java.net.ServerSocket(0)) {
            int port = deadServer.getLocalPort();
            // 接受连接并保持打开、永不响应——模拟对端进程僵死（TCP 通但应用层死了）
            java.util.List<java.net.Socket> held = new java.util.ArrayList<>();
            Thread acceptor = new Thread(() -> {
                while (!deadServer.isClosed()) {
                    try {
                        held.add(deadServer.accept());
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            // 心跳 100ms → 读空闲阈值 300ms
            RpcClient doomed = new RpcClient("127.0.0.1", port, SECRET, 2000, 100, 10_000);
            doomed.start();
            try {
                long deadline = System.currentTimeMillis() + 3000;
                boolean sawConnection = false;
                while (System.currentTimeMillis() < deadline) {
                    if (doomed.isConnected()) {
                        sawConnection = true;
                    }
                    // 连上后 read-idle 到期应断开；若从未连上也算失败（没走到检测路径）
                    if (sawConnection && !doomed.isConnected()) {
                        break;
                    }
                    Thread.sleep(50);
                }
                assertThat(sawConnection).as("应先成功建立连接").isTrue();
                assertThat(doomed.isConnected()).as("心跳失联后应主动断开").isFalse();
            } finally {
                doomed.stop();
                for (java.net.Socket s : held) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                        // 收尾
                    }
                }
            }
        }
    }
}
