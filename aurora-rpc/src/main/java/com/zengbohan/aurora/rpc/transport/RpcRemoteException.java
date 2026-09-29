package com.zengbohan.aurora.rpc.transport;

/**
 * 远端业务异常：服务端处理器抛出的异常，以错误 status 回传（连接不断）。
 * 与 {@link RpcUnavailableException} 区分：前者是"对端业务失败"，后者是"对端不可达"。
 */
public class RpcRemoteException extends RpcException {

    public RpcRemoteException(String message) {
        super(message);
    }
}
