package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 我的券台账。不按状态过滤：过期与否由服务层按 {@code expire_at} 推导，
     * 查询只负责给出事实行；按最早到期排前面（先过期的先用）。
     */
    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} ORDER BY expire_at ASC, id ASC")
    List<UserCoupon> findByUser(@Param("userId") long userId);
}
