-- order-paid 补发失败的有界重试：永久性失败（订单不存在/金额不符等）不能
-- 每 60s 无限扫描重发。publish_attempts 计数；达到上限或判定永久失败时置
-- event_failed=1，补发扫描跳过，等待人工介入。
-- 注意：已有数据卷需手工执行一次：
--   docker exec -i aurora-mysql mysql -uroot -paurora123 < docker/mysql/init/11-payment-event-retry.sql
USE aurora_payment;
ALTER TABLE payment_orders
    ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0 COMMENT '补发尝试次数',
    ADD COLUMN event_failed TINYINT NOT NULL DEFAULT 0 COMMENT '1=放弃自动补发，待人工处理';
ALTER TABLE payment_orders DROP KEY idx_payment_event,
    ADD KEY idx_payment_event (event_failed, status, event_published, created_at);
