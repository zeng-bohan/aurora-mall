# 交易可靠性：问题、实现与验证

记录 DTC 重构后交易链路的可靠性设计。每节写清楚原来的风险、现在的做法、代码位置、验证证据和剩余边界。

验证环境：Windows 11、JDK 21、MySQL 8.0（`localhost:13306`）、Redis 7（`localhost:16379`）、RocketMQ 5。命令是 `mvn -B clean verify`，另加浏览器流程脚本。

## 1. 父子订单与多商品结算

**风险**：旧模型一张订单只放一个商品，多商品结算会被拆成多笔订单，优惠券、支付和发货无法统一。

**做法**：
- `trade_order` 保存父订单金额和地址快照；`trade_order_item` 保存每个 SKU 的快照、数量、分摊后的优惠和退款金额。
- 一次结算只生成一个父订单，只支付一次、发货一次。
- 商品金额、优惠、运费、应付和退款全部由 `TradePricingService` 计算，避免 Controller、Service 和前端各算一套。

**代码**：`aurora-order/.../service/TradeService.java`、`TradePricingService.java`、`entity/TradeOrder.java`、`TradeOrderItem.java`。

**验证**：多商品结算的金额与分摊由 `TradePricingServiceTest` 覆盖；浏览器流程完成两件商品结算、支付、部分退款和发货。

**边界**：退款申请按订单串行处理，同一订单不能并行审批多笔退款。

## 2. 下单幂等

**风险**：响应丢失后重试会生成第二张订单。

**做法**：
- `order_idempotency` 主键为 `(user_id, idem_key)`，保存订单号和请求指纹。
- 映射、订单、明细、优惠券绑定和关单消息在同一本地事务提交。
- 同键同指纹返回原订单；同键不同指纹拒绝；并发同键由唯一键裁决，后到事务回滚并释放库存。
- 指纹只由地址、券和排序后的 SKU/数量组成；重试先查幂等映射，再校验商品和券。商品下架或券已核销不会让同一笔下单重试失败。

**代码**：`TradeService.place()`、`insertIdempotency()`、`replay()`；`entity/OrderIdempotency.java`。

**验证**：`TradeServiceIntegrationTest`（20 例，真实 MySQL）验证同键重放只预扣一次、券核销后重放、商品下架后重放、同键换内容被拒绝、事务失败后库存被释放。

**边界**：幂等记录没有清理任务；每张订单一行，当前规模可接受。

## 3. 支付与关单竞态

**风险**：支付成功和超时关单同时发生；关单后的支付如果被当成成功，会造成“已退款订单又发货”。

**做法**：
- 回调先校验 HMAC 签名，再比对金额。
- `markPaid` 用 `WHERE status='PAYING'` 条件更新；支付成功后重新查订单。
- 如果订单已关闭，登记迟到退款，不发布支付成功事件。
- 如果支付先赢，关单逻辑再次查单时会发现已支付并停止关单。
- 退款按退款单号幂等；退款记录与支付单累加在同一事务落账，失败时一起回滚，不会留下“已记退款但没扣款”的孤儿记录。
- `applyRefund` 用剩余可退金额做条件更新，防止超退；部分退款保持 `PARTIALLY_REFUNDED`，累计到应付金额才转 `REFUNDED`。

**代码**：`aurora-payment/.../service/PaymentService.java`、`PaymentController.java`、`PaymentOrderMapper.applyRefund()`；`aurora-order/.../service/TradeService.markPaid()`。

**验证**：`PaymentServiceTest`（7 例）覆盖签名拒绝、正常回调只发布一次、关单退款、关单赢竞态、金额不符、退款幂等和迟到退款。`PaymentRefundIntegrationTest`（5 例，真实 MySQL）覆盖部分退款状态、失败退款回滚、退款单号重放与并发重试。`TradeServiceIntegrationTest` 验证关单后支付只退款、不确认库存。

**边界**：支付事件重试没有死信队列；渠道是模拟实现。

## 4. 库存预扣与补偿

**风险**：多个 SKU 扣库存时只扣了一半；order 调用超时后不知道库存有没有扣；退款重复回补导致超卖。

**做法**：
- Redis 批量 Lua 一次检查所有 SKU，任一不足整批失败；全部满足才扣减，并写父订单守卫。
- DB 侧 `product_stock` 与 `stock_reservation` 记录可售、已预占和预约状态。
- 支付成功确认预占；关单释放；退款按 `(refund_id, sku_id)` 写入 `stock_restore` 后回补。
- Redis 键缺失时从 DB 重建；有差异的现存键只告警，不自动覆盖。
- 预扣补偿失败后，重试可用 Redis 守卫和本次请求重建缺失的 DB 台账。
- 释放时即使 DB 台账已标记 `RELEASED` 或整体缺失，也会用台账/请求数量重试 Redis，避免库存永久少算。
- 退款回补增加退款级 Redis 守卫；DB 已提交但 Redis 失败时，结算重试不会把同一笔退款加两次。
- 运营台提供“重建 Redis”，演示数据脚本播种后自动按 DB 同步；对账任务仍只自动补缺失键。

**代码**：`aurora-inventory/.../stock/StockService.java`、`StockLuaScripts.java`、`lua/stock_batch_{reserve,release,restore}.lua`、`stock_refund_restore.lua`、`stock_set.lua`。

**验证**：`StockServiceIntegrationTest`（10 例，真实 MySQL + Redis）覆盖批量预扣、台账缺失恢复、第二个 SKU 不足时整批不扣、释放幂等与 Redis 修复、确认后退款回补、回补幂等、Redis 重建和并发抢最后一件只成功一次。

