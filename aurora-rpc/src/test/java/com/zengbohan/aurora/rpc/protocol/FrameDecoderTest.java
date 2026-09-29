package com.zengbohan.aurora.rpc.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拆包行为：用 Netty EmbeddedChannel 喂入拼接/切片后的字节流，验证
 * LengthFieldBasedFrameDecoder 的参数配置真的按 18 字节定长头 + bodyLength 工作。
 */
class FrameDecoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        // 拆包配置统一来自 ProtocolCodec 工厂：头布局改动只改一处
        channel = new EmbeddedChannel(ProtocolCodec.newFrameDecoder());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    private ByteBuf encodeBuf(RpcFrame frame) {
        return Unpooled.wrappedBuffer(ProtocolCodec.encode(frame));
    }

    @Test
    void singleFrameIsDecodedWhole() {
        channel.writeInbound(encodeBuf(RpcFrame.request(1L, MessageType.REQUEST, (byte) 1, new byte[]{7, 8})));
        ByteBuf in = channel.readInbound();
        assertThat(in).isNotNull();
        assertThat(in.readableBytes()).isEqualTo(ProtocolCodec.HEADER_LENGTH + 2);
        in.release();
    }

    @Test
    void twoFramesInOneReadAreSplit() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes(ProtocolCodec.encode(RpcFrame.request(1L, MessageType.REQUEST, (byte) 1, new byte[]{7})));
        buf.writeBytes(ProtocolCodec.encode(RpcFrame.request(2L, MessageType.REQUEST, (byte) 1, new byte[]{8, 9})));
        channel.writeInbound(buf);

        ByteBuf first = channel.readInbound();
        ByteBuf second = channel.readInbound();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        RpcFrame firstFrame = ProtocolCodec.decodeHeader(toBytes(first));
        RpcFrame secondFrame = ProtocolCodec.decodeHeader(toBytes(second));
        assertThat(firstFrame.requestId()).isEqualTo(1L);
        assertThat(secondFrame.requestId()).isEqualTo(2L);
        first.release();
        second.release();
        ByteBuf none = channel.readInbound();
        assertThat(none).isNull();
    }

    @Test
    void halfFrameWaitsForTheRest() {
        byte[] full = ProtocolCodec.encode(RpcFrame.request(3L, MessageType.REQUEST, (byte) 1, new byte[]{1, 2, 3}));
        // 先喂一半，不应产出帧
        channel.writeInbound(Unpooled.wrappedBuffer(full, 0, ProtocolCodec.HEADER_LENGTH));
        ByteBuf none = channel.readInbound();
        assertThat(none).isNull();
        // 再喂剩余部分，帧才完整
        channel.writeInbound(Unpooled.wrappedBuffer(full, ProtocolCodec.HEADER_LENGTH, full.length - ProtocolCodec.HEADER_LENGTH));
        ByteBuf in = channel.readInbound();
        assertThat(in).isNotNull();
        RpcFrame frame = ProtocolCodec.decodeHeader(toBytes(in));
        assertThat(frame.requestId()).isEqualTo(3L);
        in.release();
    }

    /** 把 ByteBuf 读成 byte[]（decodeHeader 需要）。 */
    private static byte[] toBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }
}
