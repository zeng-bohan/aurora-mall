package com.zengbohan.aurora.rpc.transport;

/**
 * 传输层固定用 JSON 序列化（默认实现），此处为便捷别名。
 */
final class JsonSerializerCode {

    static final byte JSON = com.zengbohan.aurora.rpc.protocol.JsonSerializer.CODE;

    private JsonSerializerCode() {
    }
}
