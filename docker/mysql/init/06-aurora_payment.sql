CREATE DATABASE IF NOT EXISTS aurora_payment DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_payment;

CREATE TABLE IF NOT EXISTS payment_orders (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trade_id          BIGINT        NOT NULL,
    trade_no          VARCHAR(40)   NOT NULL,
    user_id           BIGINT        NOT NULL,
    amount            DECIMAL(12,2) NOT NULL,
    refunded_amount   DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status            VARCHAR(24)   NOT NULL DEFAULT 'PAYING' COMMENT 'PAYING / PAID / PARTIALLY_REFUNDED / REFUNDED',
    channel           VARCHAR(24)   NOT NULL DEFAULT 'MOCK',
    paid_at           DATETIME      NULL,
    event_published   TINYINT       NOT NULL DEFAULT 0,
    publish_attempts  INT           NOT NULL DEFAULT 0,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_trade (trade_id),
    KEY idx_payment_status (status, created_at)
) ENGINE = InnoDB;

-- 一次退款申请一条记录；refund_id 作为幂等键，重复回调不会二次累加。
CREATE TABLE IF NOT EXISTS payment_refunds (
    refund_id  BIGINT        NOT NULL PRIMARY KEY,
    trade_id   BIGINT        NOT NULL,
    amount     DECIMAL(12,2) NOT NULL,
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_payment_refund_trade (trade_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS idempotent_record (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_type   VARCHAR(32)  NOT NULL,
    biz_key    VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotent (biz_type, biz_key),
    KEY idx_idempotent_created (created_at)
) ENGINE = InnoDB;
