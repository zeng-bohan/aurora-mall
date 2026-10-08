package com.zengbohan.aurora.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    @Select("SELECT * FROM payment_orders WHERE order_id = #{orderId}")
    PaymentOrder findByOrderId(@Param("orderId") long orderId);

    // 带守卫的迁移：PAYING -> PAID 恰好成功一次。
    @Update("UPDATE payment_orders SET status = 1 WHERE order_id = #{orderId} AND status = 0")
    int markPaid(@Param("orderId") long orderId);

    // 守卫转移：PAYING 或 PAID → REFUNDED（订单已关的钱款退款闭环，迟付/迟到回调/补发 job 共用）。
    @Update("UPDATE payment_orders SET status = 2 WHERE order_id = #{orderId} AND status IN (0, 1)")
    int markRefunded(@Param("orderId") long orderId);

    // 事件发布完成后打标（守卫：不重复标记）。
    @Update("UPDATE payment_orders SET event_published = 1 WHERE order_id = #{orderId} AND event_published = 0")
    int markEventPublished(@Param("orderId") long orderId);

    // PAID 且事件未发的记录：补发 job 的扫描集（LIMIT 200 防一次拖垮）。
    @Select("SELECT * FROM payment_orders WHERE status = 1 AND event_published = 0 "
            + "AND event_failed = 0 "
            + "AND created_at <= #{before} LIMIT 200")
    List<PaymentOrder> findUnpublishedPaid(@Param("before") java.time.LocalDateTime before);

    // 补发失败计数 +1。
    @Update("UPDATE payment_orders SET publish_attempts = publish_attempts + 1 WHERE order_id = #{orderId}")
    int incrementPublishAttempts(@Param("orderId") long orderId);

    // 达到重试上限：放弃自动补发，等待人工处理。
    @Update("UPDATE payment_orders SET event_failed = 1 WHERE order_id = #{orderId}")
    int markEventFailed(@Param("orderId") long orderId);
}
