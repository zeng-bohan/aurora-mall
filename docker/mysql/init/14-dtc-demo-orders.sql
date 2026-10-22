-- 演示交易数据：37 笔父订单，覆盖待支付、已关闭、待发货、已发货、
-- 已完成、部分退款、整单退款，以及退款待审批 / 已同意 / 已拒绝。
-- 金额由商品 SKU 单价推导，免运费门槛与线上规则一致（券后满 299 免运费）。
-- 全脚本幂等：固定订单 id / 明细 id / 退款 id + INSERT IGNORE，重复执行不会产生第二份数据。
SET NAMES utf8mb4;
USE aurora_order;

CREATE TEMPORARY TABLE demo_order_spec (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL,
    refund_status   VARCHAR(16) NOT NULL,
    refunded_amount DECIMAL(12,2) NOT NULL,
    days_ago        INT         NOT NULL,
    carrier         VARCHAR(32) NULL,
    tracking_no     VARCHAR(64) NULL
);

INSERT INTO demo_order_spec VALUES
    (3001, 2, 'CREATED',   'NONE',    0.00,  0, NULL, NULL),
    (3002, 3, 'CREATED',   'NONE',    0.00,  1, NULL, NULL),
    (3003, 4, 'CREATED',   'NONE',    0.00,  2, NULL, NULL),
    (3004, 5, 'CREATED',   'NONE',    0.00,  3, NULL, NULL),
    (3005, 6, 'CREATED',   'NONE',    0.00,  4, NULL, NULL),
    (3006, 2, 'CLOSED',    'NONE',    0.00,  4, NULL, NULL),
    (3007, 3, 'CLOSED',    'NONE',    0.00,  5, NULL, NULL),
    (3008, 7, 'CLOSED',    'NONE',    0.00,  6, NULL, NULL),
    (3009, 4, 'PAID',      'NONE',    0.00,  5, NULL, NULL),
    (3010, 5, 'PAID',      'NONE',    0.00,  6, NULL, NULL),
    (3011, 6, 'PAID',      'NONE',    0.00,  7, NULL, NULL),
    (3012, 7, 'PAID',      'NONE',    0.00,  8, NULL, NULL),
    (3013, 2, 'PAID',      'NONE',    0.00,  9, NULL, NULL),
    (3014, 3, 'PAID',      'NONE',    0.00, 10, NULL, NULL),
    (3015, 4, 'PAID',      'NONE',    0.00, 11, NULL, NULL),
    (3016, 5, 'PAID',      'NONE',    0.00, 12, NULL, NULL),
    (3017, 6, 'SHIPPED',   'NONE',    0.00,  8, '顺丰速运', 'SF202610160017'),
    (3018, 7, 'SHIPPED',   'NONE',    0.00,  9, '京东物流', 'JD202610150018'),
    (3019, 2, 'SHIPPED',   'NONE',    0.00, 10, 'AURORA Express', 'AU202610140019'),
    (3020, 3, 'SHIPPED',   'NONE',    0.00, 11, '顺丰速运', 'SF202610130020'),
    (3021, 4, 'SHIPPED',   'NONE',    0.00, 12, '京东物流', 'JD202610120021'),
    (3022, 5, 'SHIPPED',   'NONE',    0.00, 13, '顺丰速运', 'SF202610110022'),
    (3023, 6, 'SHIPPED',   'NONE',    0.00, 14, 'AURORA Express', 'AU202610100023'),
    (3024, 7, 'SHIPPED',   'NONE',    0.00, 15, '京东物流', 'JD202610090024'),
    (3025, 2, 'COMPLETED', 'NONE',    0.00, 16, '顺丰速运', 'SF202610080025'),
    (3026, 3, 'COMPLETED', 'NONE',    0.00, 17, '京东物流', 'JD202610070026'),
    (3027, 4, 'COMPLETED', 'NONE',    0.00, 18, '顺丰速运', 'SF202610060027'),
    (3028, 5, 'COMPLETED', 'NONE',    0.00, 19, '京东物流', 'JD202610050028'),
    (3029, 6, 'COMPLETED', 'NONE',    0.00, 20, '顺丰速运', 'SF202610040029'),
    (3030, 7, 'COMPLETED', 'NONE',    0.00, 21, 'AURORA Express', 'AU202610030030'),
    (3031, 2, 'REFUNDED',  'FULL',  1099.00,  2, NULL, NULL),
    (3032, 3, 'REFUNDED',  'FULL',   599.00,  3, NULL, NULL),
    (3033, 4, 'PAID',      'PARTIAL', 269.00,  4, NULL, NULL),
    (3034, 5, 'PAID',      'NONE',    0.00,  1, NULL, NULL),
    (3035, 6, 'PAID',      'NONE',    0.00,  5, NULL, NULL),
    (3036, 7, 'REFUNDED',  'FULL',   678.00,  6, NULL, NULL),
    (3037, 2, 'PAID',      'PARTIAL', 459.00,  7, NULL, NULL);

