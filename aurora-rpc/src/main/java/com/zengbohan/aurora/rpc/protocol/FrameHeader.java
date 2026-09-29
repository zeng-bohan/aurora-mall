package com.zengbohan.aurora.rpc.protocol;

/**
 * 帧头元数据：18 字节定长头解出后的纯数据，不含 body。
 * <p>
 * 与 {@link RpcFrame} 的分工：只需要看「这条帧是谁的、什么类型、多长」时用本类型
 * （如拆包器决策、日志）；要处理业务数据时用 {@link ProtocolCodec#decode} 拿完整帧。
 */
public record FrameHeader(long requestId, MessageType type, byte serializerCode, byte status,
        int bodyLength) {
}
