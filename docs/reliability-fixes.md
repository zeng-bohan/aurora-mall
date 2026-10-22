# 可靠性与一致性修复记录

记录本次针对订单、库存、支付、秒杀、缓存、RPC 和熔断做的修复。每条写清楚：原来的风险、现在的做法、代码位置、验证证据和还没解决的边界。

验证环境：本机 Windows 11、JDK 21、MySQL 8.0（`localhost:13306`）、Redis 7（`localhost:16379`）、RocketMQ 5。命令是 `mvn -B clean verify`，另加针对单模块的测试命令。CI 的改动见文末，尚未在 GitHub runner 上实际跑过。

## 1. 秒杀落单：去重与订单同事务

**原来的风险**：`SeckillOrderService.materialize()` 上的 `@Idempotent(DB_DEDUP)` 先提交去重行，订单写入在另一个 Bean 的独立事务里。去重行插入成功、订单还没提交时进程崩溃，重投递会被去重守卫直接放行成“已处理”，订单永远不会生成，名额和已购标记也不归还。

**现在的做法**：
- 去重和订单写入收进 `SeckillOrderWriter.placeOnce()` 的同一个 `@Transactional`。先插去重行，再写订单和扣 DB 库存；任一步失败整体回滚，重投递可以重新处理。
- 受理结果改成状态机：`PENDING:<毫秒>` → `PROCESSING:<毫秒>` → `ORDER:<id>` 或 `FAIL:<原因>`。消费者先用 Lua 认领（`seckill_claim.lua`），补偿 Lua 只允许从 `PENDING/PROCESSING` 归还名额，避免“对账已经退款、迟到消息又写订单”。
- 瞬时失败用 `seckill_reset_pending.lua` 把 `PROCESSING` 退回 `PENDING`，交给 MQ 重投递。
- 新增 `SeckillReconcileJob`：每 30 秒扫描结束时间在一天内的活动，把超过 2 分钟仍未落单的 `PENDING`（或超过 5 分钟的 `PROCESSING`）收敛为订单或失败。

**代码**：`aurora-seckill/.../service/SeckillOrderWriter.java`、`SeckillOrderService.java`、`SeckillReconcileJob.java`、`src/main/resources/lua/seckill_{reserve,claim,reset_pending,compensate}.lua`

**验证**：
- `SeckillOrderTransactionIntegrationTest`（3 例，真实 MySQL）：成功落单时去重行和订单同时存在；DB 库存扣减失败时去重行随事务回滚；重复 messageId 只落一张订单。
- `SeckillLuaIntegrationTest`（11 例，真实 Redis）：补偿后迟到的消息被 claim 拒绝；已成交的结果不能被补偿；瞬时失败重置后可以重新认领。
- `SeckillOrderServiceTest`（26 例）：认领拒绝、永久失败补偿、瞬时失败重置、对账收敛。

**边界**：
- 对账任务按活动扫描结果 hash，活动量特别大时需要改成按键分片；当前按单机规模设计。
- MQ 发送超时仍可能造成“用户看到排队、两分钟后才失败”，这是保留不确定结果的代价。

## 2. 秒杀发送结果：区分“没发出去”和“不知道”

**原来的风险**：`SeckillEventPublisher` 只看有没有抛异常。`FLUSH_DISK_TIMEOUT` 等状态说明 Broker 已收到消息，却被当成成功；发送超时则被当成失败并立即补偿。补偿后消息仍可能到达，形成“报失败却出了订单”。

**现在的做法**：
- 发送结果分三类：`ACCEPTED`（`SEND_OK` 或 Broker 已接收的刷盘/副本超时状态）、`NOT_CONFIGURED`（没有 MQ 模板，确定没发）、`UNKNOWN`（异常/超时）。
- `NOT_CONFIGURED` 立即补偿并报错；`UNKNOWN` 不补偿，结果保持 `PENDING`，交给对账任务确认。
- 对账先查订单：订单存在就补记 `ORDER`，确认不存在才补偿。

**代码**：`aurora-seckill/.../mq/SeckillEventPublisher.java`、`SeckillOrderService.publishAsync()`、`SeckillReconcileJob`

