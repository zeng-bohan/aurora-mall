package com.zengbohan.aurora.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.inventory.entity.ProductStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ProductStockMapper extends BaseMapper<ProductStock> {

    // 原子就地自增——绝不跨消费者做读-改-写。
    @Update("UPDATE product_stock SET reserved = reserved + #{quantity} WHERE sku_id = #{skuId}")
    int incrementReserved(@Param("skuId") long skuId, @Param("quantity") int quantity);

    /** 在预占在途期间把可售数量设为 {@code quantity}：
     *  available = quantity + reserved，一条语句完成。 */
    @Update("UPDATE product_stock SET available = #{quantity} + reserved WHERE sku_id = #{skuId}")
    int resetAvailable(@Param("skuId") long skuId, @Param("quantity") int quantity);

    // 释放一次预占；带守卫，保证 reserved 不会变成负数。
    @Update("UPDATE product_stock SET reserved = reserved - #{quantity} "
            + "WHERE sku_id = #{skuId} AND reserved >= #{quantity}")
    int decrementReserved(@Param("skuId") long skuId, @Param("quantity") int quantity);

    // AT 对照：DB 直接预占（守卫式，可被 seata undo_log 回滚撤销）。
    @Update("UPDATE product_stock SET reserved = reserved + #{quantity} "
            + "WHERE sku_id = #{skuId} AND available - reserved >= #{quantity}")
    int reserveDbGuarded(@Param("skuId") long skuId, @Param("quantity") int quantity);

    // 支付已确认：预占转为真实扣减。
    @Update("UPDATE product_stock SET available = available - #{quantity}, reserved = reserved - #{quantity} "
            + "WHERE sku_id = #{skuId} AND reserved >= #{quantity}")
    int confirmPayment(@Param("skuId") long skuId, @Param("quantity") int quantity);
}