CREATE TEMPORARY TABLE demo_item_spec (
    id              BIGINT PRIMARY KEY,
    trade_id        BIGINT NOT NULL,
    sku_id          BIGINT NOT NULL,
    quantity        INT    NOT NULL,
    status          VARCHAR(16) NOT NULL,
    refunded_amount DECIMAL(12,2) NOT NULL
);

INSERT INTO demo_item_spec VALUES
    (30011, 3001,  7, 1, 'NORMAL', 0),
    (30021, 3002, 23, 2, 'NORMAL', 0),
    (30031, 3003, 15, 1, 'NORMAL', 0),
    (30041, 3004, 41, 1, 'NORMAL', 0),
    (30051, 3005,  3, 1, 'NORMAL', 0),
    (30061, 3006, 11, 1, 'NORMAL', 0),
    (30071, 3007, 35, 1, 'NORMAL', 0),
    (30081, 3008, 24, 1, 'NORMAL', 0),
    (30091, 3009, 28, 1, 'NORMAL', 0),
    (30101, 3010, 44, 1, 'REFUNDING', 0),
    (30102, 3010,  7, 1, 'NORMAL', 0),
    (30111, 3011, 26, 1, 'REFUNDING', 0),
    (30112, 3011, 35, 1, 'NORMAL', 0),
    (30121, 3012, 31, 1, 'NORMAL', 0),
    (30131, 3013,  1, 1, 'NORMAL', 0),
    (30132, 3013,  7, 1, 'NORMAL', 0),
    (30141, 3014, 42, 1, 'NORMAL', 0),
    (30151, 3015, 29, 1, 'NORMAL', 0),
    (30161, 3016, 17, 1, 'NORMAL', 0),
    (30162, 3016,  8, 2, 'NORMAL', 0),
    (30171, 3017,  5, 1, 'NORMAL', 0),
    (30181, 3018, 13, 1, 'NORMAL', 0),
    (30182, 3018, 23, 1, 'NORMAL', 0),
    (30191, 3019, 21, 1, 'NORMAL', 0),
    (30201, 3020, 30, 1, 'NORMAL', 0),
    (30211, 3021, 38, 2, 'NORMAL', 0),
    (30212, 3021, 35, 1, 'NORMAL', 0),
    (30221, 3022, 14, 1, 'NORMAL', 0),
    (30231, 3023, 34, 1, 'NORMAL', 0),
    (30232, 3023,  9, 1, 'NORMAL', 0),
    (30241, 3024, 19, 1, 'NORMAL', 0),
    (30242, 3024, 10, 1, 'NORMAL', 0),
    (30251, 3025, 28, 1, 'NORMAL', 0),
    (30261, 3026, 32, 1, 'NORMAL', 0),
    (30271, 3027,  6, 1, 'NORMAL', 0),
    (30272, 3027, 36, 1, 'NORMAL', 0),
    (30281, 3028, 25, 2, 'NORMAL', 0),
    (30282, 3028, 40, 1, 'NORMAL', 0),
    (30291, 3029, 43, 1, 'NORMAL', 0),
    (30301, 3030,  2, 1, 'NORMAL', 0),
    (30302, 3030,  7, 1, 'NORMAL', 0),
    (30311, 3031, 30, 1, 'REFUNDED', 1099.00),
    (30321, 3032, 18, 1, 'REFUNDED', 599.00),
    (30331, 3033, 20, 1, 'REFUNDED', 269.00),
    (30332, 3033, 36, 1, 'NORMAL', 0),
    (30341, 3034, 33, 1, 'REFUNDING', 0),
    (30342, 3034, 37, 1, 'REFUNDING', 0),
    (30351, 3035,  4, 1, 'NORMAL', 0),
    (30352, 3035,  8, 2, 'NORMAL', 0),
    (30361, 3036, 27, 1, 'REFUNDED', 499.00),
    (30362, 3036, 39, 1, 'REFUNDED', 179.00),
    (30371, 3037, 45, 1, 'REFUNDED', 459.00),
    (30372, 3037, 10, 1, 'NORMAL', 0);

CREATE TEMPORARY TABLE demo_totals AS
SELECT s.id AS trade_id, SUM(k.price * i.quantity) AS merchandise
FROM demo_order_spec s
JOIN demo_item_spec i ON i.trade_id = s.id
JOIN aurora_product.product_sku k ON k.id = i.sku_id
GROUP BY s.id;

