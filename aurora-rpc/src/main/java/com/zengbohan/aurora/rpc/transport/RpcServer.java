package com.zengbohan.aurora.rpc.transport;

import com.zengbohan.aurora.rpc.protocol.ControlFrames;
import com.zengbohan.aurora.rpc.protocol.MessageType;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.protocol.RpcFrame;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RPC 服务端：Netty 传输 + 内部密钥握手 + 请求分发到处理器。
 * <p>
 * 线程模型：boss 组（1 线程，接连接）+ worker 组（IO）+ 业务线程池（跑处理器）。
 * 业务处理与 IO 线程隔离，慢处理器不会阻塞读事件循环。
 * <p>
 * 过载与回收：业务池为有界队列，打满时以 OVERLOADED status 拒绝（不断连、不堆积内存）；
 * 读空闲连接由 IdleStateHandler 回收——客户端崩溃不发 FIN 时不会泄漏文件描述符。
 */
public class RpcServer {

    private static final Logger log = System.getLogger(RpcServer.class.getName());

    // 业务处理器：收到请求体，返回响应体；可抛异常（会被编码为错误 status 回传，不断连）。
    public interface RequestHandler {
        byte[] handle(byte[] requestBody) throws Exception;
    }


    // 默认读空闲回收阈值：3 个客户端心跳周期（客户端默认 30s 心跳）。
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 90_000;
    // 默认业务池：8 线程 + 256 有界队列。
    private static final int DEFAULT_POOL_THREADS = 8;
    private static final int DEFAULT_POOL_QUEUE = 256;

    private final String internalSecret;
    private final RequestHandler handler;
    private final ExecutorService businessPool;
    // 业务池是否由本服务端创建（创建的才由 stop() 关闭，注入的归注入方管）。
    private final boolean ownsBusinessPool;
    private final long idleTimeoutMillis;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private int port;

    public RpcServer(String internalSecret, RequestHandler handler) {
        this(internalSecret, handler, defaultBusinessPool(), DEFAULT_IDLE_TIMEOUT_MILLIS, true);
    }

    public RpcServer(String internalSecret, RequestHandler handler, ExecutorService businessPool) {
        this(internalSecret, handler, businessPool, DEFAULT_IDLE_TIMEOUT_MILLIS, false);
    }

    public RpcServer(String internalSecret, RequestHandler handler, ExecutorService businessPool,
            long idleTimeoutMillis) {
        this(internalSecret, handler, businessPool, idleTimeoutMillis, false);
    }

