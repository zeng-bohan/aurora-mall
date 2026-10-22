# 技术导览：设计、代码位置与验证方式

这份文档按问题组织。每节写清楚要解决什么、实现放在哪、怎么验证，以及当前方案的边界。

## 1. 商品 EAV 与目录查询

**要解决的问题**：18 个 SPU、45 个 SKU，分类和属性都要能筛选；SKU 的属性组合必须唯一，不能靠前端拼接字符串防止重复。

**做法**：
- 属性字典独立成表，属性带类型、单位、可筛选、变体、必填、展示和排序字段。
- SPU 与属性值绑定放在 `product_spu_attribute` / `product_spu_attribute_value`；SKU 与属性值绑定放在 `product_sku_attribute`。
- SKU 保存 `combination_key` 并加唯一索引，保存 `sku_code` 并加唯一索引。保存商品时同时校验：变体属性必填、组合键不重复、SKU 编码不重复。
- 目录查询从分类、关键词、价格带、属性值开始过滤，再组装 SPU 卡片。SKU 库存通过库存服务批量查询，库存服务不可用时返回 `null`，页面显示“库存待确认”，不伪造数字。

**代码**：`aurora-product/.../service/ProductQueryService.java`、`ProductAdminService.java`、`dto/ProductDtos.java`；表结构见 `docker/mysql/init/02-aurora_product.sql`，目录数据见 `12-dtc-catalog.sql`。

**验证**：目录、关键词、分类、价格与属性筛选在浏览器中逐项检查；运营台商品编辑校验必填变体与重复组合。前端类型检查覆盖 DTO 字段。

**边界**：筛选在 Java 侧完成，当前数据量下可接受。SKU 数量上到万级后，应改为索引化查询或搜索引擎。

## 2. 父订单、定价与退款金额

**要解决的问题**：一次结算有多个 SKU，但只能有一次支付、一次发货。优惠要按商品分摊，退款后金额必须对得上。

**做法**：
- 父订单 `trade_order` 保存商品金额、优惠、运费、应付、已退和地址快照；明细 `trade_order_item` 保存 SKU 快照与分摊后的金额。
- 定价统一走 `TradePricingService`：金额换算成整数分；优惠按商品金额占比分摊，最大余数法补齐；券后满 299 免运费，否则收 12 元。
- 部分退款按商品原始金额计算，不退运费。退款后剩余商品金额低于券门槛时，只允许整单退款。整单退款退回剩余实付并返还优惠券。秒杀订单只允许整单退款。
- 退款审批和外部结算分开：审批只改订单、明细和退款记录；支付退款与库存回补由 `settle()` 执行，失败由 `RefundSettlementJob` 重试。

**代码**：`aurora-order/.../service/TradeService.java`、`TradePricingService.java`、`RefundService.java`；表结构见 `05-aurora_order.sql`。

**验证**：
- `TradePricingServiceTest`（8 例）：分摊总额、运费门槛、部分退款后保留券、低于门槛拒绝、整单退款、部分后退整单、秒杀限制、优惠重分摊。
- `TradeServiceIntegrationTest`（6 例，真实 MySQL）：下单幂等、同键换内容、事务失败库存回补、关单、迟到支付、发货与确认收货。
- 浏览器流程：多商品结算、部分退款、运营审批、发货、确认收货。

**边界**：退款申请按订单串行处理；同一订单不能并行审批多笔退款。退款金额只按商品原价，不重新计算已使用优惠的退回。

## 3. 下单幂等

**要解决的问题**：网络超时后用户重试，不能生成第二张订单；同一个键换了内容，也不能悄悄下成另一单。

**做法**：
- `order_idempotency` 以 `(user_id, idem_key)` 为主键，保存 `trade_id` 和请求指纹。
- 映射、订单、明细、优惠券绑定、`tx_message` 在同一个本地事务里提交。
- 同键同指纹返回原订单；同键不同指纹抛 `DUPLICATE_REQUEST`。
- 并发同键由主键裁决：后到事务回滚并释放已预扣库存，再查询赢家订单返回。

**代码**：`TradeService.place()`、`insertIdempotency()`、`replay()`；`entity/OrderIdempotency.java`。

**验证**：`TradeServiceIntegrationTest` 的 `duplicateIdempotencyKeyReturnsTheOriginalTradeAndReservesOnce` 与 `reusingAKeyForDifferentContentIsRejectedWithoutSecondReserve`。

**边界**：幂等记录长期保留，没有清理任务。前端在提交中禁用按钮，但最终仍以服务端唯一键为准。

## 4. 库存预扣、确认与回补

**要解决的问题**：多个 SKU 一起扣库存时必须原子；重复预扣、重复释放不能多扣或多还；Redis 与 DB 不能各说各话。

**做法**：
- Redis 批量 Lua 一次检查所有 SKU 的可售量，任一不足就整批失败；全部满足才统一扣减，并写入父订单守卫，防止同一订单重复预扣。
- DB 侧 `product_stock` 记录可售与已预占，`stock_reservation` 按 `(trade_id, sku_id)` 记录 `RESERVED / CONFIRMED / RELEASED`。
- 支付成功确认预占；关单释放预占；退款按 `(refund_id, sku_id)` 写入 `stock_restore` 后回补可售库存。
- Redis 键缺失时从 DB 重建；键存在但与 DB 有差异时只告警，不自动覆盖。

**代码**：`aurora-inventory/.../stock/StockService.java`、`StockLuaScripts.java`、`lua/stock_batch_{reserve,release,restore}.lua`、`stock_set.lua`；表结构见 `04-aurora_inventory.sql`。

