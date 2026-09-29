package com.zengbohan.aurora.common.idempotent;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotentAspectTest {

    private FakeRedis redis;
    private FakeDedup dedup;
    private IdempotentAspect aspect;
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        dedup = new FakeDedup();
        aspect = new IdempotentAspect(
                new TestProvider<>(redis),
                new TestProvider<>(dedup));
        calls = new AtomicInteger();
    }

    /** Minimal ObjectProvider stand-in returning a single available bean. */
    private static class TestProvider<T> implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T bean;

        TestProvider(T bean) {
            this.bean = bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getObject(Object... args) {
            return bean;
        }

        @Override
        public T getObject() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }
    }

    private static class FakeRedis implements RedisIdempotentStore {
        private final Set<String> keys = new HashSet<>();
        private String lastKey;
        private long lastTtl;

        @Override
        public boolean tryAcquire(String key, long ttlSeconds) {
            lastKey = key;
            lastTtl = ttlSeconds;
            return keys.add(key);
        }
    }

    private static class FakeDedup implements DedupStore {
        private final Set<String> rows = new HashSet<>();

        @Override
        public boolean tryInsert(String bizType, String bizKey) {
            return rows.add(bizType + ":" + bizKey);
        }
    }

    /** Target methods the aspect is reflected against. */
    @SuppressWarnings("unused")
    static class Targets {
        @Idempotent(strategy = Strategy.REDIS, key = "#orderId", ttlSeconds = 77)
        public void placeOrder(String orderId) {
        }

        @Idempotent(strategy = Strategy.DB_DEDUP, bizType = "stock-sync", key = "#messageId")
        public void onMessage(String messageId) {
        }
    }

    private ProceedingJoinPoint joinPoint(String methodName, Object... args) throws NoSuchMethodException {
        Method method = Targets.class.getMethod(methodName, String.class);
        MethodSignature signature = mock(MethodSignature.class);
        Mockito.doReturn(method).when(signature).getMethod();
        Mockito.doReturn(Targets.class).when(signature).getDeclaringType();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        try {
            when(pjp.proceed()).thenAnswer(inv -> {
                calls.incrementAndGet();
                return "result";
            });
        } catch (Throwable impossible) {
            // stubbing a mock: proceed() never actually throws
        }
        return pjp;
    }

    @Test
    void redisStrategyFirstCallProceedsAndRecordsKeyAndTtl() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("placeOrder", "order-1");

        Object result = aspect.guard(pjp, annotated(Strategy.REDIS, "#orderId", 77));

        assertThat(result).isEqualTo("result");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(redis.lastKey).isEqualTo("Targets:order-1");
        assertThat(redis.lastTtl).isEqualTo(77);
    }

    @Test
    void redisStrategyDuplicateThrowsDuplicateRequest() throws Throwable {
        ProceedingJoinPoint first = joinPoint("placeOrder", "order-1");
        aspect.guard(first, annotated(Strategy.REDIS, "#orderId", 77));

        ProceedingJoinPoint second = joinPoint("placeOrder", "order-1");
        assertThatThrownBy(() -> aspect.guard(second, annotated(Strategy.REDIS, "#orderId", 77)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.DUPLICATE_REQUEST.getCode());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void redisStrategyDifferentKeysDoNotCollide() throws Throwable {
        aspect.guard(joinPoint("placeOrder", "order-1"), annotated(Strategy.REDIS, "#orderId", 77));
        aspect.guard(joinPoint("placeOrder", "order-2"), annotated(Strategy.REDIS, "#orderId", 77));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void dedupStrategyFirstCallProceedsDuplicateIsSkipped() throws Throwable {
        ProceedingJoinPoint first = joinPoint("onMessage", "msg-1");
        assertThat(aspect.guard(first, annotated(Strategy.DB_DEDUP, "stock-sync", "#messageId", 0))).isEqualTo("result");

        ProceedingJoinPoint second = joinPoint("onMessage", "msg-1");
        assertThat(aspect.guard(second, annotated(Strategy.DB_DEDUP, "stock-sync", "#messageId", 0))).isNull();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(dedup.rows).containsExactly("stock-sync:msg-1");
    }

    @Test
    void missingStoreForStrategyFailsWithReadableMessage() throws Throwable {
        IdempotentAspect bare = new IdempotentAspect(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(RedisIdempotentStore.class),
                new TestProvider<>(dedup));
        ProceedingJoinPoint pjp = joinPoint("placeOrder", "order-1");

        assertThatThrownBy(() -> bare.guard(pjp, annotated(Strategy.REDIS, "#orderId", 77)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RedisIdempotentStore");
    }

    // mock(Idempotent.class) stubbing helper with explicit values
    private Idempotent annotated(Strategy strategy, String key, long ttl) {
        return annotated(strategy, "", key, ttl);
    }

    private Idempotent annotated(Strategy strategy, String bizType, String key, long ttl) {
        Idempotent annotation = mock(Idempotent.class);
        when(annotation.strategy()).thenReturn(strategy);
        when(annotation.bizType()).thenReturn(bizType);
        when(annotation.key()).thenReturn(key);
        when(annotation.ttlSeconds()).thenReturn(ttl);
        return annotation;
    }
}
