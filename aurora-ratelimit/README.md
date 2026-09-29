# aurora-ratelimit

手写限流器与熔断器（ADR-0008 三部曲之二）：**滑动窗口 + 令牌桶 + 简易熔断器**。纯 Java 库，无 Spring 依赖。

## 设计

### `CircuitBreaker` — CLOSED / OPEN / HALF_OPEN 状态机

- 以**装饰器**形式包裹任意 `Callable`：熔断判定 → 执行 → 统计，业务异常原样穿透（不被熔断吞掉）
- **CLOSED**：环形桶滑动窗口统计失败率与慢调用率，任一超阈值转 OPEN
- **OPEN**：不触达被包裹调用，直接抛 `CircuitOpenException` 快速失败；持续时长到达转 HALF_OPEN
- **HALF_OPEN**：放行有限次试探（并发下严格有界），全部成功回 CLOSED，任一失败回 OPEN
- **触发源二选一/并用**：失败率阈值、慢调用率阈值（单次耗时 > `slowCallDurationMillis` 算慢调用），两者独立判定，任一达到即熔断
- **最小请求数** `minRequestThreshold`：窗口内请求数低于此值不判定，避免小样本抖动误熔断
- 状态变更经 `Listener` 事件钩子外抛（为 M4 指标留缝，不引依赖）

### `SlidingWindowRateLimiter` — 环形桶分段计数

- 窗口切成 `bucketCount`（默认 10）个等长时间槽，当前时刻落在哪个槽就计数在哪个槽
- 判定时只累计**最近 bucketCount 个槽**的计数，窗口连续滑动，没有固定窗口在临界点放行双倍配额的缺陷
- **加锁换取严格不超发**：整个判定在一个 `synchronized` 临界区内，临界区只做 O(bucketCount) 的求和；环形桶的价值在于把统计与清理都压到 O(1) 摊销
- 边界防御：桶过期（槽号不匹配）自动清零、`permits <= 0` 拒绝、窗口短于桶数直接拒绝构造而不是静默劣化

### `TokenBucketRateLimiter` — 惰性补充

- 恒定速率补充令牌，桶容量决定可容忍的突发量；初始满桶，允许冷启动瞬间的突发
- **惰性补充**（懒计算）：不跑定时任务，取令牌时按「距上次补充的逝去时间 × 速率」一次性补足，上限为桶容量
- 令牌以 `double` 累计：短于 1 个令牌的时间不会白白丢失（单测覆盖 250ms×2 = 0.5+0.5 = 1 个令牌）
- 单次 `permits > 容量` 永远不可能满足，直接拒绝且不消耗令牌

## 用法

```java
RateLimiter window = new SlidingWindowRateLimiter(100, Duration.ofSeconds(1));
RateLimiter bucket = new TokenBucketRateLimiter(50, 100); // 50/s，突发 100
if (!window.tryAcquire()) {
    throw new TooManyRequestsException(); // 立即返回，不阻塞等待
}

CircuitBreaker breaker = new CircuitBreaker(CircuitBreakerConfig.builder()
        .failureRateThreshold(50)          // 失败率 50% 熔断
        .slowCallDuration(Duration.ofMillis(500)) // >500ms 算慢调用
        .minRequestThreshold(10)           // 少于 10 次不判定
        .openDuration(Duration.ofSeconds(10))
        .build());
T result = breaker.execute(() -> callRemote()); // 熔断打开时快速失败
```

## 测试覆盖（23 例，`mvn -pl aurora-ratelimit test`）

| 场景 | 说明 |
| --- | --- |
| 窗口内配额耗尽 | 放行到 limit 后持续拒绝，时间不推进不自行恢复 |
| 窗口完全滑动 | 边界前 1ms 拒绝，边界时刻整体恢复 |
| 部分滑动 | 只有过期的桶释放配额，部分过期的继续占用 |
| 批量 permits | 按 permits 加权判定（7 放行 / 4 拒绝 / 3 放行） |
| 配置防御 | limit/window/bucketCount 非正、窗口短于桶数、permits 非正 |
| 并发不超发 | 20 线程 × 10 次抢 50 配额，恰好 50 成功 |
| 令牌桶突发 | 放行到容量后拒绝 |
| 令牌桶速率 | 2s × 2/s = 恰好 4 个令牌 |
| 补充上限 | 远超容量的补充只补到桶容量 |
| 小数精度 | 250ms 两次各 0.5 累计成 1 个令牌 |
| 单请求超容量 | 永远拒绝且不消耗令牌 |
| 并发不超发 | 20 线程 × 10 次抢 50 令牌，恰好 50 成功 |
| 与 Guava 同场基准 | 见下 |
| 固定窗口翻倍 | 一秒内最多放行配额数，不因窗口对齐翻倍 |
| 熔断：低于阈值 | 4 次请求 1 失败（25% < 50%）保持 CLOSED |
| 熔断：达阈值打开 | 4 次失败（75% ≥ 50%）转 OPEN |
| 熔断：快速失败 | OPEN 期间抛 CircuitOpenException，被包裹调用零执行 |
| 熔断：半开恢复 | 时长到达转 HALF_OPEN，试探成功回 CLOSED |
| 熔断：半开失败 | 试探失败回 OPEN |
| 熔断：慢调用触发 | 3 慢 + 1 快 = 75% 慢调用率独立触发 OPEN |
| 熔断：并发试探有界 | 10 线程试探仅 2 个进入，其余快速失败（阻塞验证真实上界） |
| 熔断：事件钩子 | 状态迁移事件按预期发布 OPEN/CLOSED 各一次 |

## 实测基准

测试内基准 harness（非 JMH——本机内存吃紧，JVM 预热的严谨性对本次对比收益有限）：

```
[bench] throughput  sliding-window=5371492 ops/s  guava=5254087 ops/s  (ratio 0.98)
```

8 线程 × 6250 次抢 5 万配额、同等配额下本实现与 Guava 令牌桶同量级。

## 设计要点回顾

1. **滑动窗口 vs 固定窗口**：固定窗口在窗口对齐的临界点可放行双倍配额（用满上一窗口尾部 + 下一窗口头部），这是它的固有缺陷；环形桶分段把窗口连续滑动，消除此缺陷，代价是 O(bucketCount) 求和。
2. **环形桶 vs 定时清理**：惰性补充（令牌桶）与惰性清零（滑动窗口）都省掉了后台线程——限流器是被高频调用的热路径，后台线程的调度抖动会直接反映到限流精度上。
3. **加锁 vs 无锁**：滑动窗口加锁换取严格不超发，与 Guava 同级取舍；如果追求更高并发可以改 CAS 累加，但会牺牲严格性。
4. **惰性补充的时间基准**：用纳秒时钟而非毫秒，避免毫秒精度下高频补充的舍入误差累积（单测覆盖小数精度）。
5. **熔断为什么要有 HALF_OPEN 而不是直接回 CLOSED**：直接回 CLOSED 会在依赖刚好恢复的瞬间把全部流量一次性放过去再打崩一次；放行有限次试探并要求全部成功，是用少量流量换恢复确定性。
6. **并发试探上界为什么不能是 1**：单次试探成功不代表依赖真的恢复（可能只是那一个请求命中了缓存）；配置为 N 次全部成功才回 CLOSED，N 太小易误恢复、太大等于没限流。
7. **失败率与慢调用率两个触发源**：只看失败率会漏掉"没报错但慢到拖垮线程池"的雪崩路径——这类调用在结果上仍是成功的，必须单独用慢调用率兜住。
