CREATE DATABASE IF NOT EXISTS aurora_seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_seckill;

CREATE TABLE IF NOT EXISTS seckill_activity (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(64)   NOT NULL,
    spu_id         BIGINT        NOT NULL,
    sku_id         BIGINT        NOT NULL,
    product_title  VARCHAR(160)  NOT NULL,
    product_image  VARCHAR(255)  NOT NULL,
    original_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    seckill_price  DECIMAL(10,2) NOT NULL,
    total_stock    INT           NOT NULL,
    per_user_limit INT           NOT NULL DEFAULT 1,
    start_at       DATETIME      NOT NULL,
    end_at         DATETIME      NOT NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_seckill_window (start_at, end_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS seckill_stock (
    activity_id BIGINT   NOT NULL PRIMARY KEY,
    total       INT      NOT NULL,
    available   INT      NOT NULL,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB;

-- 消费预约台账：DB 扣减与订单创建之间的恢复锚点。
CREATE TABLE IF NOT EXISTS seckill_reservation (
    activity_id BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED / CONSUMED / RELEASED',
    trade_id    BIGINT      NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (activity_id, user_id),
    KEY idx_seckill_reservation_trade (trade_id)
) ENGINE = InnoDB;

-- 关单 / 整单退款归还活动库存，按父订单幂等。
CREATE TABLE IF NOT EXISTS seckill_release (
    trade_id    BIGINT      NOT NULL PRIMARY KEY,
    activity_id BIGINT      NOT NULL,
    quantity    INT         NOT NULL,
    reason      VARCHAR(24) NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS idempotent_record (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_type   VARCHAR(32)  NOT NULL,
    biz_key    VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotent (biz_type, biz_key),
    KEY idx_idempotent_created (created_at)
) ENGINE = InnoDB;
