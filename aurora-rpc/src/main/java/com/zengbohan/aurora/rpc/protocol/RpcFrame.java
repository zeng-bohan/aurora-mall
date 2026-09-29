package com.zengbohan.aurora.rpc.protocol;

import java.util.Arrays;

/**
 * 一帧 RPC 消息：头元数据 + body 载荷。完整构造只能经
 * {@link ProtocolCodec#decode}（或各工厂方法），保证「帧要么完整要么不存在」。
 */
public final class RpcFrame {

    private final long requestId;
    private final MessageType type;
    private final byte serializerCode;
    private final byte status;
    private final byte[] body;

    private RpcFrame(long requestId, MessageType type, byte serializerCode, byte status, byte[] body) {
        this.requestId = requestId;
        this.type = type;
        this.serializerCode = serializerCode;
        this.status = status;
        this.body = body;
    }

    public static RpcFrame request(long requestId, MessageType type, byte serializerCode, byte[] body) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return new RpcFrame(requestId, type, serializerCode, (byte) 0, requireBody(body));
    }

    /** 响应帧，status 携带业务结果码。 */
    public static RpcFrame response(long requestId, byte serializerCode, byte status, byte[] body) {
        return new RpcFrame(requestId, MessageType.RESPONSE, serializerCode, status, requireBody(body));
    }

    /** decode 的后半段：头元数据 + 切出的真实 body 组装成完整帧。 */
    static RpcFrame from(FrameHeader header, byte[] body) {
        return new RpcFrame(header.requestId(), header.type(), header.serializerCode(),
                header.status(), body);
    }

    private static byte[] requireBody(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        return body;
    }

    public long requestId() {
        return requestId;
    }

    public MessageType type() {
        return type;
    }

    public byte serializerCode() {
        return serializerCode;
    }

    public byte status() {
        return status;
    }

    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    public int bodyLength() {
        return body.length;
    }

    /** 内部可见的直接引用，避免编解码时不必要的拷贝。 */
    byte[] bodyRef() {
        return body;
    }
}
