package com.zengbohan.aurora.rpc.transport;

/**
 * 响应 status 码：0 = 成功，非 0 = 业务/处理失败。
 */
public final class StatusCodes {

    public static final byte OK = 0;
    public static final byte ERROR = 1;

    private StatusCodes() {
    }
}
