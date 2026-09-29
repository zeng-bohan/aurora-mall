package com.zengbohan.aurora.rpc.protocol;

import java.util.Arrays;

/**
 * 一帧 RPC 消息：定长 18 字节头 + body。
 * <p>
 * 头布局（大端）：
 * <pre>
 * magic(2) | version(1) | type(1) | serializer(1) | status(1) | requestId(8) | bodyLength(4)
 * </pre>
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

    private static byte[] requireBody(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        return body;
    }

    /**
     * 仅解码头时使用：body 未知（等待按 bodyLength 读取），但 status 已在头里。
     */
    static RpcFrame headerOnly(long requestId, MessageType type, byte serializerCode,
            byte status, int bodyLength) {
        byte[] placeholder = new byte[Math.max(bodyLength, 0)];
        return new RpcFrame(requestId, type, serializerCode, status, placeholder);
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
