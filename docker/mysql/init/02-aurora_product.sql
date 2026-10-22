CREATE DATABASE IF NOT EXISTS aurora_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_product;

CREATE TABLE IF NOT EXISTS product_category (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(32)  NOT NULL,
    slug       VARCHAR(48)  NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=shown, 0=hidden',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_category_slug (slug)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_spu (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT        NOT NULL,
    brand       VARCHAR(32)   NOT NULL DEFAULT 'AURORA',
    title       VARCHAR(96)   NOT NULL,
    subtitle    VARCHAR(160)  NOT NULL,
    description TEXT          NOT NULL,
    spec_json   TEXT          NULL COMMENT '商品级参数，展示用；筛选属性仍走 EAV',
    sort        INT           NOT NULL DEFAULT 0,
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1=on sale, 0=off shelf',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_spu_category_status (category_id, status, sort),
    KEY idx_spu_title (title)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_attribute (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(32)  NOT NULL,
    code       VARCHAR(48)  NOT NULL,
    type       VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / COLOR / NUMBER',
    unit       VARCHAR(16)  NULL,
    filterable TINYINT      NOT NULL DEFAULT 0,
    variant    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=用于生成 SKU 组合',
    required   TINYINT      NOT NULL DEFAULT 0,
    display    TINYINT      NOT NULL DEFAULT 1,
    sort       INT          NOT NULL DEFAULT 0,
    status     TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_attribute_code (code)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_attribute_value (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attribute_id BIGINT       NOT NULL,
    value        VARCHAR(64)  NOT NULL,
    color_hex    VARCHAR(16)  NULL,
    sort         INT          NOT NULL DEFAULT 0,
    status       TINYINT      NOT NULL DEFAULT 1,
    UNIQUE KEY uk_attribute_value (attribute_id, value)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_spu_attribute (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    spu_id       BIGINT NOT NULL,
    attribute_id BIGINT NOT NULL,
    sort         INT    NOT NULL DEFAULT 0,
    UNIQUE KEY uk_spu_attribute (spu_id, attribute_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_spu_attribute_value (
    id                 BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    spu_id             BIGINT NOT NULL,
    attribute_id       BIGINT NOT NULL,
    attribute_value_id BIGINT NOT NULL,
    UNIQUE KEY uk_spu_attribute_value (spu_id, attribute_id),
    KEY idx_spu_attribute_value (attribute_id, attribute_value_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_sku (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    spu_id          BIGINT        NOT NULL,
    sku_code        VARCHAR(48)   NOT NULL,
    title           VARCHAR(128)  NOT NULL COMMENT '下单快照的完整标题',
    price           DECIMAL(10,2) NOT NULL,
    status          TINYINT       NOT NULL DEFAULT 1,
    image_url       VARCHAR(255)  NOT NULL,
    combination_key VARCHAR(255)  NOT NULL COMMENT '排序后的 attributeId:valueId 拼接，保证组合唯一',
    sort            INT           NOT NULL DEFAULT 0,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sku_code (sku_code),
    UNIQUE KEY uk_spu_combination (spu_id, combination_key),
    KEY idx_sku_spu_status (spu_id, status, price)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS product_sku_attribute (
    id                 BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sku_id             BIGINT NOT NULL,
    attribute_id       BIGINT NOT NULL,
    attribute_value_id BIGINT NOT NULL,
    UNIQUE KEY uk_sku_attribute (sku_id, attribute_id),
    KEY idx_sku_attribute_value (attribute_id, attribute_value_id)
) ENGINE = InnoDB;
