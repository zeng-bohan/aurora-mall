package com.zengbohan.aurora.rpc.transport;

/**
 * 远端业务异常：服务端处理器显式标记的业务失败，以 status=OK + 异常类型载荷回传
 * （连接不断），消费端还原为本异常，不计入熔断统计。
 * 与 {@link RpcUnavailableException} 区分：前者是"对端业务失败"，后者是
 * "对端不可达/系统失败"（计入熔断）。
 */
public class RpcRemoteException extends RpcException {

    public RpcRemoteException(String message) {
        super(message);
    }
}
