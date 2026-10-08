package com.zengbohan.aurora.rpc.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 协议编解码：定长 18 字节头往返，粘包/半包由 frame codec 处理，坏帧防御。
 */
class ProtocolCodecTest {

    @Test
    void headerRoundTrips() {
        RpcFrame original = RpcFrame.request(42L, MessageType.REQUEST, (byte) 1, new byte[]{1, 2, 3});
        byte[] encoded = ProtocolCodec.encode(original);

        assertThat(encoded).hasSize(ProtocolCodec.HEADER_LENGTH + 3);
        FrameHeader header = ProtocolCodec.decodeHeader(encoded);
        assertThat(header.requestId()).isEqualTo(42L);
        assertThat(header.type()).isEqualTo(MessageType.REQUEST);
        assertThat(header.serializerCode()).isEqualTo((byte) 1);
        assertThat(header.status()).isEqualTo((byte) 0);
        assertThat(header.bodyLength()).isEqualTo(3);
    }

    @Test
    void decodeExtractsTheRealBodyNotAPlaceholder() throws Exception {
        // decodeHeader 只产头元数据（不碰 body），decode 才切出真实 body——
        // 传输层必须用 decode；坏帧头声明再大也不会被分配成实际内存
        byte[] body = new byte[]{9, 8, 7};
        byte[] full = ProtocolCodec.encode(RpcFrame.request(5L, MessageType.REQUEST, (byte) 1, body));

        FrameHeader headerOnly = ProtocolCodec.decodeHeader(full);
        assertThat(headerOnly.bodyLength()).isEqualTo(3);
        assertThat(headerOnly.requestId()).isEqualTo(5L);

        RpcFrame decoded = ProtocolCodec.decode(full);
        assertThat(decoded.body()).containsExactly(9, 8, 7);
        assertThat(decoded.requestId()).isEqualTo(5L);
    }

    @Test
    void unknownSerializerCodeFailsFast() {
        ProtocolCodec codec = ProtocolCodec.defaultCodec();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.serializer((byte) 99))
                .withMessageContaining("unknown serializer");
    }

    @Test
    void decodeRejectsTruncatedBody() {
        byte[] full = ProtocolCodec.encode(RpcFrame.request(1L, MessageType.REQUEST, (byte) 1, new byte[]{1, 2, 3}));
        byte[] truncated = java.util.Arrays.copyOf(full, full.length - 1);
        assertThat(ProtocolCodec.decode(truncated)).isNull();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RpcFrame.response(1L, (byte) 1, (byte) 0, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RpcFrame.request(1L, null, (byte) 1, new byte[0]));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RpcFrame.request(1L, MessageType.REQUEST, (byte) 1, null));
    }

    @Test
    void badMagicIsRejected() {
        byte[] frame = ProtocolCodec.encode(RpcFrame.request(1L, MessageType.REQUEST, (byte) 1, new byte[]{9}));
        frame[0] = (byte) 0x00; // 破坏 magic

        assertThat(ProtocolCodec.decodeHeader(frame)).isNull();
    }

    @Test
    void oversizedBodyIsRejected() {
        // 正溢出分支：bodyLength = MAX+1（长度字段在 offset 14）
        byte[] header = new byte[ProtocolCodec.HEADER_LENGTH];
        ProtocolCodec.writeMagic(header);
        header[2] = MessageType.REQUEST.code();
        header[3] = (byte) 1;
        writeLong(header, 6, 1L);
        writeInt(header, 14, ProtocolCodec.MAX_BODY_LENGTH + 1);
        assertThat(ProtocolCodec.decodeHeader(header)).isNull();

        // 负数分支：高位为 1 的 int
        byte[] negative = new byte[ProtocolCodec.HEADER_LENGTH];
        ProtocolCodec.writeMagic(negative);
        negative[2] = MessageType.REQUEST.code();
        negative[3] = (byte) 1;
        writeLong(negative, 6, 1L);
        writeInt(negative, 14, -1);
        assertThat(ProtocolCodec.decodeHeader(negative)).isNull();
    }

    @Test
    void serializerIsPluggable() throws Exception {
        // SPI：默认 JSON（code 1），可注册第二个实现并按 code 取用
        ProtocolCodec codec = ProtocolCodec.defaultCodec();
        byte[] json = codec.serialize("hello");
        assertThat(json).isNotEmpty();
        assertThat(codec.deserialize(json, String.class)).isEqualTo("hello");

        codec.register(new UppercaseStubSerializer());
        byte[] stub = codec.serialize("hello", UppercaseStubSerializer.CODE);
        assertThat(codec.deserialize(stub, String.class, UppercaseStubSerializer.CODE))
                .isEqualTo("HELLO");
    }

    @Test
    void nestedAndCollectionPayloadsRoundTrip() throws Exception {
        ProtocolCodec codec = ProtocolCodec.defaultCodec();
        List<String> payload = List.of("a", "b", "c");
        byte[] bytes = codec.serialize(payload);
        assertThat(codec.deserialize(bytes, List.class)).isEqualTo(payload);
    }

    @Test
    void nestedObjectAndMapPayloadsRoundTrip() throws Exception {
        ProtocolCodec codec = ProtocolCodec.defaultCodec();
        Outer payload = new Outer(List.of(new Inner("a"), new Inner("b")),
                java.util.Map.of("x", 1, "y", 2));
        byte[] bytes = codec.serialize(payload);
        assertThat(codec.deserialize(bytes, Outer.class)).isEqualTo(payload);
    }

    // 嵌套结构往返的样例载荷。
    record Inner(String value) {
    }

    record Outer(java.util.List<Inner> items, java.util.Map<String, Integer> counts) {
    }

    private static void writeLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (8 * (7 - i)));
        }
    }

    private static void writeInt(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) {
            b[off + i] = (byte) (v >>> (8 * (3 - i)));
        }
    }
}
