package com.zengbohan.aurora.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    @Select("SELECT * FROM payment_orders WHERE trade_id = #{tradeId}")
    PaymentOrder findByTradeId(@Param("tradeId") long tradeId);

    @Update("UPDATE payment_orders SET status = 'PAID', paid_at = #{paidAt}, updated_at = NOW() "
            + "WHERE trade_id = #{tradeId} AND status = 'PAYING'")
    int markPaid(@Param("tradeId") long tradeId, @Param("paidAt") LocalDateTime paidAt);

    @Update("UPDATE payment_orders SET status = 'REFUNDED', refunded_amount = amount, updated_at = NOW() "
            + "WHERE trade_id = #{tradeId} AND status IN ('PAYING', 'PAID', 'PARTIALLY_REFUNDED')")
    int markRefundedLate(@Param("tradeId") long tradeId);

    // MySQL 单表 UPDATE 按从左到右赋值：status 先读旧的 refunded_amount 判断，
    // 再把本次金额累加进去，避免部分退款被误记为整单退款。
    @Update("UPDATE payment_orders SET "
            + "status = IF(refunded_amount + #{amount} >= amount, 'REFUNDED', 'PARTIALLY_REFUNDED'), "
            + "refunded_amount = refunded_amount + #{amount}, updated_at = NOW() "
            + "WHERE trade_id = #{tradeId} AND status IN ('PAID', 'PARTIALLY_REFUNDED') "
            + "AND amount - refunded_amount >= #{amount}")
    int applyRefund(@Param("tradeId") long tradeId, @Param("amount") BigDecimal amount);

    @Update("UPDATE payment_orders SET event_published = 1, updated_at = NOW() "
            + "WHERE trade_id = #{tradeId} AND event_published = 0")
    int markEventPublished(@Param("tradeId") long tradeId);

    @Select("SELECT * FROM payment_orders WHERE status = 'PAID' AND event_published = 0 "
            + "AND created_at <= #{before} LIMIT 200")
    List<PaymentOrder> findUnpublishedPaid(@Param("before") LocalDateTime before);

    @Update("UPDATE payment_orders SET publish_attempts = publish_attempts + 1 WHERE trade_id = #{tradeId}")
    int incrementPublishAttempts(@Param("tradeId") long tradeId);
}