**验证**：`SeckillEventPublisherTest`（4 例）覆盖 Broker 超时仍算接收、异常返回 UNKNOWN、模板缺失返回 NOT_CONFIGURED；`SeckillOrderServiceTest` 覆盖 UNKNOWN 不补偿。

**边界**：没有引入事务消息或独立 outbox。Broker 长期不可用时，用户会在排队态停留到对账超时；这是“宁可晚一点失败，也不把已送达的消息误判为丢失”的取舍。

## 3. 下单幂等：同键返回同一张订单

**原来的风险**：下单只靠 Redis 守卫 `setIfAbsent`，重复键直接抛 `DUPLICATE_REQUEST`，不保存订单号。首次提交成功、响应丢失后，用户 10 分钟内重试只会拿到 409，不知道订单已经建好；Redis 记录过期或丢失后重试会生成第二张订单。

**现在的做法**：
- 新增 `order_idempotency` 表，主键 `(user_id, idem_key)`，保存 `order_id` 和请求指纹（SKU、数量、抵扣后金额、是否用券的 SHA-256）。
- 映射行与订单、`tx_message` 在同一个本地事务提交。同键同指纹直接返回原订单；同键不同指纹拒绝。
- 并发同键由主键裁决：后到的事务回滚订单和库存预扣，再查询已提交的订单号返回。
- 去掉了覆盖整个方法的 Redis 守卫，改为“数据库唯一键 + 请求指纹”。短时间防连点仍由前端提交中状态兜底。

**代码**：`aurora-order/.../service/OrderService.java`、`entity/OrderIdempotency.java`、`mapper/OrderIdempotencyMapper.java`、`docker/mysql/init/05-aurora_order.sql`

**验证**：`OrderServiceTest` 新增回放与同键换参数用例；`mvn -pl aurora-order -am test` 20 例通过。

**边界**：
- 映射行长期保留，没有清理任务；每张订单一行，当前规模可以接受。
- 请求指纹包含抵扣后金额，不包含券 ID。两张不同券刚好抵出同一金额时，会被视为同一请求；实际业务里没有复现路径，但这是已知的口径。
- Seata AT 对照路径只做“先查映射、落单后补写映射”，不保证与全局事务一起提交或回滚。

## 4. 库存回滚：Redis 键丢失时不再写入错误库存

**原来的风险**：`stock_rollback.lua` 只 SETNX 释放标记，然后无条件 `INCRBY` 库存键。Redis 重启后库存键丢失，重入回滚会把“本次订单数量”写成整个可售库存，造成严重少卖。

**现在的做法**：只有库存键存在时才 `INCRBY`。键缺失时只写释放标记，等对账任务用 DB 的 `available - reserved` 重建。

**代码**：`aurora-inventory/src/main/resources/lua/stock_rollback.lua`

**验证**：`StockLuaIntegrationTest` 新增“缺失的 key 不能被回滚创建”和库存 CAS 用例，共 10 例通过（真实 Redis）。

**补充修复（管理员设置库存）**：`PUT /admin/stocks/{skuId}` 原来直接 `SET` Redis，会把在途预扣重新放回可售。现在在 DB 事务里锁行，算出 `available - reserved` 作为期望值，再用 `stock_set.lua` 做 Redis CAS：值不一致说明有预扣/释放未落账，抛业务错误并回滚 DB，让管理员稍后重试。

**代码**：`aurora-inventory/.../stock/StockService.java`、`src/main/resources/lua/stock_set.lua`、`mapper/ProductStockMapper.selectForUpdate`

**验证**：`StockServiceTest`（20 例）覆盖 CAS 成功与在途冲突；`StockLuaIntegrationTest`（10 例）覆盖期望值不匹配、key 缺失两种分支。

**边界**：Redis 不可用时设置库存会失败（宁可拒绝，也不写一个无法 CAS 的值）；没有提供“强制覆盖”入口，确实需要人工干预时只能走对账/运维脚本。

## 5. 支付事件：模板缺失不再静默丢弃

