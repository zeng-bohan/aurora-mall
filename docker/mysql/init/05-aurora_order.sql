CREATE DATABASE IF NOT EXISTS aurora_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_order;

CREATE TABLE IF NOT EXISTS trade_order (
    id                 BIGINT        NOT NULL PRIMARY KEY COMMENT 'aurora-id-generator 供号',
    trade_no           VARCHAR(40)   NOT NULL,
    user_id            BIGINT        NOT NULL,
    source             VARCHAR(16)   NOT NULL DEFAULT 'MALL' COMMENT 'MALL / SECKILL',
    status             VARCHAR(16)   NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED / PAID / SHIPPED / COMPLETED / CLOSED / REFUNDED',
    refund_status      VARCHAR(16)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE / PARTIAL / FULL',
    merchandise_amount DECIMAL(12,2) NOT NULL,
    discount_amount    DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    shipping_fee       DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    payable_amount     DECIMAL(12,2) NOT NULL,
    refunded_amount    DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    coupon_template_id BIGINT        NULL,
    user_coupon_id     BIGINT        NULL,
    receiver_name      VARCHAR(32)   NOT NULL,
    receiver_phone     VARCHAR(20)   NOT NULL,
    province           VARCHAR(32)   NOT NULL,
    city               VARCHAR(32)   NOT NULL,
    district           VARCHAR(32)   NOT NULL,
    address_detail     VARCHAR(200)  NOT NULL,
    postal_code        VARCHAR(12)   NULL,
    carrier            VARCHAR(32)   NULL,
    tracking_no        VARCHAR(64)   NULL,
    seckill_activity_id BIGINT       NULL,
    stock_confirmed    TINYINT       NOT NULL DEFAULT 0,
    stock_released     TINYINT       NOT NULL DEFAULT 0,
    paid_at            DATETIME      NULL,
    shipped_at         DATETIME      NULL,
    completed_at       DATETIME      NULL,
    closed_at          DATETIME      NULL,
    version            INT           NOT NULL DEFAULT 0,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trade_no (trade_no),
    KEY idx_trade_user (user_id, id),
    KEY idx_trade_status_created (status, created_at),
    KEY idx_trade_ship_time (status, shipped_at),
    KEY idx_trade_source (source, created_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS trade_order_item (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trade_id        BIGINT        NOT NULL,
    spu_id          BIGINT        NOT NULL,
    sku_id          BIGINT        NOT NULL,
    spu_title       VARCHAR(160)  NOT NULL,
    sku_code        VARCHAR(48)   NOT NULL,
    attributes_text VARCHAR(255)  NOT NULL,
    image_url       VARCHAR(255)  NOT NULL,
    unit_price      DECIMAL(10,2) NOT NULL,
    quantity        INT           NOT NULL,
    item_amount     DECIMAL(12,2) NOT NULL,
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    refunded_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status          VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / REFUNDED / REFUNDING',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_trade_item_trade (trade_id, id),
    KEY idx_trade_item_sku (sku_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS order_refund (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    refund_no      VARCHAR(40)   NOT NULL,
    trade_id       BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    amount         DECIMAL(12,2) NOT NULL,
    reason         VARCHAR(200)  NOT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    settled        TINYINT       NOT NULL DEFAULT 0 COMMENT '1=退款与库存回补已完成',
    reject_reason  VARCHAR(200)  NULL,
    reviewer       VARCHAR(64)   NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at    DATETIME      NULL,
    UNIQUE KEY uk_refund_no (refund_no),
    KEY idx_refund_trade (trade_id, status),
    KEY idx_refund_status_created (status, created_at),
    KEY idx_refund_settlement (status, settled, reviewed_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS order_refund_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    refund_id     BIGINT        NOT NULL,
    order_item_id BIGINT        NOT NULL,
    sku_id        BIGINT        NOT NULL,
    quantity      INT           NOT NULL,
    amount        DECIMAL(12,2) NOT NULL,
    UNIQUE KEY uk_refund_item (refund_id, order_item_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS order_idempotency (
    user_id      BIGINT       NOT NULL,
    idem_key     VARCHAR(128) NOT NULL,
    trade_id     BIGINT       NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, idem_key),
    KEY idx_order_idempotency_trade (trade_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS tx_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_key    VARCHAR(64)  NOT NULL,
    topic      VARCHAR(64)  NOT NULL,
    tag        VARCHAR(64)  NOT NULL,
    payload    TEXT         NOT NULL,
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=pending 1=sent',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at    DATETIME     NULL,
    UNIQUE KEY uk_tx_message_biz (biz_key, tag),
    KEY idx_tx_message_status (status, created_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS coupon_template (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title            VARCHAR(64)   NOT NULL,
    threshold_amount DECIMAL(10,2) NOT NULL,
    discount_amount  DECIMAL(10,2) NOT NULL,
    total            INT           NOT NULL,
    claimed          INT           NOT NULL DEFAULT 0,
    claim_start_at   DATETIME      NOT NULL,
    claim_end_at     DATETIME      NOT NULL,
    valid_days       INT           NOT NULL,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_coupon_template_window (claim_start_at, claim_end_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS user_coupon (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    template_id      BIGINT        NOT NULL,
    user_id          BIGINT        NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED / LOCKED / USED',
    trade_id         BIGINT        NULL,
    title            VARCHAR(64)   NOT NULL,
    threshold_amount DECIMAL(10,2) NOT NULL,
    discount_amount  DECIMAL(10,2) NOT NULL,
    claimed_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at        DATETIME      NOT NULL,
    used_at          DATETIME      NULL,
    UNIQUE KEY uk_user_coupon_once (template_id, user_id),
    KEY idx_user_coupon_user (user_id, status, expire_at),
    KEY idx_user_coupon_trade (trade_id)
) ENGINE = InnoDB;
