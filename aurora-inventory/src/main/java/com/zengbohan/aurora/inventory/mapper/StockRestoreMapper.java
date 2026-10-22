package com.zengbohan.aurora.inventory.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface StockRestoreMapper {

    @Insert("INSERT IGNORE INTO stock_restore (refund_id, sku_id, quantity) "
            + "VALUES (#{refundId}, #{skuId}, #{quantity})")
    int insertIfAbsent(@Param("refundId") long refundId, @Param("skuId") long skuId,
                       @Param("quantity") int quantity);
}
