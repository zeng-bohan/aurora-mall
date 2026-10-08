package com.zengbohan.aurora.ratelimit.circuit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断器状态机：注入可控时钟，把 CLOSED / OPEN / HALF_OPEN 的迁移测成确定性的。
 */
class CircuitBreakerTest {

    // 测试用假时钟（毫秒）。
    private long now;

    private CircuitBreakerConfig.Builder config() {
        return new CircuitBreakerConfig.Builder()
                .failureRateThreshold(50)
                .slowCallRateThreshold(50)
                .slowCallDurationMillis(100)
                .minRequestThreshold(4)
                .halfOpenPermittedCalls(2)
                .openDurationMillis(1000)
                .clock(() -> now);
    }

    private CircuitBreaker breaker(CircuitBreakerConfig cfg) {
        return new CircuitBreaker(cfg);
    }

    private void advance(long millis) {
        now += millis;
    }

    private static Callable<Void> ok() {
        return () -> null;
    }

    private static Callable<Void> boom() {
        return () -> {
            throw new IllegalStateException("downstream failure");
        };
    }

    private static Callable<Void> slow(long millis) {
        return () -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
    }

    @Test
    void staysClosedBelowFailureThreshold() throws Exception {
        CircuitBreaker breaker = breaker(config().build());
        // 4 次请求 1 失败 = 25% < 50%
        for (int i = 0; i < 3; i++) {
            breaker.execute(ok());
        }
        assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void opensWhenFailureRateHitsThreshold() throws Exception {
        CircuitBreaker breaker = breaker(config().build());
        // 4 次请求 3 失败 = 75% >= 50% → OPEN
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void openFailsFastWithoutCallingDownstream() {
        CircuitBreaker breaker = breaker(config().build());
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        // OPEN 期间快速失败，不触达被包裹的调用
        AtomicInteger calls = new AtomicInteger();
        Callable<Void> counting = () -> {
            calls.incrementAndGet();
            return null;
        };
        assertThatThrownBy(() -> breaker.execute(counting))
                .isInstanceOf(CircuitOpenException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void halfOpensAfterOpenDurationThenSuccessRecovers() throws Exception {
        CircuitBreaker breaker = breaker(config().build());
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        advance(1001); // 到达 OPEN 持续时长
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        // 半开试探成功 → 恢复 CLOSED（试探数 2，这里两次都成功）
        breaker.execute(ok());
        breaker.execute(ok());
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void halfOpenFailureFallsBackToOpen() throws Exception {
        CircuitBreaker breaker = breaker(config().build());
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        }
        advance(1001);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void slowCallsTriggerBreakerIndependently() throws Exception {
        CircuitBreaker breaker = breaker(config().build());
        // 4 次全部成功但其中 3 次慢（>100ms）= 75% 慢调用 >= 50% → OPEN
        for (int i = 0; i < 3; i++) {
            breaker.execute(slow(150));
        }
        breaker.execute(ok());
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void concurrentHalfOpenProbesAreBounded() throws Exception {
        CircuitBreaker breaker = breaker(config().build());
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        }
        advance(1001); // HALF_OPEN，试探数 2

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch admittedSignal = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger fastFailed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    // 试探调用阻塞，直到 release 才返回——这样试探槽位才会真正被占住，
                    // 若实现没有对并发试探设上界，10 个线程会全部进入
                    breaker.execute(() -> {
                        admitted.incrementAndGet();
                        admittedSignal.countDown();
                        release.await();
                        return null;
                    });
                } catch (CircuitOpenException e) {
                    fastFailed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // 未抛异常即通过
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        // 等 2 个试探进入后（第三个若被放行说明无上界），此时 admitted 应恰为 2
        assertThat(admittedSignal.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200); // 给其余线程进入 fast-fail 的时间
        assertThat(admitted.get()).isEqualTo(2);
        assertThat(fastFailed.get()).isEqualTo(threads - 2);
        release.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
    }

    @Test
    void stateChangeEventsArePublished() throws Exception {
        AtomicInteger openEvents = new AtomicInteger();
        AtomicInteger closeEvents = new AtomicInteger();
        CircuitBreaker breaker = breaker(config().eventListener(new CircuitBreaker.Listener() {
            @Override
            public void onStateChange(CircuitBreakerState from, CircuitBreakerState to) {
                if (to == CircuitBreakerState.OPEN) {
                    openEvents.incrementAndGet();
                } else if (to == CircuitBreakerState.CLOSED) {
                    closeEvents.incrementAndGet();
                }
            }
        }).build());

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(openEvents.get()).isEqualTo(1);

        advance(1001);
        breaker.execute(ok());
        breaker.execute(ok());
        assertThat(closeEvents.get()).isEqualTo(1);
    }

    @Test
    void supplierVariantWrapsWithoutCheckedExceptions() {
        CircuitBreaker breaker = breaker(config().build());

        assertThat(breaker.executeSupplier(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> breaker.executeSupplier(() -> {
            throw new IllegalStateException("supplier blew up");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failuresAgeOutOfStatWindowIndependentlyOfOpenDuration() throws Exception {
        // 统计窗口 200ms 与 OPEN 时长 10s 相互独立：早期失败出窗后不应再推高失败率
        CircuitBreaker breaker = breaker(config()
                .statWindowMillis(200)
                .openDurationMillis(10_000)
                .minRequestThreshold(3)
                .build());

        assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.execute(boom())).isInstanceOf(IllegalStateException.class);

        advance(500); // 统计窗（200ms）已完全滑过，两次失败出窗
        breaker.execute(ok());
        // 若统计窗仍包住早期失败：3 次里 2 败 = 67% ≥ 50% 会误熔断；
        // 出窗后只剩 1 次成功，样本数低于阈值不判定，保持 CLOSED
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }
}
