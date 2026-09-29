package com.zengbohan.aurora.rpc.transport;

import io.netty.util.AttributeKey;

/**
 * Netty 通道属性键。
 */
public final class AttributeKeys {

    /** 该连接是否已通过内部密钥握手。 */
    public static final AttributeKey<Boolean> AUTHENTICATED = AttributeKey.valueOf("aurora.rpc.authenticated");

    private AttributeKeys() {
    }
}
