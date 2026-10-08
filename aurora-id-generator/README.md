# aurora-id-generator

手写分布式 ID 生成器（三部曲之一）：**号段模式 + 雪花改进**，纯 Java 库（无 Spring 依赖），M2 起为订单号供号。

## 设计

### 号段模式 `SegmentIdGenerator`

- 号段表 `aurora_id.leaf_alloc`（biz_tag / max_id / step），经 `SegmentLoader` 接口取段；`JdbcSegmentLoader` 用
  `UPDATE ... SET max_id = LAST_INSERT_ID(max_id + step)` 把分配与回读合成一条原子语句（MySQL 惯用技巧）
- **双 buffer**：当前段消耗到 60% 时异步预取下一段，段耗尽时切换 O(1)、零空窗
- **预取尽力而为**：预取失败只置标志、不打断当前段发号；真正切换时重试并暴露错误（一个坏预取不能杀死存量号）
- 输出趋势递增 long（订单号用），带 `biz_tag` 可多业务隔离
- 边界：`offset` 严格保持在 `[0, step)`，段边界 off-by-one 有并发单测盯防（曾抓出一个真 bug）

### 雪花改进 `SnowflakeIdGenerator`

- 标准 41/10/12 布局，自定义 epoch（2025-01-01，可用约 69 年）
- **时钟回拨守卫**：≤5ms 等待追上；更大回拨直接拒绝（`IllegalStateException`），绝不发可能重复的号
- **等待后重评估**：等待新毫秒期间其他线程可能已占用该毫秒的序号——主循环 `continue` 重新推导，而不是沿用环绕后的 seq（并发单测曾抓出这个撞号 bug）
- 轻微自旋等待用 `wait()`，中断即中止

## 用法

```java
// 号段（订单推荐：趋势递增、无时钟依赖）
IdGenerator orderIds = new SegmentIdGenerator("order",
        new JdbcSegmentLoader(dataSource, "aurora_id.leaf_alloc"));
long orderId = orderIds.nextId();

// 雪花（需要纯内存、无 DB 依赖时）
IdGenerator snowflake = new SnowflakeIdGenerator(workerId); // 0..1023
```

Spring 装配示例见 `aurora-order/config/IdGeneratorConfig`（DataSource → JdbcSegmentLoader，bean 名 `orderIdGenerator`）。

## 测试覆盖（8 例，`mvn -pl aurora-id-generator test`）

| 场景 | 说明 |
| --- | --- |
| 小幅回拨 | 等待追上后继续发号 |
| 大幅回拨 | 拒绝并给出可读错误 |
| workerId 边界 | [0,1023] 之外构造即失败 |
| 并发唯一性（雪花） | 100 线程 × 100 号，无重复 |
| 段内发号 / 跨段切换 | 覆盖段边界、断点无跳号 |
| 预取计数 | 三段耗尽恰三次 loader 调用（预取恰一次/段） |
| 并发唯一性（号段） | 20 线程 × 400 号，跨段切换无重复 |

## 设计要点回顾（真实实现支撑）

1. 号段 `LAST_INSERT_ID` 原子分配的 SQL 技巧与双 buffer 切换时序
2. 雪花回拨两种策略（等待/拒绝）的取舍；等待后为什么必须重评估 seq（真实并发 bug）
3. 预取失败的降级路径：为什么允许"少一次预取"但不能"杀当前段"
