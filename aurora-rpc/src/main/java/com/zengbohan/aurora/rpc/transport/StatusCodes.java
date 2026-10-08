package com.zengbohan.aurora.rpc.transport;

/**
 * 响应 status 码：0 = 成功，非 0 = 各类失败。
 * <p>
 * 调用方按语义分级处理：ERROR/OVERLOADED 可重试，UNAUTHORIZED 是配置错误（重试无意义）。
 */
public final class StatusCodes {

    public static final byte OK = 0;
    // 业务处理器抛出异常。
    public static final byte ERROR = 1;
    // 服务端业务线程池已满（过载保护），稍后重试。
    public static final byte OVERLOADED = 2;
    // 未通过内部密钥握手（配置错误）。
    public static final byte UNAUTHORIZED = 3;

    private StatusCodes() {
    }
}
