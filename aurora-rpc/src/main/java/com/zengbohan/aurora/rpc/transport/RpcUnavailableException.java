package com.zengbohan.aurora.rpc.transport;

/**
 * 服务不可用：连接断开/发送失败/未连接。调用方可重试。
 */
public class RpcUnavailableException extends RpcException {

    public RpcUnavailableException(String message) {
        super(message);
    }

    public RpcUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
