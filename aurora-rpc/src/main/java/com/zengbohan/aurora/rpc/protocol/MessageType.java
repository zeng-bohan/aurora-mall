package com.zengbohan.aurora.rpc.protocol;

/**
 * 消息类型：请求 / 响应。
 */
public enum MessageType {

    REQUEST((byte) 1),
    RESPONSE((byte) 2),
    /** 控制帧：握手（内部密钥）、心跳 ping/pong。 */
    CONTROL((byte) 3);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    /** 按 code 还原类型；未知 code 返回 null（由调用方判空拒绝）。 */
    public static MessageType fromCode(byte code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
