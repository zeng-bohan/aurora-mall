package com.zengbohan.aurora.ratelimit.circuit;

/**
 * 熔断器三态：CLOSED（正常放行）/ OPEN（快速失败）/ HALF_OPEN（放行试探）。
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