**原来的风险**：`PaymentEventPublisher` 在 `RocketMQTemplate` 缺失时只打一条 WARN 就返回；`PaymentService.publishPaid()` 随后仍然 `markEventPublished`，支付成功事件永久丢失。

**现在的做法**：模板缺失或发送结果没有状态时抛异常，调用方不会打发布标记，`PaymentEventRetryJob` 会继续补发。

**代码**：`aurora-payment/.../mq/PaymentEventPublisher.java`

**验证**：`PaymentEventPublisherTest` 覆盖模板缺失和无发送状态两种情况。

**补发策略**：不做「失败 10 次就永久放弃」。`payment_orders` 本身就是本地消息表，消费端幂等，Broker 恢复后应继续投递；`publish_attempts` 留给监控和告警。没有接 dead-letter 或人工处理队列，长期失败仍需要看日志/指标。

## 6. 商品缓存：Redis 故障降级到 DB

**原来的风险**：缓存读写没有异常处理，Redis 抖动会让商品详情直接 500；重建线程池满时 `execute` 抛 `RejectedExecutionException`，互斥锁还留在 Redis 里 10 秒。

**现在的做法**：
- 所有缓存操作经过安全包装。读失败当作未命中回源 DB，写失败只记日志，删除失败不影响主流程。
- 互斥锁拿不到或缓存不可用时直接回源 DB。
- 重建任务被线程池拒绝时释放互斥锁，本次继续返回旧值。

**代码**：`aurora-product/.../cache/ProductCacheService.java`

**验证**：`ProductCacheServiceTest` 新增一个所有操作都抛异常的 `FailingCacheStore`，确认详情仍从 DB 返回、`evict` 不抛。

**仍未修复**：布隆过滤器重建与新建商品写入仍有竞态，极端情况下新商品会在下一轮重建前被误判为不存在；多实例各自维护本地位图，也没有跨实例同步。这是单实例设计的已知边界，README 已写明。

## 7. 熔断器：忽略异常不再泄漏半开槽位

**原来的风险**：半开状态下抛出的 ignored 异常不进入 `recordResult`，`halfOpenInFlight` 只增不减。连续几次配置类异常后，熔断器会永远卡在 HALF_OPEN。

**现在的做法**：ignored 异常单独走 `releaseIgnoredProbe`，只归还槽位，不改变成功计数。

**代码**：`aurora-ratelimit/.../circuit/CircuitBreaker.java`

**验证**：`CircuitBreakerTest` 新增 `ignoredProbeFailuresReleaseHalfOpenSlots`，12 例通过。

## 8. RPC 握手失败：从静默断连改成可归类的错误

**原来的风险**：客户端密钥错误时，服务端只 `ctx.close()`，客户端只能看到连接丢失，把配置错误计入熔断失败率，还会每 500ms 重连一次。

**现在的做法**：服务端在断连前回一个 `CONTROL` 帧，状态是 `UNAUTHORIZED`；客户端收到后停止重连，并把在途调用以 `RpcUnauthorizedException` 失败。心跳 PONG 仍走普通控制帧，不会被误关连接。

**代码**：`aurora-rpc/.../protocol/RpcFrame.java`、`transport/RpcServer.java`、`transport/RpcClient.java`

**验证**：`RpcTransportIntegrationTest` 新增 `badHandshakeGetsAControlUnauthorizedFrame`，14 例通过。

**边界**：客户端把 `unauthorized` 视为终态；修正密钥后需要重新创建客户端实例，没有做运行时热更新。

## 9. CI：真实中间件成为必过项

`.github/workflows/ci.yml` 的 `build-test` 现在启动 MySQL 8.4 和 Redis 7，映射到本地相同的 13306/16379，导入 `docker/mysql/init/*.sql` 后再跑 `mvn -B clean verify`。最后一步检查 seckill 和 inventory 的 surefire 报告：这两个模块出现任何 skipped 都会让构建失败。

**尚未验证**：工作流没有在 GitHub Actions 上实际跑过。本地用同一套中间件跑通了上述测试；首次推送后需要看一次真实 CI 结果。
