package com.zengbohan.aurora.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.seckill.entity.SeckillStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillStockMapper extends BaseMapper<SeckillStock> {

    @Update("UPDATE seckill_stock SET available = available - #{quantity} "
            + "WHERE activity_id = #{activityId} AND available >= #{quantity}")
    int deduct(@Param("activityId") long activityId, @Param("quantity") int quantity);

    @Update("UPDATE seckill_stock SET available = available + #{quantity} "
            + "WHERE activity_id = #{activityId}")
    int restore(@Param("activityId") long activityId, @Param("quantity") int quantity);
}
