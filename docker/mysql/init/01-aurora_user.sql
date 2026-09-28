CREATE DATABASE IF NOT EXISTS aurora_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aurora_user;

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(72)  NOT NULL COMMENT 'BCrypt hash',
    role        VARCHAR(16)  NOT NULL DEFAULT 'USER',
    nickname    VARCHAR(64)  NULL,
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB;
