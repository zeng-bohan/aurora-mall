package com.zengbohan.aurora.rpc.protocol;

/**
 * 序列化 SPI：按 code 编解码，编解码两端通过 code 协商具体实现。
 * <p>
 * 可插拔性由测试内第二个实现（stub）证明——新增算法只需实现本接口并注册到 codec。
 */
public interface Serializer {

    /** 序列化实现编号，写入协议头供对端识别。 */
    byte code();

    byte[] serialize(Object value) throws Exception;

    <T> T deserialize(byte[] bytes, Class<T> type) throws Exception;
}
