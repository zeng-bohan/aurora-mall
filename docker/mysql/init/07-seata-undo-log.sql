-- Seata AT 模式 undo_log（order 与 inventory 两库各一份；branch 回滚靠它）
USE aurora_order;
CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB     NOT NULL,
    log_status    INT          NOT NULL,
    log_created   DATETIME(6)  NOT NULL,
    log_modified  DATETIME(6)  NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB;

-- AT 对照：订单记录自己的事务模式，关单路径按模式选择回滚目标
ALTER TABLE aurora_order.orders
    ADD COLUMN tx_mode VARCHAR(8) NOT NULL DEFAULT 'mq' COMMENT 'mq=redis预扣+MQ最终一致 at=seata AT 对照';

USE aurora_inventory;
CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB     NOT NULL,
    log_status    INT          NOT NULL,
    log_created   DATETIME(6)  NOT NULL,
    log_modified  DATETIME(6)  NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB;
