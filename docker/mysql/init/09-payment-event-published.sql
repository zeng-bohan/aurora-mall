-- payment 侧 order-paid 事件发布标记（T14）：markPaid 成功与事件发出之间
-- 崩溃时，由 PaymentEventRetryJob 按 event_published=0 补发。
-- 注意：已有数据卷需手工执行一次：
--   docker exec -i aurora-mysql mysql -uroot -paurora123 < docker/mysql/init/09-payment-event-published.sql
USE aurora_payment;
ALTER TABLE payment_orders
    ADD COLUMN event_published TINYINT NOT NULL DEFAULT 0 COMMENT '1=order-paid 事件已发布';
ALTER TABLE payment_orders ADD KEY idx_payment_event (status, event_published);
