package com.zengbohan.aurora.rpc.proxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 调用结果载荷（线上格式）：返回值 或 远端业务异常的类型名 + 消息。
 * <p>
 * 业务异常走 status=OK 的正常帧（传输本身成功），由 exceptionType 区分——
 * 客户端把它转成 {@link com.zengbohan.aurora.rpc.transport.RpcRemoteException}
 * （携带远端类型名），与不可用/超时/熔断三类异常严格分层。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvocationResult(Object result, String exceptionType, String exceptionMessage) {

    public static InvocationResult of(Object result) {
        return new InvocationResult(result, null, null);
    }

    public static InvocationResult failure(String type, String message) {
        return new InvocationResult(null, type, message);
    }

    public boolean isBusinessFailure() {
        return exceptionType != null;
    }
}
