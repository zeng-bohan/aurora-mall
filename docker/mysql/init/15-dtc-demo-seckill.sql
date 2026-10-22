-- 秒杀演示活动：一场进行中、一场即将开始。
-- 固定 id + INSERT IGNORE，重复执行不产生新活动；库存行同样幂等。
-- Redis 预热需要运行时执行（运营台“预热”按钮或内部接口）。
SET NAMES utf8mb4;
USE aurora_seckill;

INSERT IGNORE INTO seckill_activity (
    id, title, spu_id, sku_id, product_title, product_image, original_price,
    seckill_price, total_stock, per_user_limit, start_at, end_at)
SELECT 9930002, 'Type 75 键盘 · 周末闪购', k.spu_id, k.id, k.title, k.image_url, k.price,
       599.00, 8, 1,
       DATE_SUB(NOW(), INTERVAL 6 HOUR), DATE_ADD(NOW(), INTERVAL 18 HOUR)
FROM aurora_product.product_sku k
WHERE k.id = 14;

INSERT IGNORE INTO seckill_stock (activity_id, total, available)
SELECT 9930002, 8, 8
WHERE EXISTS (SELECT 1 FROM seckill_activity WHERE id = 9930002);

INSERT IGNORE INTO seckill_activity (
    id, title, spu_id, sku_id, product_title, product_image, original_price,
    seckill_price, total_stock, per_user_limit, start_at, end_at)
SELECT 9930001, 'Arc 65W 闪充套装 · 明日开抢', k.spu_id, k.id, k.title, k.image_url, k.price,
       129.00, 30, 1,
       DATE_ADD(NOW(), INTERVAL 18 HOUR), DATE_ADD(NOW(), INTERVAL 42 HOUR)
FROM aurora_product.product_sku k
WHERE k.id = 1;

INSERT IGNORE INTO seckill_stock (activity_id, total, available)
SELECT 9930001, 30, 30
WHERE EXISTS (SELECT 1 FROM seckill_activity WHERE id = 9930001);
