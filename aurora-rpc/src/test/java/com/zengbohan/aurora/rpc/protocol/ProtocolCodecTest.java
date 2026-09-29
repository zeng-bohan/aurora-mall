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
        RpcFrame decoded = ProtocolCodec.decodeHeader(encoded);
        assertThat(decoded.requestId()).isEqualTo(42L);
        assertThat(decoded.type()).isEqualTo(MessageType.REQUEST);
        assertThat(decoded.serializerCode()).isEqualTo((byte) 1);
        assertThat(decoded.bodyLength()).isEqualTo(3);
    }

    @Test
    void decodeExtractsTheRealBodyNotAPlaceholder() throws Exception {
        // decodeHeader 只解头（body 是零填充占位），decode 才切出真实 body——
        // 传输层必须用 decode，否则握手/业务数据全是 0 字节
        byte[] body = new byte[]{9, 8, 7};
        byte[] full = ProtocolCodec.encode(RpcFrame.request(5L, MessageType.REQUEST, (byte) 1, body));

        RpcFrame headerOnly = ProtocolCodec.decodeHeader(full);
        assertThat(headerOnly.body()).containsExactly(0, 0, 0); // 占位

        RpcFrame decoded = ProtocolCodec.decode(full);
        assertThat(decoded.body()).containsExactly(9, 8, 7);
        assertThat(decoded.requestId()).isEqualTo(5L);
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
        // 声明一个超长 body（超过上限）但只给 18 字节头
        byte[] header = new byte[ProtocolCodec.HEADER_LENGTH];
        ProtocolCodec.writeMagic(header);
        header[2] = MessageType.REQUEST.code();
        header[3] = (byte) 1;
        writeLong(header, 4, 1L);
        writeInt(header, 12, Integer.MAX_VALUE); // bodyLength 爆表

        assertThat(ProtocolCodec.decodeHeader(header)).isNull();
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
