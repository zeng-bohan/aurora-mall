CREATE DATABASE IF NOT EXISTS aurora_id DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_id;

CREATE TABLE IF NOT EXISTS leaf_alloc (
    biz_tag     VARCHAR(32)  NOT NULL PRIMARY KEY,
    max_id      BIGINT       NOT NULL DEFAULT 0,
    step        INT          NOT NULL DEFAULT 1000,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB;

INSERT IGNORE INTO leaf_alloc (biz_tag, max_id, step) VALUES ('order', 0, 1000);
