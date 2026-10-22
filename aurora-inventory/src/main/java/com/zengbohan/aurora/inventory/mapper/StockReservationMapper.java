package com.zengbohan.aurora.inventory.mapper;

import com.zengbohan.aurora.inventory.entity.StockReservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface StockReservationMapper {

    @Select("SELECT * FROM stock_reservation WHERE trade_id = #{tradeId} ORDER BY sku_id")
    List<StockReservation> findByTrade(@Param("tradeId") long tradeId);

    @Select("SELECT * FROM stock_reservation WHERE trade_id = #{tradeId} AND sku_id = #{skuId}")
    StockReservation find(@Param("tradeId") long tradeId, @Param("skuId") long skuId);

    @Insert("INSERT IGNORE INTO stock_reservation (trade_id, sku_id, quantity, status) "
            + "VALUES (#{tradeId}, #{skuId}, #{quantity}, 'RESERVED')")
    int insertIfAbsent(@Param("tradeId") long tradeId, @Param("skuId") long skuId,
                       @Param("quantity") int quantity);

    @Update("UPDATE stock_reservation SET status = 'RELEASED' "
            + "WHERE trade_id = #{tradeId} AND sku_id = #{skuId} AND status = 'RESERVED'")
    int markReleased(@Param("tradeId") long tradeId, @Param("skuId") long skuId);

    @Update("UPDATE stock_reservation SET status = 'CONFIRMED' "
            + "WHERE trade_id = #{tradeId} AND sku_id = #{skuId} AND status = 'RESERVED'")
    int markConfirmed(@Param("tradeId") long tradeId, @Param("skuId") long skuId);
}
