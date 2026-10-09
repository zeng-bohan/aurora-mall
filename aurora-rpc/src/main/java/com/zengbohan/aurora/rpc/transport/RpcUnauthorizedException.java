package com.zengbohan.aurora.rpc.transport;

/**
 * 未通过对端内部密钥握手：对端回了 UNAUTHORIZED 状态帧。
 * <p>
 * 这是**配置错误**（两端密钥不一致），重试不会好转，也不代表对端不健康——
 * 因此消费端不把它计入熔断失败率（见 {@code RpcProxyFactory} 的熔断配置）。
 * 与 {@link RpcUnavailableException}（对端不可达/过载，可重试且计入熔断）区分。
 */
public class RpcUnauthorizedException extends RpcException {

    public RpcUnauthorizedException(String message) {
        super(message);
    }
}
