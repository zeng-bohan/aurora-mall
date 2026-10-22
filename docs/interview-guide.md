# 面试导览

这份文档只做两件事：用最短路径找到代码，以及提前把项目的边界说清楚。所有数字都来自本机运行，口径见对应的压测报告。

## 一分钟介绍

Aurora Mall 是一个 Java 21 + Spring Boot 3.5 的微服务电商项目，包含 8 个可启动服务和 1 个 Vue 3 前端。交易链路覆盖下单、库存预扣、支付回调、超时关单和秒杀。项目重点不是堆中间件，而是把交易失败场景写清楚、能复现、能收敛。

## 三个可以展开讲的技术点

### 1. 秒杀：Redis 闸门 + 消息落单 + 对账收敛

- 业务问题：高并发下不能超卖、不能重复成交；预扣成功也不能因为一次发送失败就让名额一直占着不还。
- 方案：`seckill_reserve.lua` 一次完成时间窗、一人一单、库存扣减和 `PENDING` 标记。消费者用 `claim` 把状态推进到 `PROCESSING`，订单和去重行在同一事务提交，成功后写 `ORDER`，永久失败才补偿成 `FAIL`。发送结果分 `ACCEPTED / NOT_CONFIGURED / UNKNOWN`，未知结果保留 `PENDING`，由 `SeckillReconcileJob` 查订单或归还名额。
- 代码：`aurora-seckill/src/main/java/com/zengbohan/aurora/seckill/service/SeckillOrderService.java`、`SeckillOrderWriter.java`、`SeckillReconcileJob.java`、`src/main/resources/lua/seckill_*.lua`
- 证据：`SeckillLuaIntegrationTest`（真实 Redis，11 例）、`SeckillOrderTransactionIntegrationTest`（真实 MySQL，3 例）、`docker/smoke-seckill.sh`（22 条断言）。
- 边界：对账按活动扫描，按单机规模设计；Broker 长期不可用时用户会在排队态等到对账超时。

### 2. 下单幂等：数据库映射而不是“短时间防连点”

- 业务问题：首次提交成功但响应丢失，用户重试不能生成第二张订单，也不能只拿到 409。
- 方案：`order_idempotency` 表保存 `(user_id, idem_key) → order_id` 和请求指纹。映射、订单、`tx_message` 同一事务提交；同键同指纹回放原订单，同键换参数拒绝；并发同键由主键裁决，后到的一方回滚自己的库存预扣。
- 代码：`aurora-order/src/main/java/com/zengbohan/aurora/order/service/OrderService.java`、`entity/OrderIdempotency.java`、`docker/mysql/init/05-aurora_order.sql`
- 证据：`OrderServiceTest` 的回放/冲突用例、`docker/smoke-flows.sh` 的同键返回原单与换参数 40900。
- 边界：映射行长期保留，没有清理任务；请求指纹按抵扣后金额计算，不包含券 ID。

### 3. 自研 RPC：先证明等价，再谈性能

- 业务问题：服务间调用默认走 OpenFeign，项目需要一条可切换的传输路径，用来对照协议开销、连接模型和容错方式。
- 方案：18 字节定长头、长度字段拆包、requestId 挂起表、断线快速失败、心跳与重连解耦、四类异常分开、按接口隔离的熔断状态机。购物车到商品服务用同一个端口接口在 Feign 与 RPC 间切换。
- 代码：`aurora-rpc/src/main/java/com/zengbohan/aurora/rpc/`、`aurora-cart/.../port/`
- 证据：`mvn -pl aurora-rpc test`、`docker/smoke-rpc.sh`（11 条等价性断言）、`docs/m3-rpc-comparison.md`、`docs/m4-load-test.md`。
- 边界：单连接多路复用，大响应会阻塞小响应；没有连接池和 XID 透传，所以 order→inventory 仍走 Feign。50 线程隔离补测：默认 Feign 2,503/s、连接池 Feign 2,846/s、RPC 2,976/s，三组都是 0 错误；RPC 的优势主要来自连接复用，协议本身只占很小一部分。

## 可能被追问的问题

**为什么不用 Seata 覆盖整条链路？**
Seata AT 在本项目里只做订单与库存的对照实验，不能和 MQ 模式混跑同一 SKU。全量 AT 会把 Redis 预扣、MQ 事件和支付回调都拖进全局事务，复杂度和故障面都更大。项目选择最终一致 + 可对账，保留 AT 作为对照证据。

**为什么订单是单商品？**
当前 API 契约是 `{skuId, quantity}`，购物车多件结算会生成多张订单。前端如实显示“提交 N 笔订单”，没有虚构父订单。拆成父子订单需要改订单模型、支付单映射、券的作用域和关单逻辑，不是前端能单独补的。

**怎么证明没有超卖？**
三层证据：`seckill_reserve.lua` 在 Redis 内原子扣减；DB 订单表有 `(activity_id, user_id)` 唯一键；DB 库存用条件扣减 `available > 0`。压测和 `smoke-seckill.sh` 都核对 Redis 余量、DB available、订单数三者一致。

**哪些地方还不是生产级？**
库存对账不会自动覆盖已存在 key 的持续偏差；布隆过滤器是单实例内存实现；支付事件没有 dead-letter 或人工处理队列；CI 的真实中间件改造还没有在 GitHub runner 上跑过。这些都在 README 和 [reliability-fixes.md](reliability-fixes.md) 里写明，没有藏起来。

## 演示顺序

1. `docker compose up -d` → `bash docker/smoke.sh` → `bash docker/nacos/import.sh`。
2. `mvn -B clean verify`，展示 422 个用例、0 跳过。
3. `bash docker/seed-demo.sh`，启动前端，用 `admin / admin12345` 登录。
4. 商品目录 → 商品详情 → 加购 → 结算（说明多商品会生成多笔订单）→ 订单详情 → 发起模拟支付 → `bash docker/mock-pay.sh <orderId> <amount>` → 刷新状态。
5. 运营台：商品管理里对照“展示库存”和“可售库存” → 新建券模板 → 秒杀活动预热（带确认）→ 查看活动库存。
6. 可选：`bash docker/smoke-seckill.sh` 和 `bash docker/smoke-rpc.sh`。
