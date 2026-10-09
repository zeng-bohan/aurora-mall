CREATE DATABASE IF NOT EXISTS aurora_seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_seckill;

-- 秒杀活动：运营创建。状态（未开始/进行中/已结束）由时间窗推导，不落列。
CREATE TABLE IF NOT EXISTS seckill_activity (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(64)   NOT NULL,
    sku_id         BIGINT        NOT NULL COMMENT '商品 SKU（aurora_product.sku.id）',
    seckill_price  DECIMAL(10,2) NOT NULL COMMENT '活动价',
    total_stock    INT           NOT NULL COMMENT '活动总量',
    per_user_limit INT           NOT NULL DEFAULT 1 COMMENT '每人限购件数',
    start_at       DATETIME      NOT NULL,
    end_at         DATETIME      NOT NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_seckill_window (start_at, end_at)
) ENGINE = InnoDB;

-- 活动库存：DB 侧最终事实。Redis 是预扣快照（S2 起由 Lua 维护），预热时以此为准。
CREATE TABLE IF NOT EXISTS seckill_stock (
    activity_id BIGINT   NOT NULL PRIMARY KEY,
    total       INT      NOT NULL,
    available   INT      NOT NULL,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB;

-- 秒杀订单：一人一单由唯一键兜底（Redis 只挡流量，DB 是事实）。
CREATE TABLE IF NOT EXISTS seckill_order (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    sku_id      BIGINT        NOT NULL,
    price       DECIMAL(10,2) NOT NULL COMMENT '成交价（下单时刻的活动价）',
    status      VARCHAR(16)   NOT NULL DEFAULT 'CREATED',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_seckill_order_user (activity_id, user_id),
    KEY idx_seckill_order_user (user_id, created_at)
) ENGINE = InnoDB;