INSERT IGNORE INTO trade_order (
    id, trade_no, user_id, source, status, refund_status, merchandise_amount,
    discount_amount, shipping_fee, payable_amount, refunded_amount,
    receiver_name, receiver_phone, province, city, district, address_detail, postal_code,
    carrier, tracking_no, stock_confirmed, stock_released,
    paid_at, shipped_at, completed_at, closed_at, version, created_at, updated_at)
SELECT s.id, CONCAT('A', s.id), s.user_id, 'MALL', s.status, s.refund_status,
       t.merchandise, 0.00,
       IF(t.merchandise >= 299.00, 0.00, 12.00),
       t.merchandise + IF(t.merchandise >= 299.00, 0.00, 12.00),
       s.refunded_amount,
       a.receiver_name, a.phone, a.province, a.city, a.district, a.detail, a.postal_code,
       s.carrier, s.tracking_no,
       IF(s.status IN ('PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED'), 1, 0),
       IF(s.status = 'CLOSED', 1, 0),
       IF(s.status IN ('PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED'),
          DATE_ADD(DATE_SUB(NOW(), INTERVAL s.days_ago DAY), INTERVAL 15 MINUTE), NULL),
       IF(s.status = 'SHIPPED',
          TIMESTAMPADD(DAY, -(1 + MOD(s.id, 4)), NOW()),
          IF(s.status = 'COMPLETED',
             TIMESTAMPADD(DAY, -(4 + MOD(s.id, 4)), NOW()), NULL)),
       IF(s.status = 'COMPLETED',
          TIMESTAMPADD(DAY, -(3 + MOD(s.id, 4)), NOW()), NULL),
       IF(s.status = 'CLOSED',
          DATE_ADD(DATE_SUB(NOW(), INTERVAL s.days_ago DAY), INTERVAL 30 MINUTE), NULL),
       0,
       IF(s.status = 'CREATED',
          TIMESTAMPADD(MINUTE, -(10 + MOD(s.id, 10)), NOW()),
          DATE_SUB(NOW(), INTERVAL s.days_ago DAY)),
       IF(s.status = 'CREATED',
          TIMESTAMPADD(MINUTE, -(10 + MOD(s.id, 10)), NOW()),
          DATE_SUB(NOW(), INTERVAL s.days_ago DAY))
FROM demo_order_spec s
JOIN demo_totals t ON t.trade_id = s.id
JOIN aurora_user.user_address a ON a.user_id = s.user_id AND a.is_default = 1;

INSERT IGNORE INTO trade_order_item (
    id, trade_id, spu_id, sku_id, spu_title, sku_code, attributes_text, image_url,
    unit_price, quantity, item_amount, discount_amount, refunded_amount, status)
SELECT i.id, i.trade_id, k.spu_id, k.id, p.title, k.sku_code,
       (SELECT GROUP_CONCAT(av.value ORDER BY pa.sort SEPARATOR ' / ')
        FROM aurora_product.product_sku_attribute psa
        JOIN aurora_product.product_attribute_value av ON av.id = psa.attribute_value_id
        JOIN aurora_product.product_attribute pa ON pa.id = av.attribute_id
        WHERE psa.sku_id = k.id),
       k.image_url, k.price, i.quantity, k.price * i.quantity, 0.00,
       i.refunded_amount, i.status
FROM demo_item_spec i
JOIN aurora_product.product_sku k ON k.id = i.sku_id
JOIN aurora_product.product_spu p ON p.id = k.spu_id
WHERE NOT EXISTS (SELECT 1 FROM trade_order_item x WHERE x.id = i.id);

CREATE TEMPORARY TABLE demo_refund_spec (
    id            BIGINT PRIMARY KEY,
    trade_id      BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    amount        DECIMAL(12,2) NOT NULL,
    reason        VARCHAR(200) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    settled       TINYINT NOT NULL,
    reject_reason VARCHAR(200) NULL,
    reviewer      VARCHAR(64) NULL,
    days_ago      INT NOT NULL
);

