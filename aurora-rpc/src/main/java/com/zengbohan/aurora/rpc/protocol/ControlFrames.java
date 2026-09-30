package com.zengbohan.aurora.rpc.protocol;

/**
 * 控制帧 body 约定（握手/心跳）的唯一事实源：client 与 server 两侧共用，
 * 字面量只在本文件出现一次。
 */
public final class ControlFrames {

    public static final String HANDSHAKE_PREFIX = "H:";
    public static final String PING = "C:ping";
    public static final String PONG = "C:pong";

    private ControlFrames() {
    }
}
