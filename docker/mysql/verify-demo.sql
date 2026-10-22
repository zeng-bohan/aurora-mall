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


SELECT 'demo order ids collide with id generator' AS metric, COUNT(*) AS value
FROM aurora_id.leaf_alloc
WHERE biz_tag = 'order' AND max_id < 3037;

SELECT 'confirmed demo items without reservation' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order_item i
JOIN aurora_order.trade_order o ON o.id = i.trade_id
LEFT JOIN aurora_inventory.stock_reservation r
       ON r.trade_id = i.trade_id AND r.sku_id = i.sku_id
WHERE o.id BETWEEN 3001 AND 3037
  AND o.status IN ('PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED')
  AND r.trade_id IS NULL;

SELECT 'approved demo refunds without stock restore' AS metric, COUNT(*) AS value
FROM aurora_order.order_refund r
LEFT JOIN aurora_inventory.stock_restore s ON s.refund_id = r.id
WHERE r.trade_id BETWEEN 3001 AND 3037
  AND r.status = 'APPROVED' AND s.refund_id IS NULL;

SELECT 'refund item sum mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.order_refund r
JOIN (
    SELECT refund_id, SUM(amount) AS item_sum
    FROM aurora_order.order_refund_item
    GROUP BY refund_id
) i ON i.refund_id = r.id
WHERE r.trade_id BETWEEN 3001 AND 3037
  AND r.status = 'APPROVED' AND i.item_sum <> r.amount;

SELECT 'item refunded sum mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order o
JOIN (
    SELECT trade_id, SUM(refunded_amount) AS item_refunded
    FROM aurora_order.trade_order_item
    WHERE trade_id BETWEEN 3001 AND 3037
    GROUP BY trade_id
) i ON i.trade_id = o.id
WHERE o.id BETWEEN 3001 AND 3037
  AND i.item_refunded <> o.refunded_amount;

SELECT 'item discount sum mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order o
JOIN (
    SELECT trade_id, SUM(discount_amount) AS item_discount
    FROM aurora_order.trade_order_item
    WHERE trade_id BETWEEN 3001 AND 3037
    GROUP BY trade_id
) i ON i.trade_id = o.id
WHERE o.id BETWEEN 3001 AND 3037
  AND i.item_discount <> o.discount_amount;

SELECT 'payment refund ledger mismatches' AS metric, COUNT(*) AS value
FROM aurora_payment.payment_orders p
LEFT JOIN (
    SELECT trade_id, SUM(amount) AS ledger
    FROM aurora_payment.payment_refunds
    WHERE trade_id BETWEEN 3001 AND 3037
    GROUP BY trade_id
) r ON r.trade_id = p.trade_id
WHERE p.trade_id BETWEEN 3001 AND 3037
  AND p.refunded_amount <> COALESCE(r.ledger, 0)
  -- 迟到支付由支付渠道原路退回，不经过订单退款单，因此没有 payment_refunds 行。
  AND NOT (p.status = 'REFUNDED' AND EXISTS (
      SELECT 1 FROM aurora_order.trade_order o
      WHERE o.id = p.trade_id AND o.status = 'CLOSED'));

SELECT 'pending refund item state mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.order_refund r
JOIN aurora_order.order_refund_item ri ON ri.refund_id = r.id
JOIN aurora_order.trade_order_item i ON i.id = ri.order_item_id
WHERE r.trade_id BETWEEN 3001 AND 3037
  AND r.status = 'PENDING' AND i.status <> 'REFUNDING';

SELECT 'approved refund item state mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.order_refund r
JOIN aurora_order.order_refund_item ri ON ri.refund_id = r.id
JOIN aurora_order.trade_order_item i ON i.id = ri.order_item_id
WHERE r.trade_id BETWEEN 3001 AND 3037
  AND r.status = 'APPROVED' AND i.status <> 'REFUNDED';

SELECT 'full refund item state mismatches' AS metric, COUNT(*) AS value
FROM aurora_order.trade_order o
WHERE o.id BETWEEN 3001 AND 3037
  AND o.status = 'REFUNDED'
  AND EXISTS (SELECT 1 FROM aurora_order.trade_order_item i
              WHERE i.trade_id = o.id AND i.status <> 'REFUNDED');

SELECT 'catalog count mismatches' AS metric,
       ABS((SELECT COUNT(*) FROM aurora_product.product_spu) - 18)
     + ABS((SELECT COUNT(*) FROM aurora_product.product_sku) - 45)
     + ABS((SELECT COUNT(*) FROM aurora_product.product_category) - 7)
     + ABS((SELECT COUNT(*) FROM aurora_product.product_attribute) - 15)
     + ABS((SELECT COUNT(*) FROM aurora_product.product_attribute_value) - 53) AS value;

SELECT 'demo seckill activities missing' AS metric, 3 - COUNT(*) AS value
FROM aurora_seckill.seckill_activity WHERE id IN (9930001, 9930002, 9930003);

SELECT 'demo seckill stock out of range' AS metric, COUNT(*) AS value
FROM aurora_seckill.seckill_stock
WHERE activity_id IN (9930001, 9930002, 9930003)
  AND (available < 0 OR available > total);

SELECT status, COUNT(*) AS orders, SUM(payable_amount) AS payable
FROM aurora_order.trade_order
WHERE id BETWEEN 3001 AND 3037
GROUP BY status
ORDER BY status;