    private RpcServer(String internalSecret, RequestHandler handler, ExecutorService businessPool,
            long idleTimeoutMillis, boolean ownsBusinessPool) {
        if (internalSecret == null || internalSecret.isEmpty()) {
            throw new IllegalArgumentException("internalSecret must not be empty");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (businessPool == null) {
            throw new IllegalArgumentException("businessPool must not be null");
        }
        if (idleTimeoutMillis <= 0) {
            throw new IllegalArgumentException("idleTimeoutMillis must be positive: " + idleTimeoutMillis);
        }
        this.internalSecret = internalSecret;
        this.handler = handler;
        this.businessPool = businessPool;
        this.ownsBusinessPool = ownsBusinessPool;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    private static ExecutorService defaultBusinessPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(DEFAULT_POOL_THREADS, DEFAULT_POOL_THREADS,
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(DEFAULT_POOL_QUEUE),
                threadFactory("rpc-server-biz"), new ThreadPoolExecutor.AbortPolicy());
        // 核心线程也预启：避免首批请求碰上线程冷启动延迟
        pool.prestartAllCoreThreads();
        return pool;
    }

    public synchronized int start() throws InterruptedException {
        return start(0); // 0 = 随机端口，测试互不冲突
    }

    // 绑定指定端口启动（0 = 随机端口）；重启同端口场景用。
    public synchronized int start(int port) throws InterruptedException {
        if (this.serverChannel != null) {
            throw new IllegalStateException("server already started on port " + this.port);
        }
        boss = new NioEventLoopGroup(1, threadFactory("rpc-server-boss"));
        worker = new NioEventLoopGroup(2, threadFactory("rpc-server-io"));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(ProtocolCodec.newFrameDecoder())
                                // 读空闲回收：对端崩溃不发 FIN 时，半开连接靠它兜底
                                .addLast(new IdleStateHandler(idleTimeoutMillis, 0, 0, TimeUnit.MILLISECONDS))
                                .addLast(new ServerFrameHandler());
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        this.port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        return this.port;
    }

    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
            boss = null;
        }
        if (worker != null) {
            worker.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
            worker = null;
        }
        if (ownsBusinessPool && businessPool != null) {
            businessPool.shutdownNow();
        }
    }

    public int port() {
        return port;
    }

    private static ThreadFactory threadFactory(String name) {
        return runnable -> {
            Thread t = new Thread(runnable, name);
            t.setDaemon(true);
            return t;
        };
    }

    // 服务端帧处理：握手鉴权 + 请求分发 + 心跳响应 + 空闲回收。
    private class ServerFrameHandler extends SimpleChannelInboundHandler<io.netty.buffer.ByteBuf> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf buf) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            RpcFrame frame = ProtocolCodec.decode(bytes);
            if (frame == null) {
                ctx.close(); // 坏帧直接关连接（解析层不抛异常）
                return;
            }
            if (frame.type() == MessageType.CONTROL) {
                handleControl(ctx, frame);
                return;
            }
            if (frame.type() != MessageType.REQUEST) {
                return;
            }
            // 请求必须已通过内部密钥握手；先回错误帧再断连，让客户端能拿到原因
            if (!Boolean.TRUE.equals(ctx.channel().attr(AttributeKeys.AUTHENTICATED).get())) {
                respond(ctx, frame, StatusCodes.UNAUTHORIZED, "not authenticated: missing or bad handshake");
                ctx.close();
                return;
            }
            dispatch(ctx, frame);
        }

        private void handleControl(ChannelHandlerContext ctx, RpcFrame frame) {
            String body = new String(frame.body(), StandardCharsets.UTF_8);
            if (body.startsWith(ControlFrames.HANDSHAKE_PREFIX)) {
                String secret = body.substring(ControlFrames.HANDSHAKE_PREFIX.length());
                // 常量时间比较：共享密钥不走 String.equals 的短路路径
                if (java.security.MessageDigest.isEqual(
                        internalSecret.getBytes(StandardCharsets.UTF_8),
                        secret.getBytes(StandardCharsets.UTF_8))) {
                    ctx.channel().attr(AttributeKeys.AUTHENTICATED).set(true);
                } else {
                    ctx.close(); // 密钥错误，断连
                }
            } else if (body.startsWith(ControlFrames.PING)) {
                writeControl(ctx, frame.serializerCode(), frame.requestId(), ControlFrames.PONG);
            }
        }

        // 派发到业务池；池满时以 OVERLOADED 回绝（不堆积内存、不断连），调用方可退避重试。
        private void dispatch(ChannelHandlerContext ctx, RpcFrame frame) {
            long requestId = frame.requestId();
            byte serializerCode = frame.serializerCode();
            byte[] requestBody = frame.body();
            try {
                businessPool.execute(() -> {
                    byte[] responseBody;
                    byte status;
                    try {
                        responseBody = handler.handle(requestBody);
                        status = StatusCodes.OK;
                    } catch (Throwable e) {
                        // 处理器异常以错误 status 回传，不断连（调用方可区分业务失败与不可用）
                        log.log(Level.WARNING, "rpc handler failed", e);
                        responseBody = String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8);
                        status = StatusCodes.ERROR;
                    }
                    respondWith(ctx, requestId, serializerCode, status, responseBody);
                });
            } catch (RejectedExecutionException e) {
                respondWith(ctx, requestId, serializerCode, StatusCodes.OVERLOADED,
                        "server overloaded: business pool saturated".getBytes(StandardCharsets.UTF_8));
            }
        }

        private void respond(ChannelHandlerContext ctx, RpcFrame request, byte status, String message) {
            respondWith(ctx, request.requestId(), request.serializerCode(), status,
                    message.getBytes(StandardCharsets.UTF_8));
        }

        // 响应回显请求的 serializerCode：协议两端按请求协商实现，不在响应上单方面改写。
        private void respondWith(ChannelHandlerContext ctx, long requestId, byte serializerCode,
                byte status, byte[] body) {
            RpcFrame response = RpcFrame.response(requestId, serializerCode, status, body);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(ProtocolCodec.encode(response)));
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                // 读空闲超时：对端失联，关连接回收资源
                log.log(Level.DEBUG, "rpc server reaps idle connection " + ctx.channel().remoteAddress());
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // 管道异常（如拆包器 TooLongFrame）只影响当前连接，记日志后关闭
            log.log(Level.WARNING, "rpc server pipeline error on " + ctx.channel().remoteAddress()
                    + ", closing: " + cause);
            ctx.close();
        }

        private void writeControl(ChannelHandlerContext ctx, byte serializerCode, long requestId, String body) {
            RpcFrame control = RpcFrame.request(requestId, MessageType.CONTROL, serializerCode,
                    body.getBytes(StandardCharsets.UTF_8));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(ProtocolCodec.encode(control)));
        }
    }
}
