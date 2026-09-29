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
    /**
     * 帧声明的 body 长度。-1 = 以 body 数组实际长度为准（正常构造路径）。
     * 仅解头的帧（见 {@link #headerOnly}）没有 body，但声明的长度要如实报告。
     */
    private final int declaredBodyLength;

    private RpcFrame(long requestId, MessageType type, byte serializerCode, byte status, byte[] body) {
        this.requestId = requestId;
        this.type = type;
        this.serializerCode = serializerCode;
        this.status = status;
        this.body = body;
        this.declaredBodyLength = -1;
    }

    private RpcFrame(long requestId, MessageType type, byte serializerCode, byte status,
            byte[] body, int declaredBodyLength) {
        this.requestId = requestId;
        this.type = type;
        this.serializerCode = serializerCode;
        this.status = status;
        this.body = body;
        this.declaredBodyLength = declaredBodyLength;
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
     * 仅解码头时使用：body 尚未从流中切出，但头里声明的长度要如实报告。
     * <p>
     * 不按声明长度分配占位数组——坏帧头可能声明 10MB，逐帧分配是内存放大面。
     * body 为空数组，{@link #bodyLength()} 返回声明值。
     */
    static RpcFrame headerOnly(long requestId, MessageType type, byte serializerCode,
            byte status, int bodyLength) {
        return new RpcFrame(requestId, type, serializerCode, status, new byte[0], Math.max(bodyLength, 0));
    }

    /** 用解出的真实 body 重建帧（decode 的后半段）。 */
    static RpcFrame withBody(RpcFrame header, byte[] body) {
        return new RpcFrame(header.requestId, header.type, header.serializerCode, header.status, body);
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
        // 仅解头的帧 body 为空，但帧头声明的长度才是协议事实
        return declaredBodyLength >= 0 ? declaredBodyLength : body.length;
    }

    /** 内部可见的直接引用，避免编解码时不必要的拷贝。 */
    byte[] bodyRef() {
        return body;
    }
}
