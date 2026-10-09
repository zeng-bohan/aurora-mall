-- M5 S6：ShardingSphere 分库试点的两个物理库。
-- orders 的 DDL 复制自 05-aurora_order.sql（同构），试点不碰线上 aurora_order 库。
CREATE DATABASE IF NOT EXISTS aurora_order_shard0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS aurora_order_shard1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE aurora_order_shard0;
CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT        NOT NULL PRIMARY KEY COMMENT '线上由 aurora-id-generator 供号',
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

-- 对照用：自增主键在两个物理库各自计数（验证"分片下 AUTO_INCREMENT 不可作全局主键"）
CREATE TABLE IF NOT EXISTS pilot_autoinc (
    id      BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT      NOT NULL,
    note    VARCHAR(32) NOT NULL
) ENGINE = InnoDB;

-- 对照用：唯一键不含分片键（验证"全局唯一约束在分片下只在本片内成立"）
CREATE TABLE IF NOT EXISTS pilot_unique (
    id      BIGINT      NOT NULL PRIMARY KEY,
    user_id BIGINT      NOT NULL,
    uk_code VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_pilot_code (uk_code)
) ENGINE = InnoDB;

USE aurora_order_shard1;
CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT        NOT NULL PRIMARY KEY COMMENT '线上由 aurora-id-generator 供号',
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

CREATE TABLE IF NOT EXISTS pilot_autoinc (
    id      BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT      NOT NULL,
    note    VARCHAR(32) NOT NULL
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS pilot_unique (
    id      BIGINT      NOT NULL PRIMARY KEY,
    user_id BIGINT      NOT NULL,
    uk_code VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_pilot_code (uk_code)
) ENGINE = InnoDB;
