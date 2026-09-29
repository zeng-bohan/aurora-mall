CREATE DATABASE IF NOT EXISTS aurora_payment DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_payment;

CREATE TABLE IF NOT EXISTS payment_orders (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT        NOT NULL,
    amount     DECIMAL(10,2) NOT NULL,
    status     TINYINT       NOT NULL DEFAULT 0 COMMENT '0=PAYING 1=PAID',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_order (order_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS idempotent_record (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_type   VARCHAR(32)  NOT NULL,
    biz_key    VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotent (biz_type, biz_key)
) ENGINE = InnoDB;
