CREATE DATABASE IF NOT EXISTS aurora_inventory DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_inventory;

CREATE TABLE IF NOT EXISTS product_stock (
    sku_id     BIGINT   NOT NULL PRIMARY KEY,
    available  INT      NOT NULL DEFAULT 0,
    reserved   INT      NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS idempotent_record (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_type   VARCHAR(32) NOT NULL,
    biz_key    VARCHAR(128) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotent (biz_type, biz_key)
) ENGINE = InnoDB;

INSERT IGNORE INTO product_stock (sku_id, available, reserved) VALUES
    (1, 100, 0),
    (2, 100, 0),
    (3, 100, 0),
    (4, 100, 0);