**验证**：`StockServiceIntegrationTest`（5 例，真实 MySQL + Redis）覆盖批量预扣、第二个 SKU 不足时整批不扣、释放幂等、确认后退款回补、并发抢最后一件只成功一次。

**边界**：对账只重建缺失键，不自动修复有差异的键；管理员改库存时用 DB 行锁加 Redis CAS，有在途变更就拒绝。

## 5. 支付回调竞态与退款

**要解决的问题**：支付成功和超时关单可能同时发生；重复回调不能重复加钱；关单后的支付必须退回。

**做法**：
- 回调先校验 HMAC 签名，再比对支付单金额。
- `markPaid` 是 `WHERE status='PAYING'` 的条件更新；更新后重新查单。
- 如果订单已关闭，登记迟到退款（`markRefundedLate`），不发布支付成功事件。
- 如果支付赢了关单，之后关单再执行时会再次查单，发现已支付就停止关单。
- 退款按退款单号幂等：`payment_refunds` 先插入，重复请求直接返回；`applyRefund` 用 `amount - refunded_amount >= amount` 防止超退。

**代码**：`aurora-payment/.../service/PaymentService.java`、`PaymentController.java`、`PaymentEventPublisher.java`；表结构见 `06-aurora_payment.sql`。

**验证**：`PaymentServiceTest`（7 例）覆盖签名拒绝、正常回调只发布一次、关单退款、关单赢竞态、金额不符、退款幂等、迟到退款。

**边界**：支付事件重试依赖 `event_published` 与重试任务；没有死信队列。渠道是模拟实现，不对接真实支付。

## 6. 秒杀：预扣、排队与归还

**要解决的问题**：活动库存独立，一人一单，不能超卖；异步落单失败要把名额还回去。

**做法**：
- `seckill_reserve.lua` 原子完成时间窗、一人一单、库存扣减和 `PENDING` 结果。
- 异步落单由 MQ 消费，结果状态机为 `PENDING → PROCESSING → ORDER / FAIL`；认领、补偿、重置都用 Lua。
- 落单统一调用订单服务创建父订单，不另建一套订单表。
- 关单或整单退款时，按父订单幂等归还活动库存（`seckill_release`），并同步增加 Redis 活动余量。
- 发送结果未知时保留 `PENDING`，由 `SeckillReconcileJob` 查单确认或归还名额。

**代码**：`aurora-seckill/.../service/SeckillOrderService.java`、`SeckillActivityService.java`、`SeckillPreheatService.java`、`lua/seckill_{reserve,claim,reset_pending,compensate}.lua`。

**验证**：
- `SeckillOrderServiceIntegrationTest`（5 例，真实 MySQL + Redis）：同步落单与二次抢购拒绝、售罄、未开始、退款归还、5 人抢 2 件只成功 2 人。
- 浏览器全流程：运营创建并预热 → 用户抢购 → 支付 → 整单退款 → 运营审批 → 活动库存回到 5/5。

**边界**：对账按活动扫描，单机规模；限流是活动维度；没有分布式锁，靠 Redis Lua 的原子性。

## 7. 关单消息与重试

**要解决的问题**：下单后要可靠触发超时关单，不能因为消息发送失败让订单一直挂着。

**做法**：下单事务写 `tx_message`；发送 RocketMQ 延迟消息，成功后标记已发送。`TxMessageRetryJob` 重发未发送记录，`CloseTimeoutJob` 扫描超时订单兜底。关单按父订单幂等，重复消息不会重复释放。

**代码**：`aurora-order/.../mq/{OrderEventPublisher,TxMessageRetryJob,CloseTimeoutJob,TradeCloseListener}.java`。

**验证**：关单与迟到支付相关用例在 `TradeServiceIntegrationTest`、`PaymentServiceTest`；浏览器中把订单取消后检查库存与券状态。

**边界**：`tx_message` 没有归档任务；消息重试没有上限，持续失败会产生重复日志。

## 8. 前端设计系统与真实 API

**要解决的问题**：页面要像一个正常消费电子品牌，而不是后台模板；所有交互必须接真实接口。

**做法**：
- 暖白、浅灰、深墨色与少量品牌橙；中文无衬线字体搭配现代英文无衬线字体。
- 设计令牌放在 `styles/tokens.css`，基础组件在 `styles/base.css`，商城与运营台分别使用 `storefront.css` / `app.css`。
- 商城使用编辑式首页、两列商品网格、详情画廊与 SKU 选择；运营台是独立侧栏布局。
- 不展示虚构评价、评分和销量。支付与物流页面明确标注为演示流程。
- 前端只通过 `src/api/dtc.ts` 调接口，类型与后端 DTO 对齐。

**代码**：`aurora-web/src/styles/`、`src/views/`、`src/components/`、`src/api/dtc.ts`。

**验证**：`npm run typecheck && npm run build`；`scripts/ui-storefront.mjs`、`ui-admin.mjs`、`ui-seckill-flow.mjs`、`ui-mobile.mjs` 在 Chrome 中跑真实流程并检查控制台错误与横向溢出。

**边界**：商品图是概念渲染；没有真实支付、物流或评价系统。

## 9. 测试体系

- 纯单元测试：定价、签名、状态机、缓存、限流、ID 生成。
- 真实 MySQL 测试：订单幂等与关单、支付退款、库存预约、秒杀预约。
- 真实 Redis 测试：库存与秒杀 Lua 的原子性、并发防超卖。
- 浏览器测试：商城购物、运营审批、秒杀全流程、桌面与 390px 布局。

CI 启动 MySQL 8.4 与 Redis 7，导入与本地相同的建表 SQL，运行 `mvn -B clean verify`；库存和秒杀报告出现 skipped 会让构建失败。
