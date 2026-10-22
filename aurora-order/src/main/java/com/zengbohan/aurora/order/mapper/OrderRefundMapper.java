package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.OrderRefund;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRefundMapper extends BaseMapper<OrderRefund> {

    @Update("UPDATE order_refund SET status = 'APPROVED', reviewer = #{reviewer}, reviewed_at = #{reviewedAt} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int approve(@Param("id") long id, @Param("reviewer") String reviewer,
                @Param("reviewedAt") LocalDateTime reviewedAt);

    @Update("UPDATE order_refund SET status = 'REJECTED', reject_reason = #{reason}, reviewer = #{reviewer}, "
            + "reviewed_at = #{reviewedAt} WHERE id = #{id} AND status = 'PENDING'")
    int reject(@Param("id") long id, @Param("reason") String reason,
               @Param("reviewer") String reviewer, @Param("reviewedAt") LocalDateTime reviewedAt);

    @Update("UPDATE order_refund SET settled = 1 WHERE id = #{id} AND status = 'APPROVED' AND settled = 0")
    int markSettled(@Param("id") long id);

    @Select("SELECT * FROM order_refund WHERE trade_id = #{tradeId} ORDER BY id DESC")
    List<OrderRefund> findByTrade(@Param("tradeId") long tradeId);

    @Select("SELECT COUNT(*) FROM order_refund WHERE trade_id = #{tradeId} AND status = 'PENDING'")
    long countPendingByTrade(@Param("tradeId") long tradeId);

    @Select("SELECT * FROM order_refund WHERE status = 'APPROVED' AND settled = 0 "
            + "AND reviewed_at <= #{before} LIMIT 200")
    List<OrderRefund> findUnsettled(@Param("before") LocalDateTime before);

    @Select("SELECT COUNT(*) FROM order_refund WHERE status = 'PENDING'")
    long countPending();
}
