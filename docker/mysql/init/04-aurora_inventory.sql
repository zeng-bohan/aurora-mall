CREATE DATABASE IF NOT EXISTS aurora_inventory DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_inventory;

CREATE TABLE IF NOT EXISTS product_stock (
    sku_id     BIGINT   NOT NULL PRIMARY KEY,
    available  INT      NOT NULL DEFAULT 0,
    reserved   INT      NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB;

-- 一张父订单一条预占记录；状态机防止重复预扣、重复释放和重复确认。
CREATE TABLE IF NOT EXISTS stock_reservation (
    trade_id    BIGINT      NOT NULL,
    sku_id      BIGINT      NOT NULL,
    quantity    INT         NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED / RELEASED / CONFIRMED',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (trade_id, sku_id),
    KEY idx_stock_reservation_status (status, updated_at)
) ENGINE = InnoDB;

-- 退款回补按退款单幂等。
CREATE TABLE IF NOT EXISTS stock_restore (
    refund_id  BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (refund_id, sku_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS idempotent_record (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_type   VARCHAR(32)  NOT NULL,
    biz_key    VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotent (biz_type, biz_key),
    KEY idx_idempotent_created (created_at)
) ENGINE = InnoDB;
