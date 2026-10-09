CREATE DATABASE IF NOT EXISTS aurora_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_order;

CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT        NOT NULL PRIMARY KEY COMMENT 'aurora-id-generator 供号',
    user_id      BIGINT        NOT NULL,
    sku_id       BIGINT        NOT NULL,
    quantity     INT           NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    status       TINYINT       NOT NULL DEFAULT 0 COMMENT '0=CREATED 1=PAID 2=CLOSED',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_orders_user (user_id, id),
    KEY idx_orders_status_created (status, created_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS idempotent_record (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_type   VARCHAR(32)  NOT NULL,
    biz_key    VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotent (biz_type, biz_key),
    KEY idx_idempotent_created (created_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS tx_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_key    VARCHAR(64)  NOT NULL COMMENT '订单号：本地事务与消息的一致性锚点',
    topic      VARCHAR(64)  NOT NULL,
    tag        VARCHAR(64)  NOT NULL,
    payload    TEXT         NOT NULL,
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=pending 1=sent',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at    DATETIME     NULL,
    UNIQUE KEY uk_tx_message_biz (biz_key, tag),
    KEY idx_tx_message_status (status, created_at)
) ENGINE = InnoDB;
