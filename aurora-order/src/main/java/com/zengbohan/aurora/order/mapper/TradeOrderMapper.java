package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.TradeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    @Update("UPDATE trade_order SET status = #{to}, updated_at = NOW() "
            + "WHERE id = #{id} AND status = #{from}")
    int transition(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    @Update("UPDATE trade_order SET status = 'PAID', paid_at = #{paidAt}, updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'CREATED'")
    int markPaid(@Param("id") long id, @Param("paidAt") LocalDateTime paidAt);

    @Update("UPDATE trade_order SET status = 'SHIPPED', carrier = #{carrier}, tracking_no = #{trackingNo}, "
            + "shipped_at = NOW(), updated_at = NOW() WHERE id = #{id} AND status = 'PAID'")
    int markShipped(@Param("id") long id, @Param("carrier") String carrier,
                    @Param("trackingNo") String trackingNo);

    @Update("UPDATE trade_order SET status = 'COMPLETED', completed_at = NOW(), updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'SHIPPED'")
    int markCompleted(@Param("id") long id);

    @Update("UPDATE trade_order SET status = 'CLOSED', closed_at = NOW(), updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'CREATED'")
    int markClosed(@Param("id") long id);

    @Update("UPDATE trade_order SET stock_confirmed = 1, updated_at = NOW() "
            + "WHERE id = #{id} AND stock_confirmed = 0")
    int markStockConfirmed(@Param("id") long id);

    @Update("UPDATE trade_order SET stock_released = 1, updated_at = NOW() "
            + "WHERE id = #{id} AND stock_released = 0")
    int markStockReleased(@Param("id") long id);

    @Update("UPDATE trade_order SET refunded_amount = refunded_amount + #{amount}, "
            + "refund_status = #{refundStatus}, status = #{status}, version = version + 1, updated_at = NOW() "
            + "WHERE id = #{id}")
    int applyRefund(@Param("id") long id, @Param("amount") BigDecimal amount,
                    @Param("refundStatus") String refundStatus, @Param("status") String status);

    @Select("SELECT * FROM trade_order WHERE status = 'CREATED' AND created_at <= #{before} LIMIT 200")
    List<TradeOrder> findTimedOut(@Param("before") LocalDateTime before);

    @Select("SELECT * FROM trade_order WHERE status = 'SHIPPED' AND shipped_at <= #{before} LIMIT 200")
    List<TradeOrder> findShippedBefore(@Param("before") LocalDateTime before);

    @Select("SELECT * FROM trade_order WHERE status = 'CLOSED' AND stock_released = 0 LIMIT 200")
    List<TradeOrder> findClosedUnreleased();

    @Select("SELECT * FROM trade_order WHERE id = #{id} FOR UPDATE")
    TradeOrder selectForUpdate(@Param("id") long id);

    @Select("SELECT * FROM trade_order WHERE source = 'SECKILL' AND user_id = #{userId} "
            + "AND seckill_activity_id = #{activityId} ORDER BY id DESC LIMIT 1")
    TradeOrder findSeckillTrade(@Param("userId") long userId, @Param("activityId") long activityId);

    @Select("SELECT COUNT(*) FROM trade_order WHERE created_at >= #{since}")
    long countSince(@Param("since") LocalDateTime since);

    @Select("SELECT COALESCE(SUM(payable_amount - refunded_amount), 0) FROM trade_order "
            + "WHERE status IN ('PAID', 'SHIPPED', 'COMPLETED') AND paid_at >= #{since}")
    BigDecimal paidRevenueSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM trade_order WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT user_id AS userId, COUNT(*) AS orderCount, MAX(created_at) AS lastOrderAt "
            + "FROM trade_order GROUP BY user_id")
    List<UserOrderStat> userStats();

    class UserOrderStat {
        private Long userId;
        private Long orderCount;
        private LocalDateTime lastOrderAt;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getOrderCount() { return orderCount; }
        public void setOrderCount(Long orderCount) { this.orderCount = orderCount; }
        public LocalDateTime getLastOrderAt() { return lastOrderAt; }
        public void setLastOrderAt(LocalDateTime lastOrderAt) { this.lastOrderAt = lastOrderAt; }
    }
}
