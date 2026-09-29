package com.zengbohan.aurora.rpc.transport;

import com.zengbohan.aurora.rpc.protocol.MessageType;
import com.zengbohan.aurora.rpc.protocol.ProtocolCodec;
import com.zengbohan.aurora.rpc.protocol.RpcFrame;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * RPC 服务端：Netty 传输 + 内部密钥握手 + 请求分发到处理器。
 * <p>
 * 线程模型：boss 组（1 线程，接连接）+ worker 组（IO）+ 业务线程池（跑处理器）。
 * 业务处理与 IO 线程隔离，慢处理器不会阻塞读事件循环。
 */
public class RpcServer {

    /** 业务处理器：收到请求体，返回响应体；可抛异常（会被编码为错误 status 回传，不断连）。 */
    public interface RequestHandler {
        byte[] handle(byte[] requestBody) throws Exception;
    }

    /** 控制帧类型标记：body 前缀 C:ping / C:pong。 */
    private static final String PING = "C:ping";
    private static final String PONG = "C:pong";
    /** 握手：body 前缀 H:。 */
    private static final String HANDSHAKE_PREFIX = "H:";

    private final String internalSecret;
    private final RequestHandler handler;
    private final ExecutorService businessPool;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private volatile boolean running;
    private volatile boolean authenticatedClients; // 统计，仅测试观察
    private int port;

    public RpcServer(String internalSecret, RequestHandler handler) {
        this(internalSecret, handler, Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "rpc-server-biz");
            t.setDaemon(true);
            return t;
        }));
    }

    public RpcServer(String internalSecret, RequestHandler handler, ExecutorService businessPool) {
        this.internalSecret = internalSecret;
        this.handler = handler;
        this.businessPool = businessPool;
    }

    public synchronized int start() throws InterruptedException {
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
                                .addLast(new LengthFieldBasedFrameDecoder(
                                        ProtocolCodec.MAX_BODY_LENGTH + ProtocolCodec.HEADER_LENGTH,
                                        14, 4, 0, 0, true))
                                .addLast(new ServerFrameHandler());
                    }
                });
        // 绑定随机端口（0），由系统分配，测试互不冲突
        serverChannel = bootstrap.bind(0).sync().channel();
        port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        running = true;
        return port;
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
        if (businessPool != null) {
            businessPool.shutdownNow();
        }
        running = false;
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

    /** 服务端帧处理：握手鉴权 + 请求分发 + 心跳响应。 */
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
                String body = new String(frame.body(), StandardCharsets.UTF_8);
                if (body.startsWith(HANDSHAKE_PREFIX)) {
                    String secret = body.substring(HANDSHAKE_PREFIX.length());
                    if (internalSecret.equals(secret)) {
                        ctx.channel().attr(AttributeKeys.AUTHENTICATED).set(true);
                        authenticatedClients = true;
                    } else {
                        ctx.close(); // 密钥错误，断连
                    }
                } else if (body.startsWith(PING)) {
                    writeControl(ctx, frame.requestId(), PONG);
                }
                return;
            }
            if (frame.type() != MessageType.REQUEST) {
                return;
            }
            // 请求必须已通过内部密钥握手
            if (!Boolean.TRUE.equals(ctx.channel().attr(AttributeKeys.AUTHENTICATED).get())) {
                ctx.close();
                return;
            }
            long requestId = frame.requestId();
            byte[] requestBody = frame.body();
            businessPool.execute(() -> {
                byte[] responseBody;
                byte status;
                try {
                    responseBody = handler.handle(requestBody);
                    status = StatusCodes.OK;
                } catch (Throwable e) {
                    // 处理器异常以错误 status 回传，不断连（调用方可区分业务失败与不可用）
                    responseBody = String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8);
                    status = StatusCodes.ERROR;
                }
                RpcFrame response = RpcFrame.response(requestId, JsonSerializerCode.JSON, status, responseBody);
                ctx.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(ProtocolCodec.encode(response)));
            });
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                // 读空闲超时：对端失联，关连接
                ctx.close();
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        private void writeControl(ChannelHandlerContext ctx, long requestId, String body) {
            RpcFrame control = RpcFrame.request(requestId, MessageType.CONTROL, JsonSerializerCode.JSON,
                    body.getBytes(StandardCharsets.UTF_8));
            ctx.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(ProtocolCodec.encode(control)));
        }
    }
}
