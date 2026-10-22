-- 演示数据一致性检查：只读，可重复执行。
-- 用法：mysql ... < docker/mysql/verify-demo.sql
SET NAMES utf8mb4;

SELECT 'orders' AS metric, COUNT(*) AS value FROM aurora_order.trade_order
WHERE id BETWEEN 3001 AND 3037
UNION ALL
SELECT 'items', COUNT(*) FROM aurora_order.trade_order_item
WHERE trade_id BETWEEN 3001 AND 3037
UNION ALL
SELECT 'refunds', COUNT(*) FROM aurora_order.order_refund
WHERE trade_id BETWEEN 3001 AND 3037;

SELECT 'order amount mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order o
JOIN (
    SELECT trade_id, SUM(item_amount) AS merchandise
    FROM aurora_order.trade_order_item
    WHERE trade_id BETWEEN 3001 AND 3037
    GROUP BY trade_id
) i ON i.trade_id = o.id
WHERE o.merchandise_amount <> i.merchandise
   OR o.payable_amount <> o.merchandise_amount - o.discount_amount + o.shipping_fee
   OR o.shipping_fee <> IF(o.merchandise_amount - o.discount_amount >= 299, 0, 12);

SELECT 'refund amount mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order o
LEFT JOIN (
    SELECT trade_id, SUM(amount) AS approved
    FROM aurora_order.order_refund
    WHERE status = 'APPROVED' AND trade_id BETWEEN 3001 AND 3037
    GROUP BY trade_id
) r ON r.trade_id = o.id
WHERE o.id BETWEEN 3001 AND 3037
  AND o.refunded_amount <> COALESCE(r.approved, 0);

SELECT 'payment mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order o
JOIN aurora_payment.payment_orders p ON p.trade_id = o.id
WHERE o.id BETWEEN 3001 AND 3037
  AND (
      p.amount <> o.payable_amount
      OR p.refunded_amount <> o.refunded_amount
      OR (o.status = 'REFUNDED' AND p.status <> 'REFUNDED')
      OR (o.refund_status = 'PARTIAL' AND p.status <> 'PARTIALLY_REFUNDED')
  );

SELECT 'full refund state mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order o
WHERE o.id BETWEEN 3001 AND 3037
  AND o.refund_status = 'FULL'
  AND (o.status <> 'REFUNDED' OR o.refunded_amount <> o.payable_amount);

SELECT status, COUNT(*) AS orders, SUM(payable_amount) AS payable
FROM aurora_order.trade_order
WHERE id BETWEEN 3001 AND 3037
GROUP BY status
ORDER BY status;
