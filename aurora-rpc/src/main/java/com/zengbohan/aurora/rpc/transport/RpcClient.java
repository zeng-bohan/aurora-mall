package com.zengbohan.aurora.rpc.transport;

import com.zengbohan.aurora.rpc.protocol.MessageType;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.protocol.RpcFrame;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RPC 客户端：连接管理 + 内部密钥握手 + requestId→Future 挂起 + 超时快速失败 + 心跳 + 断线重连。
 * <p>
 * 调用与连接解耦：每次 invoke 分配唯一 requestId，响应按 requestId 找回挂起的调用；
 * 断线时所有在途 Future 立即失败（而不是等超时），重连成功后后续调用自动走新连接。
 */
public class RpcClient {

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
    private volatile long lastResponseNanos; // 心跳/活动判定

    public RpcClient(String host, int port, String internalSecret) {
        this(host, port, internalSecret, 3000, 30_000, 500);
    }

    public RpcClient(String host, int port, String internalSecret,
            long requestTimeoutMillis, long heartbeatIntervalMillis, long reconnectBackoffMillis) {
        this.host = host;
        this.port = port;
        this.internalSecret = internalSecret;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.reconnectBackoffMillis = reconnectBackoffMillis;
    }

    public synchronized void start() {
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
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
            group = null;
        }
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
                                    .addLast(new LengthFieldBasedFrameDecoder(
                                            ProtocolCodec.MAX_BODY_LENGTH + ProtocolCodec.HEADER_LENGTH,
                                            14, 4, 0, 0, true))
                                    .addLast(new IdleStateHandler(
                                            (int) Math.min(heartbeatIntervalMillis * 3, Integer.MAX_VALUE), 0, 0)) // 读空闲：3 周期无响应视为失联
                                    .addLast(new ClientFrameHandler());
                        }
                    });
            // 异步连接：不能在 event-loop 线程上 sync()（该线程正是完成连接的线程，会自锁）
            bootstrap.connect(host, port).addListener((GenericFutureListener<Future<? super Void>>) f -> {
                if (f.isSuccess()) {
                    Channel newChannel = ((io.netty.channel.ChannelFuture) f).channel();
                    this.channel = newChannel;
                    sendHandshake(newChannel);
                    onConnected(newChannel);
                }
            });
        } catch (Exception e) {
            // 连接失败，退避后由定时任务重试
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

    private void maybeHeartbeat() {
        // 简化的读空闲检测交给 IdleStateHandler；这里发心跳保活
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            RpcFrame ping = RpcFrame.request(0L, MessageType.CONTROL, JsonSerializerCode.JSON,
                    PING.getBytes(StandardCharsets.UTF_8));
            ch.writeAndFlush(Unpooled.wrappedBuffer(ProtocolCodec.encode(ping)));
        }
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

    /** 连接丢失：在途调用全部失败（重连后自动恢复）。 */
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
            lastResponseNanos = System.nanoTime();
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
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
                // 读空闲：连接已失联，主动关闭触发重连
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    public long lastResponseNanos() {
        return lastResponseNanos;
    }
}
