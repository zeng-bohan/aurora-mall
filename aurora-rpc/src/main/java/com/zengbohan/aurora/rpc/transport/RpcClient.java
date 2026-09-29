package com.zengbohan.aurora.rpc.transport;

import com.zengbohan.aurora.rpc.protocol.MessageType;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.protocol.RpcFrame;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RPC 客户端：连接管理 + 内部密钥握手 + requestId→Future 挂起 + 超时快速失败 + 心跳 + 断线重连。
 * <p>
 * 调用与连接解耦：每次 invoke 分配唯一 requestId，响应按 requestId 找回挂起的调用；
 * 断线时所有在途 Future 立即失败（而不是等超时），重连成功后后续调用自动走新连接。
 * 心跳节奏独立于重连退避：连接空闲超过心跳间隔才发 ping，不随退避周期抖动。
 */
public class RpcClient {

    private static final Logger log = System.getLogger(RpcClient.class.getName());

    private static final String PING = "C:ping";

    private final String host;
    private final int port;
    private final String internalSecret;
    private final long requestTimeoutMillis;
    private final long heartbeatIntervalMillis;
    private final long reconnectBackoffMillis;

    private EventLoopGroup group;
    private volatile Channel channel;
    /** requestId → 在途调用的响应 promise。 */
    private final Map<Long, DefaultPromise<byte[]>> pending = new ConcurrentHashMap<>();
    private final AtomicLong requestIdSeq = new AtomicLong();
    private volatile boolean running;
    /** 上次发 ping 的时刻（纳秒），实现心跳间隔与重连退避解耦。 */
    private volatile long lastPingNanos;

    public RpcClient(String host, int port, String internalSecret) {
        this(host, port, internalSecret, 3000, 30_000, 500);
    }

