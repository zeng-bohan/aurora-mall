package com.zengbohan.aurora.common.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ADR-0005 unified idempotency: one annotation, scenario-mapped strategies.
 *
 * <ul>
 *   <li>{@link Strategy#REDIS} — request dedup (order placement): duplicates
 *       fail fast with {@code DUPLICATE_REQUEST}.</li>
 *   <li>{@link Strategy#DB_DEDUP} — dedup-table guard (MQ consumers, payment
 *       callbacks): duplicates are skipped silently. Payment callbacks pair
 *       this with the payment-order status machine + unique index.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    Strategy strategy();

    /** Business type recorded in the dedup table; defaults to the declaring class name. */
    String bizType() default "";

    /**
     * SpEL over the method parameters, e.g. {@code "#request.requestId"}.
     * Empty means class#method plus a hash of the arguments.
     */
    String key() default "";

    /** TTL in seconds for the {@link Strategy#REDIS} guard. */
    long ttlSeconds() default 600;
}
