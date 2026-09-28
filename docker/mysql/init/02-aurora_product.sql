CREATE DATABASE IF NOT EXISTS aurora_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_product;

CREATE TABLE IF NOT EXISTS sku (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(128)  NOT NULL,
    price       DECIMAL(10,2) NOT NULL,
    stock       INT           NOT NULL DEFAULT 0 COMMENT 'display stock; real deduction is M2',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1=on sale, 0=off shelf',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_sku_status (status)
) ENGINE = InnoDB;
