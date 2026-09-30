-- orders 补偿扫描（findUnreleasedClosed: status=2 AND stock_released=0）的覆盖索引：
-- 现有 idx_orders_status_created 前导列 status 后第二列是 created_at，无法服务
-- stock_released 过滤，表大后补偿扫描会退化为全表扫。
-- 注意：已有数据卷需手工执行一次：
--   docker exec -i aurora-mysql mysql -uroot -paurora123 < docker/mysql/init/10-order-release-index.sql
USE aurora_order;
ALTER TABLE orders ADD KEY idx_orders_release (status, stock_released);
