package com.zengbohan.aurora.ratelimit.circuit;

/**
 * 熔断打开时调用方收到的异常：被包裹的调用根本没被执行。
 */
public class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String message) {
        super(message);
    }
}
