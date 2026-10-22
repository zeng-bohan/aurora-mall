package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} ORDER BY expire_at ASC, id ASC")
    List<UserCoupon> findByUser(@Param("userId") long userId);

    @Update("UPDATE user_coupon SET status = 'LOCKED' "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'UNUSED'")
    int lock(@Param("id") long id, @Param("userId") long userId);

    @Update("UPDATE user_coupon SET trade_id = #{tradeId} WHERE id = #{id} AND status = 'LOCKED'")
    int bindToTrade(@Param("id") long id, @Param("tradeId") long tradeId);

    @Update("UPDATE user_coupon SET status = 'USED', used_at = NOW() "
            + "WHERE trade_id = #{tradeId} AND status = 'LOCKED'")
    int markUsedByTrade(@Param("tradeId") long tradeId);

    @Update("UPDATE user_coupon SET status = 'UNUSED', trade_id = NULL "
            + "WHERE trade_id = #{tradeId} AND status = 'LOCKED'")
    int releaseByTrade(@Param("tradeId") long tradeId);

    @Update("UPDATE user_coupon SET status = 'UNUSED', trade_id = NULL, used_at = NULL "
            + "WHERE trade_id = #{tradeId} AND status IN ('LOCKED', 'USED')")
    int refundByTrade(@Param("tradeId") long tradeId);
}
