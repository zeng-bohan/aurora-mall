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
                String token = store.tryAcquire(fullKey, idempotent.ttlSeconds());
                if (token == null) {
                    throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
                }
                try {
                    yield pjp.proceed();
                } catch (Throwable failure) {
                    // 本次调用没有成功，守卫不能挡住重试：按 token 做 CAS 释放，
                    // 这样慢的首次尝试不会删掉后来投递重新获取的守卫
                    store.release(fullKey, token);
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
                    // 与 REDIS 分支对称：调用失败不能留下守卫，
                    // 否则重投递会被跳过、消息被静默丢弃。（在调用方自有事务里，
                    // 回滚已经删掉了该行；这里覆盖的是裸注解场景。）
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
