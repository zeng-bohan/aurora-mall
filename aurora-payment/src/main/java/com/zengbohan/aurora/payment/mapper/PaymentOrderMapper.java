package com.zengbohan.aurora.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    @Select("SELECT * FROM payment_orders WHERE order_id = #{orderId}")
    PaymentOrder findByOrderId(@Param("orderId") long orderId);

    /** Guarded transition: PAYING -> PAID succeeds exactly once. */
    @Update("UPDATE payment_orders SET status = 1 WHERE order_id = #{orderId} AND status = 0")
    int markPaid(@Param("orderId") long orderId);

    /** 守卫转移：仅 PAYING → REFUNDED（迟到回调的自动退款，不覆盖已支付）。 */
    @Update("UPDATE payment_orders SET status = 2 WHERE order_id = #{orderId} AND status = 0")
    int markRefunded(@Param("orderId") long orderId);
}
