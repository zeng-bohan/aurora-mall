package com.zengbohan.aurora.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.inventory.entity.ProductStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ProductStockMapper extends BaseMapper<ProductStock> {

    @Select("SELECT * FROM product_stock WHERE sku_id = #{skuId} FOR UPDATE")
    ProductStock selectForUpdate(@Param("skuId") long skuId);

    @Update("UPDATE product_stock SET reserved = reserved + #{quantity} WHERE sku_id = #{skuId}")
    int incrementReserved(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("UPDATE product_stock SET reserved = reserved - #{quantity} "
            + "WHERE sku_id = #{skuId} AND reserved >= #{quantity}")
    int decrementReserved(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("UPDATE product_stock SET available = available - #{quantity}, reserved = reserved - #{quantity} "
            + "WHERE sku_id = #{skuId} AND available >= #{quantity} AND reserved >= #{quantity}")
    int confirmReservation(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("UPDATE product_stock SET available = available + #{quantity} WHERE sku_id = #{skuId}")
    int restoreAvailable(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("UPDATE product_stock SET available = #{sellable} + reserved WHERE sku_id = #{skuId}")
    int setSellable(@Param("skuId") long skuId, @Param("sellable") int sellable);
}
