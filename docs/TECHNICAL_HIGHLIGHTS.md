# 技术导览：设计、代码位置与验证方式

这份文档给面试或代码审查用。每一项写清楚要解决什么问题、实现放在哪、怎么验证，以及当前方案的边界。

## 1. 自研 RPC：协议、复制、熔断与失败语义

**要解决的问题**：服务间调用默认走 OpenFeign。项目需要一条可以切换的传输路径，用来对照协议开销、连接模型和容错方式。

**协议**：18 字节定长头加 body。头字段为 magic(2) / version(1) / type(1) / serializer(1) / status(1) / requestId(8) / bodyLength(4)，大端。拆包统一用 `LengthFieldBasedFrameDecoder`，长度字段在 offset 14，body 上限 10MB。

**调用**：客户端每次调用分配自增 requestId，放进 `ConcurrentHashMap` 的挂起表；响应按 requestId 找回 Future。发送失败立即让该调用失败，断线时 `failAllPending` 让所有在途调用立即可失败，不必等到超时。心跳与重连解耦，空闲超过心跳间隔才发 ping。

**失败语义**：四类异常分开。`RpcRemoteException` 是业务失败，不走熔断；`RpcUnavailableException` 是不可达或过载，计入熔断；`RpcTimeoutException` 是超时，计入熔断；`RpcUnauthorizedException` 是握手密钥不一致，属于配置错误，单独成型且不计失败率。

**代码位置**：`aurora-rpc/src/main/java/com/zengbohan/aurora/rpc/protocol/ProtocolCodec.java`、`transport/RpcClient.java`、`transport/RpcServer.java`、`proxy/RpcProxyFactory.java`、`proxy/RpcClientPool.java`

**验证**：`mvn -pl aurora-rpc test`，13 个传输集成用例、10 个编解码用例、8 个全链路代理用例。传输等价性用 `bash docker/smoke-rpc.sh`（11 条断言），并发对照见 [m4-load-test.md](m4-load-test.md)。

**边界**：单连接多路复用，没有连接池，大响应会阻塞小响应；未实现 Seata XID 透传，所以 order→inventory 保持 Feign；注册中心只实现注册、订阅与健康过滤，没有权重和集群路由。

## 2. 秒杀：Lua 原子预扣与失败补偿

**要解决的问题**：抢购要在高并发下同时保证时间窗、一人一单和库存不超卖，且落单失败时名额必须还回去。

**做法**：`seckill_reserve.lua` 在一次脚本执行里完成时间窗判断、已购集合检查、库存扣减和结果写入 PENDING。`seckill_compensate.lua` 用 `SREM` 的返回值作为幂等凭据，只有确实移除掉已购标记时才回补库存，避免补偿重放造成超卖。两条路径的结果都写进同一个结果 hash，客户端只查这一个键。

**MQ 模式**：预扣成功后投递消息即返回，请求路径不碰 DB。消费者写入订单与扣减 DB 库存在同一个事务里，DB 是最终事实。投递失败、落单永久失败（DB 库存已尽）都会补偿。瞬时失败抛给 MQ 重投，重投由 `DB_DEDUP` 幂等守卫兜住。

**代码位置**：`aurora-seckill/src/main/resources/lua/seckill_reserve.lua`、`seckill_compensate.lua`、`service/SeckillOrderService.java`、`service/SeckillOrderWriter.java`、`mq/SeckillOrderListener.java`

**验证**：`mvn -pl aurora-seckill test`。Lua 脚本的真实 Redis 用例在中间件可用时运行，否则跳过。压测与一致性结论见 [m5-seckill-load-test.md](m5-seckill-load-test.md)：20 万级尝试下恰好成交 3000，DB 与 Redis 同时归零，无残留 PENDING。

**边界**：结果 hash 的 TTL 到活动结束后一天，之后只能回落到 DB 查询；限流是活动维度，不区分用户。

## 3. 事务一致性：本地消息表、事务消息与回查

**要解决的问题**：下单要写订单、扣库存，还要保证后续事件不丢。

**做法**：本地事务里同时写 `orders` 与 `tx_message`。RocketMQ 事务消息的确认以 `tx_message` 行是否存在为锚，同一个查询也用于回答 broker 的回查，因此本地提交与确认之间崩溃时最终解析为一次 COMMIT。提交后发送失败不回滚订单，由 `TxMessageRetryJob` 重发；关单由延迟消息触发，`CloseTimeoutJob` 扫描兜底，两条路径都会回券。

**代码位置**：`aurora-order/src/main/java/com/zengbohan/aurora/order/service/OrderService.java`、`mq/OrderTxListener.java`、`mq/TxMessageRetryJob.java`、`mq/CloseTimeoutJob.java`

**验证**：`OrderTxListenerTest`、`TxMessageRetryJobTest`、`OrderServiceTest`。AT 与 MQ 的对照见 [m2-tx-comparison.md](m2-tx-comparison.md)，其中包含回滚注入后的订单行撤销与库存不变断言。

**边界**：AT 模式只覆盖订单与库存，且不触碰 Redis；两种模式不能混跑同一 SKU。

## 4. 库存预扣与未知结果补偿

**要解决的问题**：order 调用 inventory 预扣时如果超时，结果未知：可能扣了，也可能没扣。直接重试可能重复扣减。

**做法**：预扣按订单号幂等，库存侧只在确实预扣过时才回补。order 在收到传输类失败时调用补偿，补偿对没落地过的预扣不会凭空加库存。库存服务还有对账任务处理 Redis 与 DB 的偏差。