    public RpcClient(String host, int port, String internalSecret,
            long requestTimeoutMillis, long heartbeatIntervalMillis, long reconnectBackoffMillis) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (requestTimeoutMillis <= 0 || heartbeatIntervalMillis <= 0 || reconnectBackoffMillis <= 0) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.host = host;
        this.port = port;
        this.internalSecret = internalSecret;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.reconnectBackoffMillis = reconnectBackoffMillis;
    }

    public synchronized void start() {
        if (group != null) {
            return; // 幂等：重复 start 不重建线程组
        }
        group = new NioEventLoopGroup(1, runnable -> {
            Thread t = new Thread(runnable, "rpc-client-io");
            t.setDaemon(true);
            return t;
        });
        running = true;
        group.scheduleWithFixedDelay(this::connectOrRecover, 0, reconnectBackoffMillis,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;
        // 先让在途调用立即失败：若等 channelInactive 触发，线程组可能先死、调用方会白等整个超时
        failAllPending(new RpcUnavailableException("rpc client stopped"));
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
            group = null;
        }
    }

    /** 当前是否持有活跃连接（健康检查/测试观察用）。 */
    public boolean isConnected() {
        Channel ch = channel;
        return ch != null && ch.isActive();
    }

    private void connectOrRecover() {
        if (!running) {
            return;
        }
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            maybeHeartbeat();
            return;
        }
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(ProtocolCodec.newFrameDecoder())
                                    // 读空闲：3 个心跳周期无任何响应视为失联，关连接触发重连
                                    .addLast(new IdleStateHandler(
                                            (int) Math.min(heartbeatIntervalMillis * 3, Integer.MAX_VALUE),
                                            0, 0, TimeUnit.MILLISECONDS))
                                    .addLast(new ClientFrameHandler());
                        }
                    });
            // 异步连接：不能在 event-loop 线程上 sync()（该线程正是完成连接的线程，会自锁）
            bootstrap.connect(host, port).addListener((GenericFutureListener<Future<? super Void>>) f -> {
                if (f.isSuccess()) {
                    Channel newChannel = ((ChannelFuture) f).channel();
                    this.channel = newChannel;
                    this.lastPingNanos = System.nanoTime();
                    sendHandshake(newChannel);
                    onConnected(newChannel);
                } else {
                    // 连接失败：DEBUG 记录（服务未起是常态路径），退避后由定时任务重试
                    log.log(Level.DEBUG, "rpc connect to " + host + ":" + port
                            + " failed, will retry: " + f.cause());
                }
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "rpc connect setup error: " + e);
        }
    }

    private void sendHandshake(Channel ch) {
        RpcFrame handshake = RpcFrame.request(0L, MessageType.CONTROL, JsonSerializerCode.JSON,
                ("H:" + internalSecret).getBytes(StandardCharsets.UTF_8));
        ch.writeAndFlush(Unpooled.wrappedBuffer(ProtocolCodec.encode(handshake)));
    }

    /** 子类/测试钩子：连接就绪回调。 */
    protected void onConnected(Channel ch) {
    }

    /** 空闲超过心跳间隔才发 ping：请求本身在流动时不额外打扰。 */
    private void maybeHeartbeat() {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastPingNanos < heartbeatIntervalMillis * 1_000_000L) {
            return;
        }
        lastPingNanos = now;
        RpcFrame ping = RpcFrame.request(0L, MessageType.CONTROL, JsonSerializerCode.JSON,
                PING.getBytes(StandardCharsets.UTF_8));
        ch.writeAndFlush(Unpooled.wrappedBuffer(ProtocolCodec.encode(ping)));
    }

    /**
     * 发起一次调用：发送请求体，等待响应。超时/断线快速失败。
     */
    public byte[] invoke(byte[] requestBody) throws RpcUnavailableException, RpcTimeoutException {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            throw new RpcUnavailableException("not connected to " + host + ":" + port);
        }
        long requestId = requestIdSeq.incrementAndGet();
        DefaultPromise<byte[]> promise = new DefaultPromise<>(ch.eventLoop());
        pending.put(requestId, promise);

        RpcFrame request = RpcFrame.request(requestId, MessageType.REQUEST, JsonSerializerCode.JSON, requestBody);
        // pipeline 以 ByteBuf 为消息类型（LengthFieldBasedFrameDecoder 之后），出站需包一层
        ch.writeAndFlush(Unpooled.wrappedBuffer(ProtocolCodec.encode(request)))
                .addListener((GenericFutureListener<Future<? super Void>>) f -> {
                    if (!f.isSuccess()) {
                        // 发送失败：立即失败该调用，不等超时
                        DefaultPromise<byte[]> p = pending.remove(requestId);
                        if (p != null) {
                            p.tryFailure(new RpcUnavailableException("send failed: " + f.cause()));
                        }
                    }
                });

        try {
            return promise.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RpcTimeoutException("rpc call timed out after " + requestTimeoutMillis + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RpcException) {
                throw (RpcException) cause;
            }
            throw new RpcUnavailableException("rpc call failed: " + cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcUnavailableException("interrupted");
        } finally {
            pending.remove(requestId);
        }
    }

    /** 在途调用全部立即失败（断线/停止时调用；按 key 移除保证幂等）。 */
    void failAllPending(Throwable cause) {
        for (Map.Entry<Long, DefaultPromise<byte[]>> entry : pending.entrySet()) {
            DefaultPromise<byte[]> p = pending.remove(entry.getKey());
            if (p != null) {
                p.tryFailure(cause);
            }
        }
    }

    private class ClientFrameHandler extends SimpleChannelInboundHandler<io.netty.buffer.ByteBuf> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf buf) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            RpcFrame frame = ProtocolCodec.decode(bytes);
            if (frame == null) {
                ctx.close();
                return;
            }
            if (frame.type() == MessageType.CONTROL) {
                return; // pong，忽略
            }
            if (frame.type() == MessageType.RESPONSE) {
                DefaultPromise<byte[]> promise = pending.get(frame.requestId());
                if (promise == null) {
                    return; // 已超时移除，丢弃迟到响应
                }
                if (frame.status() == StatusCodes.OK) {
                    promise.trySuccess(frame.body());
                } else {
                    promise.tryFailure(new RpcRemoteException(
                            new String(frame.body(), StandardCharsets.UTF_8)));
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            channel = null;
            failAllPending(new RpcUnavailableException("connection lost"));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                // 读空闲：连接已失联，主动关闭触发重连
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.log(Level.DEBUG, "rpc client pipeline error, closing: " + cause);
            ctx.close();
        }
    }
}
