package com.zengbohan.aurora.rpc.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 测试用的第二个序列化实现：证明 SPI 可插拔——新增算法只需实现 Serializer 并注册，
 * 无需改动 codec 或协议布局。
 */
class UppercaseStubSerializer implements Serializer {

    static final byte CODE = 2;

    @Override
    public byte code() {
        return CODE;
    }

    @Override
    public byte[] serialize(Object value) {
        String s = String.valueOf(value);
        return s.toUpperCase().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        T cast = (T) s;
        return cast;
    }
}
