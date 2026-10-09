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

-- 券模板：运营创建（M5 S4）。满减类型：订单金额 >= threshold_amount 时抵扣 discount_amount。
-- claimed < total 是防超发的守卫（条件更新），不是展示字段。
CREATE TABLE IF NOT EXISTS coupon_template (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title            VARCHAR(64)   NOT NULL,
    threshold_amount DECIMAL(10,2) NOT NULL COMMENT '使用门槛：订单金额不低于该值才可用',
    discount_amount  DECIMAL(10,2) NOT NULL COMMENT '抵扣金额',
    total            INT           NOT NULL COMMENT '发放总量',
    claimed          INT           NOT NULL DEFAULT 0 COMMENT '已领取数',
    claim_start_at   DATETIME      NOT NULL,
    claim_end_at     DATETIME      NOT NULL,
    valid_days       INT           NOT NULL COMMENT '领取后有效天数（过期由读取时按 expire_at 推导，不落状态）',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_coupon_template_window (claim_start_at, claim_end_at)
) ENGINE = InnoDB;

-- 用户券：一人一张由唯一键兜底（并发抢输的那次会因唯一键失败而回滚，配额随之释放）。
-- 标题/门槛/抵扣在领取时快照：模板日后被改，不影响已领出的券。
CREATE TABLE IF NOT EXISTS user_coupon (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    template_id      BIGINT        NOT NULL,
    user_id          BIGINT        NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/LOCKED/USED（EXPIRED 由读取时推导）',
    order_id         BIGINT        NULL COMMENT '锁定/核销它的订单；未使用时为空',
    title            VARCHAR(64)   NOT NULL COMMENT '领取时快照',
    threshold_amount DECIMAL(10,2) NOT NULL COMMENT '领取时快照',
    discount_amount  DECIMAL(10,2) NOT NULL COMMENT '领取时快照',
    claimed_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at        DATETIME      NOT NULL COMMENT 'claimed_at + valid_days',
    used_at          DATETIME      NULL,
    UNIQUE KEY uk_user_coupon_once (template_id, user_id),
    KEY idx_user_coupon_user (user_id, status, expire_at),
    KEY idx_user_coupon_order (order_id)
) ENGINE = InnoDB;
