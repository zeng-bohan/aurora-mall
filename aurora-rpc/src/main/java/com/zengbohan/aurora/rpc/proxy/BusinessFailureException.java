package com.zengbohan.aurora.rpc.proxy;

/**
 * 业务失败标记：服务端处理器抛出它，表示"域规则拒绝了这次调用，服务本身健康"。
 * <p>
 * 传输层把它编码为 status=OK + 异常类型/消息载荷（区别于系统异常的 ERROR
 * status）；消费端还原为 RpcRemoteException——熔断器只统计系统失败，
 * 业务拒绝（如库存不足）不把健康链路打成熔断。
 */
public class BusinessFailureException extends RuntimeException {

    private final String failureType;

    public BusinessFailureException(String failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    /** 远端业务异常的原始类型名（客户端透传给 RpcRemoteException）。 */
    public String getFailureType() {
        return failureType;
    }
}
