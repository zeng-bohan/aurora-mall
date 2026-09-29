package com.zengbohan.aurora.common.idempotent;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

@Aspect
@Component
public class IdempotentAspect {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_NAMES = new DefaultParameterNameDiscoverer();

    private final ObjectProvider<RedisIdempotentStore> redisStores;
    private final ObjectProvider<DedupStore> dedupStores;

    public IdempotentAspect(ObjectProvider<RedisIdempotentStore> redisStores,
                            ObjectProvider<DedupStore> dedupStores) {
        this.redisStores = redisStores;
        this.dedupStores = dedupStores;
    }

    @Around("@annotation(idempotent)")
    public Object guard(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String key = resolveKey(pjp, idempotent);
        String bizType = idempotent.bizType().isEmpty()
                ? pjp.getSignature().getDeclaringType().getSimpleName()
                : idempotent.bizType();
        return switch (idempotent.strategy()) {
            case REDIS -> {
                RedisIdempotentStore store = require(redisStores,
                        "RedisIdempotentStore (needs redis on the classpath) for @Idempotent(REDIS)");
                String fullKey = bizType + ":" + key;
                if (!store.tryAcquire(fullKey, idempotent.ttlSeconds())) {
                    throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
                }
                try {
                    yield pjp.proceed();
                } catch (Throwable failure) {
                    // the call did not succeed, so the guard must not block the
                    // retry: release the key before surfacing the failure
                    store.release(fullKey);
                    throw failure;
                }
            }
            case DB_DEDUP -> {
                DedupStore store = require(dedupStores,
                        "DedupStore (needs spring-jdbc + datasource) for @Idempotent(DB_DEDUP)");
                if (!store.tryInsert(bizType, key)) {
                    yield null;
                }
                try {
                    yield pjp.proceed();
                } catch (Throwable failure) {
                    // symmetric with the REDIS branch: a failed call must not
                    // leave the guard behind, or redelivery gets skipped and
                    // the message is silently lost. (Inside a caller-owned
                    // transaction the rollback already removed the row; this
                    // covers the bare-annotation case.)
                    store.remove(bizType, key);
                    throw failure;
                }
            }
        };
    }

    private <T> T require(ObjectProvider<T> provider, String what) {
        T store = provider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("@Idempotent needs " + what + ", but no bean is available");
        }
        return store;
    }

    private String resolveKey(ProceedingJoinPoint pjp, Idempotent idempotent) {
        if (!idempotent.key().isEmpty()) {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    pjp.getTarget(), method, pjp.getArgs(), PARAM_NAMES);
            String value = PARSER.parseExpression(idempotent.key()).getValue(context, String.class);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return pjp.getSignature().toShortString() + ":" + Arrays.deepHashCode(pjp.getArgs());
    }
}
