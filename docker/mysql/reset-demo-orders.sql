-- 只重置演示订单（3001–3037）及其退款、支付与明细；真实下单产生的订单不动。
SET NAMES utf8mb4;
USE aurora_order;

DELETE ri FROM aurora_order.order_refund_item ri
JOIN aurora_order.order_refund r ON r.id = ri.refund_id
WHERE r.trade_id BETWEEN 3001 AND 3037;
DELETE FROM aurora_order.order_refund WHERE trade_id BETWEEN 3001 AND 3037;
DELETE FROM aurora_payment.payment_refunds WHERE trade_id BETWEEN 3001 AND 3037;
DELETE FROM aurora_payment.payment_orders WHERE trade_id BETWEEN 3001 AND 3037;
DELETE FROM aurora_order.tx_message WHERE biz_key BETWEEN '3001' AND '3037';
DELETE FROM aurora_order.order_idempotency WHERE trade_id BETWEEN 3001 AND 3037;
DELETE FROM aurora_order.trade_order_item WHERE trade_id BETWEEN 3001 AND 3037;
DELETE FROM aurora_order.trade_order WHERE id BETWEEN 3001 AND 3037;
