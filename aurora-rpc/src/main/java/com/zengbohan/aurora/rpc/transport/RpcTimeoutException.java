package com.zengbohan.aurora.rpc.transport;

/**
 * 调用超时：等待响应超过阈值。调用方可重试。
 */
public class RpcTimeoutException extends RpcException {

    public RpcTimeoutException(String message) {
        super(message);
    }
}
