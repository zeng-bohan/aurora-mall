package com.zengbohan.aurora.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.inventory.entity.ProductStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ProductStockMapper extends BaseMapper<ProductStock> {

    /** Atomic in-place increment - never read-modify-write across consumers. */
    @Update("UPDATE product_stock SET reserved = reserved + #{quantity} WHERE sku_id = #{skuId}")
    int incrementReserved(@Param("skuId") long skuId, @Param("quantity") int quantity);

    /** Sets the sellable number to {@code quantity} while reservations in flight:
     *  available = quantity + reserved, in one statement. */
    @Update("UPDATE product_stock SET available = #{quantity} + reserved WHERE sku_id = #{skuId}")
    int resetAvailable(@Param("skuId") long skuId, @Param("quantity") int quantity);

    /** Releases a reservation; guarded so reserved can never go negative. */
    @Update("UPDATE product_stock SET reserved = reserved - #{quantity} "
            + "WHERE sku_id = #{skuId} AND reserved >= #{quantity}")
    int decrementReserved(@Param("skuId") long skuId, @Param("quantity") int quantity);

    /** AT 对照：DB 直接预占（守卫式，可被 seata undo_log 回滚撤销）。 */
    @Update("UPDATE product_stock SET reserved = reserved + #{quantity} "
            + "WHERE sku_id = #{skuId} AND available - reserved >= #{quantity}")
    int reserveDbGuarded(@Param("skuId") long skuId, @Param("quantity") int quantity);

    /** Payment confirmed: the reservation converts to a real deduction. */
    @Update("UPDATE product_stock SET available = available - #{quantity}, reserved = reserved - #{quantity} "
            + "WHERE sku_id = #{skuId} AND reserved >= #{quantity}")
    int confirmPayment(@Param("skuId") long skuId, @Param("quantity") int quantity);
}
