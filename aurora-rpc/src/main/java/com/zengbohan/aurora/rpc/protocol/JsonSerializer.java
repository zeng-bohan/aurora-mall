package com.zengbohan.aurora.rpc.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON 序列化：默认实现。选它的理由是可读（抓包即可排障）与跨语言友好，
 * 代价是体积与 CPU——取舍详见 README。
 */
public class JsonSerializer implements Serializer {

    // 协议默认序列化编号。
    public static final byte CODE = 1;

    private final ObjectMapper mapper;

    public JsonSerializer() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // 金额精度：未开此项 JSON 小数走 Double，BigDecimal 19.90 会被劣化成 19.9
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public byte code() {
        return CODE;
    }

    @Override
    public byte[] serialize(Object value) throws Exception {
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) throws Exception {
        return mapper.readValue(bytes, type);
    }
}
