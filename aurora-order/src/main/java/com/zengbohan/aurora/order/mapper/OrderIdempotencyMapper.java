package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.OrderIdempotency;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OrderIdempotencyMapper extends BaseMapper<OrderIdempotency> {

    @Select("SELECT * FROM order_idempotency WHERE user_id = #{userId} AND idem_key = #{idemKey}")
    OrderIdempotency find(@Param("userId") long userId, @Param("idemKey") String idemKey);
}
