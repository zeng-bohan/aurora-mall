# 面试导览

按问题组织的代码路径与验证证据。适合在面试中挑一条深入，不要背结论。

## 1. 多商品结算为什么用父订单？

一次结算生成一张 `trade_order`，商品明细放 `trade_order_item`。支付、发货、地址快照、优惠券和退款都挂在父订单上。这样一次支付只对应一次发货，退款也能按明细选择商品。

金额计算集中在 `TradePricingService`：整数分、最大余数法分摊、券后满 299 免运费、部分退款按商品原价且不退运费、剩余低于券门槛只允许整单。

入口：`aurora-order/.../service/TradeService.place()`。
证据：`TradePricingServiceTest`、`TradeServiceIntegrationTest`、浏览器多商品结算流程。

## 2. 下单幂等怎么保证不会生成第二张订单？

`order_idempotency` 以 `(user_id, idem_key)` 为主键，保存 `trade_id` 和请求指纹。映射、订单、明细、券绑定、关单消息同一事务提交。同键同内容返回原订单，同键换内容拒绝。并发同键由唯一键裁决，输家回滚并释放已预扣库存。

入口：`TradeService.place()`、`insertIdempotency()`、`replay()`。
证据：`TradeServiceIntegrationTest` 的重复键与换参数两个用例。

## 3. 库存和秒杀怎么防超卖？

普通库存：Redis 批量 Lua 一次检查所有 SKU，任一不足整批失败；全部满足才扣减，并写父订单守卫。DB 用 `product_stock` 和 `stock_reservation` 记账，预扣、确认、释放都按父订单幂等，退款回补按退款单幂等。

秒杀：活动库存独立。`seckill_reserve.lua` 原子完成时间窗、一人一单、扣库存和写 `PENDING`。异步落单由 MQ 消费，结果状态为 `PENDING → PROCESSING → ORDER / FAIL`；补偿用已购标记的 `SREM` 返回值防重放。

入口：`StockService`、`SeckillOrderService`、`lua/stock_batch_reserve.lua`、`lua/seckill_reserve.lua`。
证据：`StockServiceIntegrationTest`（并发抢最后一件）、`SeckillOrderServiceIntegrationTest`（5 人抢 2 件）、浏览器秒杀全流程。

## 4. 支付成功和关单同时发生怎么办？

支付回调先校验签名和金额，再用 `WHERE status='PAYING'` 条件更新。更新后重新查订单：如果已关闭，登记迟到退款，不发布支付成功事件；如果订单仍待支付，发布支付事件并确认库存。关单侧也会在释放库存前重新查单，发现已支付就停止。

退款审批与外部结算分开：审批只改本地状态，支付退款和库存回补由 `settle()` 执行，失败由重试任务补偿。

入口：`PaymentService.handleMockCallback()`、`TradeService.markPaid()`、`RefundService.settle()`。
证据：`PaymentServiceTest` 的关单竞态用例、`TradeServiceIntegrationTest` 的迟到支付用例。

## 5. 全局 EAV 怎么落到代码？

分类、属性、属性值、SPU 属性绑定、SKU 属性绑定分别建表。属性带类型、单位、可筛选、变体、必填、展示和排序。SKU 保存 `combination_key` 并加唯一索引，保存 `sku_code` 并加唯一索引；保存时校验必填变体、重复组合和重复编码。

目录查询从分类、关键词、价格带和属性值过滤，再组装 SPU 卡片；库存通过库存服务批量查询，不可用时显示“库存待确认”，不写死数字。

入口：`ProductQueryService`、`ProductAdminService`、`ProductDtos`。
证据：浏览器目录筛选、运营台商品编辑与 SKU 组合校验。

## 6. 前端为什么不做评分和销量？

演示数据没有真实评价与销量。页面只展示能由数据库证明的信息：价格、库存、变体、配送与退款状态。支付和物流明确标注为演示流程。运营台的概览数字来自订单库 SQL，销售额口径是已支付订单的实付减已退。

入口：`AdminDashboardView.vue`、`TradeService.adminStats()`。
证据：概览数字与 SQL 对账；浏览器运营流程。

## 7. 这套实现还有哪些边界？

- 支付、物流是模拟流程，商品图是概念渲染。
- 只支持发货前退款，退款审批按订单串行。
- 商品筛选在 Java 侧完成，适合演示规模。
- 秒杀对账与布隆过滤器按单实例设计。
- `docs/m2`–`docs/m5` 的压测数据来自重构前环境，不能直接当作当前容量。

面试时先讲业务约束，再讲取舍。不要把演示规模说成生产规模。
