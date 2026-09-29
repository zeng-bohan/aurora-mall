package com.zengbohan.aurora.rpc.transport;

/**
 * RPC 异常基类。
 */
public class RpcException extends RuntimeException {

    public RpcException(String message) {
        super(message);
    }

    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