**边界**：周期对账只重建缺失键；差异键需要运营显式重建。管理员改库存遇到在途变更会拒绝，需要稍后重试。

## 5. 退款审批与结算分离

**风险**：运营点击同意后，支付或库存服务暂时不可用；如果整个审批回滚，运营会重复点击，退款状态和外部账目对不上。

**做法**：
- 申请时在父订单行锁内重新读取明细并计算退款额，避免与上一笔审批并发时使用过期快照。
- 审批事务只改订单、明细和退款记录，把 `settled` 置为 0。
- `settle()` 再调用支付退款、库存回补或秒杀库存归还，成功后标记 `settled=1`。
- `RefundSettlementJob` 扫描已同意但未结算的记录并重试。
- 整单退款才返还优惠券；部分退款重算剩余商品优惠分摊，已退款商品优惠置零，明细优惠之和始终等于父订单优惠。
- 退款明细金额按实际退款额分摊，整单退款含运费时不会继续沿用商品原价。
- 审批后结算失败只记录日志并保留 `settled=0`，由任务重试；重复审批直接返回当前结果。

**代码**：`aurora-order/.../service/RefundService.java`、`TradePricingService.allocateRefundAmounts()`、`mq/RefundSettlementJob.java`。

**验证**：`TradeServiceIntegrationTest` 覆盖部分退款、整单退款返券、结算失败重试、拒绝后恢复发货、跨订单商品拒绝；浏览器完成部分退款审批并核对支付单 `PARTIALLY_REFUNDED`。

**边界**：退款金额按商品原价，不退运费；退款后剩余商品低于券门槛时只允许整单退款。

## 6. 秒杀异步落单

**风险**：预扣成功但落单失败，名额没有归还；消息重投又生成第二张订单。

**做法**：
- `seckill_reserve.lua` 原子完成时间窗、一人一单、扣库存和写 `PENDING`。
- 消费者先用 `seckill_claim.lua` 把结果改为 `PROCESSING`；只有 `PENDING/PROCESSING` 允许补偿。
- 补偿依赖已购标记的 `SREM` 返回值，重复补偿不会再次增加库存。
- 落单统一调用订单服务，秒杀不维护第二套订单表。
- 关单或整单退款通过 `seckill_release` 按父订单幂等归还活动库存；Redis 活动余量用订单级守卫同步，失败时抛出让关单/结算重试。

**代码**：`aurora-seckill/.../service/SeckillOrderService.java`、`lua/seckill_{reserve,claim,reset_pending,compensate,release_stock}.lua`、`SeckillReconcileJob.java`。

**验证**：`SeckillOrderServiceIntegrationTest`（6 例，真实 MySQL + Redis）覆盖一人一单、售罄、未开始、退款归还、Redis 库存修复和 5 人抢 2 件只成功 2 人；浏览器完成创建、预热、抢购、支付、整单退款和审批。

**边界**：对账按活动扫描，单机规模；发送结果未知时用户会停留在排队态，直到对账收敛。

## 7. 运营台数据与前端真实性

**风险**：运营首页用写死数字，页面看起来完整但无法反映数据库。

**做法**：
- 概览的待发货、待审批退款、近 30 天订单和销售额都来自订单库 SQL。
- 销售额口径是已支付订单的 `payable_amount - refunded_amount`。
- 商品、库存、券、秒杀、用户都接真实接口；操作后重新拉取列表。
- 商城不展示虚构评价、评分和销量；支付与物流页面标注为演示流程。

**代码**：`TradeService.adminStats()`、`TradeOrderMapper.countByStatus()/paidRevenueSince()`；`aurora-web/src/views/admin/AdminDashboardView.vue`。

**验证**：浏览器登录运营台，核对概览数字与数据库查询；订单筛选、退款审批、发货、商品编辑、库存调整、券创建、秒杀创建与预热均实际操作过。

**边界**：销售额只统计当前订单表，不包含已删除或归档订单。

## 8. 登录会话撤销

**风险**：登出只拉黑当前 access token 时，同一账号其他会话的 token 仍可用到过期。

**做法**：登出写 `session-invalid-before` 时间戳；网关在 JWT 校验后同时检查该时间戳，`iat` 更早的 access token 直接拒绝。refresh 轮换用 SETNX 原子认领，旧 refresh 只能成功一次。

**验证**：`GatewayJwtFilterTest` 覆盖登出前签发的 token 被拒绝；浏览器/接口实测登出后旧 access token 返回 401。

## 9. 测试与已知边界

- `mvn -B clean verify` 覆盖后端全模块；真实 MySQL/Redis 用例不跳过。
- `npm run typecheck && npm run build` 覆盖前端类型与生产构建。
- `npm run smoke:ui` 覆盖 93 个页面/视口组合，检查 1440/1280/390/320 的横向溢出、控制台错误、失败请求、图片加载与关键只读交互；完整交易闭环在验收记录中由 Playwright 实际操作。

仍然存在的边界：

- 支付、物流是模拟流程；商品图是概念渲染。
- 只支持发货前退款；退款审批按订单串行。
- 商品筛选在 Java 侧完成，适合演示规模。
- 秒杀对账、布隆过滤器按单实例设计。
- 幂等记录与 `tx_message` 没有归档任务。
- `docs/m2`–`docs/m5` 的压测数据来自重构前环境，不能直接当作当前容量。
