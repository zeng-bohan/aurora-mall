package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.TradeOrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface TradeOrderItemMapper extends BaseMapper<TradeOrderItem> {

    @Update("UPDATE trade_order_item SET status = 'REFUNDING' WHERE id = #{id} AND status = 'NORMAL'")
    int markRefunding(@Param("id") long id);

    @Update("UPDATE trade_order_item SET status = 'NORMAL' WHERE id = #{id} AND status = 'REFUNDING'")
    int releaseRefunding(@Param("id") long id);

    @Update("UPDATE trade_order_item SET status = 'REFUNDED', refunded_amount = refunded_amount + #{amount} "
            + "WHERE id = #{id} AND status = 'REFUNDING'")
    int markRefunded(@Param("id") long id, @Param("amount") BigDecimal amount);

    @Update("UPDATE trade_order_item SET discount_amount = #{discount} WHERE id = #{id}")
    int updateDiscount(@Param("id") long id, @Param("discount") BigDecimal discount);

    @Update("UPDATE trade_order_item SET discount_amount = 0.00 WHERE id = #{id}")
    int clearDiscount(@Param("id") long id);
}