INSERT INTO demo_refund_spec VALUES
    (4001, 3010, 5,  459.00, '键盘尺寸不合适，未拆封', 'PENDING',  0, NULL, NULL, 1),
    (4002, 3011, 6,  499.00, '耳机夹头，想换成头戴式', 'PENDING',  0, NULL, NULL, 1),
    (4003, 3034, 5,  628.00, '临时改变主意，整单退',   'PENDING',  0, NULL, NULL, 0),
    (4004, 3013, 2,  199.00, '误购，买错颜色',         'REJECTED', 0, '商品已拆封，不支持无理由退款', 'AURORA 运营', 8),
    (4005, 3033, 4,  269.00, '颜色与预期不同',         'APPROVED', 1, NULL, 'AURORA 运营', 3),
    (4006, 3036, 7,  678.00, '整套退货',               'APPROVED', 1, NULL, 'AURORA 运营', 5),
    (4007, 3031, 2, 1099.00, '音箱左声道无声',         'APPROVED', 1, NULL, 'AURORA 运营', 1),
    (4008, 3032, 3,  599.00, '七天无理由退货',         'APPROVED', 1, NULL, 'AURORA 运营', 2),
    (4009, 3037, 2,  459.00, '重复购买，退一套',       'APPROVED', 1, NULL, 'AURORA 运营', 6);

INSERT IGNORE INTO order_refund (
    id, refund_no, trade_id, user_id, amount, reason, status, settled,
    reject_reason, reviewer, created_at, reviewed_at)
SELECT r.id, CONCAT('R', r.trade_id, '-', LPAD(r.id - 4000, 4, '0')),
       r.trade_id, r.user_id, r.amount, r.reason, r.status, r.settled,
       r.reject_reason, r.reviewer,
       DATE_SUB(NOW(), INTERVAL r.days_ago DAY),
       IF(r.status = 'PENDING', NULL,
          DATE_ADD(DATE_SUB(NOW(), INTERVAL r.days_ago DAY), INTERVAL 12 HOUR))
FROM demo_refund_spec r;

INSERT IGNORE INTO order_refund_item (refund_id, order_item_id, sku_id, quantity, amount)
SELECT 4001, 30101, 44, 1, 459.00 UNION ALL
SELECT 4002, 30111, 26, 1, 499.00 UNION ALL
SELECT 4003, 30341, 33, 1, 499.00 UNION ALL
SELECT 4003, 30342, 37, 1, 129.00 UNION ALL
SELECT 4004, 30131,  1, 1, 199.00 UNION ALL
SELECT 4005, 30331, 20, 1, 269.00 UNION ALL
SELECT 4006, 30361, 27, 1, 499.00 UNION ALL
SELECT 4006, 30362, 39, 1, 179.00 UNION ALL
SELECT 4007, 30311, 30, 1, 1099.00 UNION ALL
SELECT 4008, 30321, 18, 1, 599.00 UNION ALL
SELECT 4009, 30371, 45, 1, 459.00;

-- 支付单跟随订单状态：待支付 / 已关闭仍是 PAYING，退款后进入部分或整单退款。
INSERT IGNORE INTO aurora_payment.payment_orders (
    trade_id, trade_no, user_id, amount, refunded_amount, status, channel,
    paid_at, event_published, publish_attempts, created_at, updated_at)
SELECT s.id, CONCAT('A', s.id), s.user_id,
       t.merchandise + IF(t.merchandise >= 299.00, 0.00, 12.00),
       s.refunded_amount,
       CASE
           WHEN s.status IN ('CREATED', 'CLOSED') THEN 'PAYING'
           WHEN s.status = 'REFUNDED' THEN 'REFUNDED'
           WHEN s.refunded_amount > 0 THEN 'PARTIALLY_REFUNDED'
           ELSE 'PAID'
       END,
       'MOCK',
       IF(s.status IN ('PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED'),
          DATE_ADD(DATE_SUB(NOW(), INTERVAL s.days_ago DAY), INTERVAL 15 MINUTE), NULL),
       IF(s.status IN ('PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED'), 1, 0),
       0,
       IF(s.status = 'CREATED',
          TIMESTAMPADD(MINUTE, -(10 + MOD(s.id, 10)), NOW()),
          DATE_SUB(NOW(), INTERVAL s.days_ago DAY)),
       IF(s.status = 'CREATED',
          TIMESTAMPADD(MINUTE, -(10 + MOD(s.id, 10)), NOW()),
          DATE_SUB(NOW(), INTERVAL s.days_ago DAY))
FROM demo_order_spec s
JOIN demo_totals t ON t.trade_id = s.id;

INSERT IGNORE INTO aurora_payment.payment_refunds (refund_id, trade_id, amount, created_at)
SELECT r.id, r.trade_id, r.amount,
       DATE_ADD(DATE_SUB(NOW(), INTERVAL r.days_ago DAY), INTERVAL 12 HOUR)
FROM demo_refund_spec r
WHERE r.status = 'APPROVED';

DROP TEMPORARY TABLE demo_refund_spec;
DROP TEMPORARY TABLE demo_totals;
DROP TEMPORARY TABLE demo_item_spec;
DROP TEMPORARY TABLE demo_order_spec;