**代码位置**：`aurora-order/.../OrderService.java` 的 `reserveStock` 与 `rollbackStockQuietly`；`aurora-inventory/.../stock/StockService.java`

**验证**：`OrderServiceTest` 覆盖未知结果补偿；`StockServiceTest` 覆盖重复预留、已释放订单的中和与对账重建。

## 5. 缓存穿透与缓存一致性

**要解决的问题**：商品详情是读多写少的热点，需要挡住不存在的 ID，也要避免更新时读到旧值。

**做法**：读取顺序是布隆过滤器 → 空值缓存 → 逻辑过期信封。逻辑过期允许单个重建者刷新期间继续返回旧值，物理 TTL 比逻辑过期长并带抖动。布隆过滤器用写时复制的方式整体替换，读路径不加锁。

**代码位置**：`aurora-product/src/main/java/com/zengbohan/aurora/product/cache/ProductCacheService.java`、`StringBloomFilter.java`、`VolatileBloomFilterHolder.java`

**验证**：`mvn -pl aurora-product test`，覆盖布隆过滤器、空值缓存与逻辑过期。

**边界**：布隆过滤器是进程内实现，多实例各自一份，新商品写入后其他实例要等重建；重建策略按本项目的单机部署设计。

## 6. 网关与内部接口的访问边界

**要解决的问题**：网关会为转发流量注入身份头，下游内部接口（`/internal/**`、`/stocks/**`）不能被普通登录用户借道调用。

**做法**：网关的 `InternalPathGuardFilter` 按 StripPrefix 语义推算下游实际会看到的路径，再做与 Tomcat 一致的归一化（剥矩阵参数、折叠空段、解析点段），命中内部路径返回 404，不暴露端点存在性。库存服务的 `StockUserIdentityGuardFilter` 用 `UrlPathHelper` 解析路径，与 MVC 的匹配视图保持一致；携带 `X-User-Id` 的请求一律 403。

**代码位置**：`aurora-gateway/src/main/java/com/zengbohan/aurora/gateway/auth/InternalPathGuardFilter.java`、`aurora-inventory/src/main/java/com/zengbohan/aurora/inventory/web/StockUserIdentityGuardFilter.java`

**验证**：`InternalPathGuardFilterTest`（5 例）、`StockUserIdentityGuardFilterTest`（7 例）。后者包含矩阵参数绕过的回归用例：`/stocks;x=1/76` 必须仍进入身份校验。

**边界**：管理端 `/admin/stocks/**` 刻意不在用户身份守卫范围内，它的授权依赖 `@RequireAdmin`，相关断言在 `AdminStockControllerTest`。

## 7. 幂等

**要解决的问题**：下单、落单、支付回调都可能重放。

**做法**：下单用 `Idempotency-Key` 请求头加 Redis 去重，键为用户与幂等键的组合；秒杀落单用消息里的 `messageId` 做 DB 去重；支付与关单的状态迁移是带前置状态的条件更新，重放只会命中零行。

**代码位置**：`aurora-common/src/main/java/com/zengbohan/aurora/common/idempotent/`、`aurora-order/.../OrderService.java`、`aurora-seckill/.../SeckillOrderService.java`

**验证**：`IdempotentAspectTest`、`JdbcDedupStoreTest`、`OrderServiceTest` 与 `SeckillOrderServiceTest` 的重放用例。

## 8. 分布式 ID

**要解决的问题**：订单号需要全局唯一、趋势递增，不能依赖数据库自增。

**做法**：号段模式从数据库批量取号，双缓冲在当前号段消耗到 60% 时预先取下一段；雪花模式包含时钟回拨处理，workerId 优先取显式配置，缺省按 hostname 与 pid 推导。订单号由它生成；秒杀订单仍用数据库自增主键。

**代码位置**：`aurora-id-generator/src/main/java/com/zengbohan/aurora/id/`

**验证**：`mvn -pl aurora-id-generator test`，覆盖并发唯一性、号段切换与时钟回拨。

## 9. 限流与熔断

**要解决的问题**：网关需要限流，服务间调用需要熔断。

**做法**：限流有令牌桶与滑动窗口两种实现，网关用 Redis 滑动窗口在多实例间共享计数；熔断器按接口隔离，状态机为 CLOSED / OPEN / HALF_OPEN，半开状态并发试探有界，配置错误类异常不计入失败率。

**代码位置**：`aurora-ratelimit/src/main/java/com/zengbohan/aurora/ratelimit/`、`aurora-gateway/.../ratelimit/`

**验证**：`SlidingWindowRateLimiterTest`、`TokenBucketRateLimiterTest`、`CircuitBreakerTest`、`GatewayRateLimitFilterTest`。

## 10. 测试体系

**做法**：用例按依赖分层。不依赖外部服务的用例默认运行；需要 MySQL 或 Redis 的用例先探测连接，可用时执行，不可用时跳过。跳过不是静默的，surefire 报告里能看到具体数量。

**代码位置**：`aurora-*/src/test/java`、`.github/workflows/ci.yml`

**验证**：`mvn -B clean verify`，当前 395 个用例，0 失败，38 个跳过。

**边界**：跳过的用例在本地不起中间件时不会验证 Lua 脚本与并发语义，CI 也没有起中间件。这部分只能靠本地 `docker compose up -d` 后的完整运行补上。
