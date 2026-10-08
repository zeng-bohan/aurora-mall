# M2 分布式事务对照实验：RocketMQ 最终一致性 vs Seata AT

日期：2026-09-29 · 状态：已完成（工作负载：本地单机 docker + 宿主服务）

本实验为对照实验部分：主链路（mq 模式）用 RocketMQ 事务消息达成最终一致性；**at 模式**用 Seata AT 的全局事务包裹同一下单语义（订单落库 + 库存预占），配置开关 `aurora.tx.mode=mq|at` 切换。

## 机制对比

| 维度 | mq 模式（主链路） | at 模式（对照） |
| --- | --- | --- |
| 库存扣减 | Redis Lua 预扣 + MQ 异步落 DB | DB 直接守卫式预占（undo_log 保护） |
| 一致性载体 | 事务消息 + 本地消息表 tx_message | Seata 全局事务（TC 协调 + undo_log） |
| 覆盖的服务 | inventory / order / payment 全链路 | 仅 order + inventory 两个分支 |
| 一致性窗口 | 下单成功 → 落库之间有窗口期（对账兜底） | 提交前锁行，无窗口期 |
| 失败恢复 | 回查 + tx_message 重试 job + 关单回扫 | TC 驱动反向 SQL（undo_log） |
| 吞吐代价 | 事件驱动，写路径短 | 每分支拿全局锁 + undo_log 双写 |
| 关单路径 | 延迟消息 + 超时扫描，回滚 redis+DB | 超时扫描，仅释放 DB 预占（release-db） |

## 失败注入实测记录（2026-09-29 实跑）

**成功路径（at）**：sku4 库存 100，下单 2 → 订单 4001：`status=CREATED, tx_mode=at`、无 tx_message（绕过 MQ）、DB `reserved 0→2`、Seata 日志 `commit status: Committed` + RM `branch commit` ×2。

**回滚注入（at）**：`PUT /stocks/4 {quantity:1}` 将可售压到 1 → 下单 5：

1. 订单行**先**插入（本地提交，这是要演示的"已创建"状态）
2. inventory `reserve-db` 守卫失败（`available-reserved=1 < 5`）→ 业务抛 `20001 库存不足`
3. **Seata 反向回滚**：日志 `branch rollback success` → `PhaseTwo_Rollbacked` → `rollback status: Rollbacked`
4. 事后断言：`orders` 表中订单 id **不存在**（已提交的订单行被 undo_log 撤销）、`product_stock.reserved` 保持 7 不变

结论：AT 的"两个分支各自本地提交、任一失败全局撤销"同滚语义在实跑中得到验证。

**主链路回归（mq）**：切回默认模式下单 1 笔 → `tx_mode=mq` 正常落库，T4/T5 的全链路（预扣→事务消息→支付→扣减）不受影响。

## 适用边界与已知取舍

- **at 只覆盖 order+inventory 两分支**，支付/关单仍复用 mq 基础设施（对照实验刻意的"小而精"）
- **at 单不触碰 Redis**：其 DB 预占与 redis sellable 之间会有对账 WARN（reconcile job 只重建缺失键、不覆盖差异键）；对照实验期间建议一机一模式，不与 mq 单混跑同一 sku
- **模式是全局开关**（经配置中心 `aurora.tx.mode` + 重启生效）：AT 的 RM 数据源代理在启动期装配
- seata-server 也会把自己注册进 Nacos（仅保留作观测/未来集群发现用途），客户端实际走 file registry 直连 `grouplist`——Nacos 里那份 172.x 容器地址宿主不可达，不影响链路
- **两个开关必须成对**：`aurora.tx.mode=at` 而 `seata.enabled=false` 会让 `@GlobalTransactional` 空转、分支失败留下已提交订单——服务已内置启动校验，此组合直接拒绝启动（拒绝静默不一致）
- 生产实践取向：**订单主链路用 mq 最终一致**（吞吐与解耦优先），AT 适合短链路强一致场景（如账户扣减的正交操作）

## 复现实操

```bash
# 1) 基础设施（含 seata-server，file registry 直连 127.0.0.1:8091）
cd docker && docker compose up -d

# 2) at 模式起 order/inventory（RM/TM 在启动期装配）
SEATA_ENABLED=true AURORA_TX_MODE=at java -jar aurora-order/target/aurora-order-*.jar &
SEATA_ENABLED=true java -jar aurora-inventory/target/aurora-inventory-*.jar &

# 3) 成功 + 回滚注入见上文；切回 mq 只需不带这两个环境变量重启
```
