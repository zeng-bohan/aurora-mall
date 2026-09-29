package com.zengbohan.aurora.rpc.protocol;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 协议编解码：定长头 + body。
 * <p>
 * 头布局（大端，共 18 字节）：
 * <pre>
 * offset  0  magic(2)      —— 魔数，误连别的协议立刻判掉
 * offset  2  version(1)    —— 协议版本
 * offset  3  type(1)       —— 请求/响应
 * offset  4  serializer(1) —— 序列化实现编号
 * offset  5  status(1)     —— 业务结果码（响应帧用）
 * offset  6  requestId(8)  —— 请求/响应配对
 * offset 14  bodyLength(4) —— body 字节数
 * offset 18  body
 * </pre>
 */
public final class ProtocolCodec {

    /** 定长头字节数。 */
    public static final int HEADER_LENGTH = 18;

    /** 魔数：0xA0 0xB0。 */
    public static final short MAGIC = (short) 0xA0B0;

    /** 协议版本。 */
    public static final byte VERSION = 1;

    /** body 长度上限，防止恶意/错位帧分配巨量内存（10MB）。 */
    public static final int MAX_BODY_LENGTH = 10 * 1024 * 1024;

    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 2;
    private static final int OFF_TYPE = 3;
    private static final int OFF_SERIALIZER = 4;
    private static final int OFF_STATUS = 5;
    private static final int OFF_REQUEST_ID = 6;
    private static final int OFF_BODY_LENGTH = 14;

    private final Map<Byte, Serializer> serializers = new HashMap<>();

    public ProtocolCodec() {
        register(new JsonSerializer());
    }

    /** 注册序列化实现，按 code 覆盖。 */
    public ProtocolCodec register(Serializer serializer) {
        serializers.put(serializer.code(), serializer);
        return this;
    }

    public static ProtocolCodec defaultCodec() {
        return new ProtocolCodec();
    }

    public Serializer serializer(byte code) {
        return serializers.get(code);
    }

    /** 序列化指定值，使用默认（JSON）实现。 */
    public byte[] serialize(Object value) throws Exception {
        return serializer(JsonSerializer.CODE).serialize(value);
    }

    /** 反序列化，使用默认（JSON）实现。 */
    public <T> T deserialize(byte[] bytes, Class<T> type) throws Exception {
        return serializer(JsonSerializer.CODE).deserialize(bytes, type);
    }

    /** 按指定实现序列化（proxy 层用于带 code 的帧）。 */
    public byte[] serialize(Object value, byte serializerCode) throws Exception {
        return serializer(serializerCode).serialize(value);
    }

    public <T> T deserialize(byte[] bytes, Class<T> type, byte serializerCode) throws Exception {
        return serializer(serializerCode).deserialize(bytes, type);
    }

    /**
     * 编码完整帧：头 + body。
     */
    public static byte[] encode(RpcFrame frame) {
        byte[] body = frame.bodyRef();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + body.length);
        buffer.putShort(MAGIC);
        buffer.put(VERSION);
        buffer.put(frame.type().code());
        buffer.put(frame.serializerCode());
        buffer.put(frame.status());
        buffer.putLong(frame.requestId());
        buffer.putInt(body.length);
        buffer.put(body);
        return buffer.array();
    }

    /**
     * 解码头：校验魔数/类型/body 长度上限；任一不合法返回 null（调用方关连接或回错误帧）。
     */
    public static RpcFrame decodeHeader(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_LENGTH) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getShort(OFF_MAGIC) != MAGIC) {
            return null;
        }
        if (buffer.get(OFF_VERSION) != VERSION) {
            return null;
        }
        MessageType type = MessageType.fromCode(buffer.get(OFF_TYPE));
        if (type == null) {
            return null;
        }
        int bodyLength = buffer.getInt(OFF_BODY_LENGTH);
        if (bodyLength < 0 || bodyLength > MAX_BODY_LENGTH) {
            return null;
        }
        return RpcFrame.headerOnly(buffer.getLong(OFF_REQUEST_ID), type,
                buffer.get(OFF_SERIALIZER), buffer.get(OFF_STATUS), bodyLength);
    }

    /**
     * 解码完整帧（头 + body）：把真实 body 从缓冲区切出来。
     * <p>
     * 与 {@link #decodeHeader} 的区别：那个只解头、body 是占位零填充（供拆包器先看长度），
     * 这个把 body 一并解出，供拿到完整帧字节后直接使用。
     *
     * @return 完整帧；魔数/版本/type/body 长度不合法时返回 null
     */
    public static RpcFrame decode(byte[] fullFrame) {
        RpcFrame header = decodeHeader(fullFrame);
        if (header == null) {
            return null;
        }
        int bodyLength = header.bodyLength();
        if (fullFrame.length < HEADER_LENGTH + bodyLength) {
            return null; // body 未收全
        }
        byte[] body = new byte[bodyLength];
        System.arraycopy(fullFrame, HEADER_LENGTH, body, 0, bodyLength);
        return RpcFrame.withBody(header, body);
    }

    /** 仅测试用：写魔数到指定偏移。 */
    static void writeMagic(byte[] target) {
        ByteBuffer.wrap(target).putShort(OFF_MAGIC, MAGIC);
    }
}

