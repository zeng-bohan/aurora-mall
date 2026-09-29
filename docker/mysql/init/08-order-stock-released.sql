USE aurora_order;
ALTER TABLE orders
    ADD COLUMN stock_released TINYINT NOT NULL DEFAULT 0
        COMMENT '1=关单后的库存回滚已完成（释放失败可重入补偿）';
