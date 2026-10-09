package com.zengbohan.aurora.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.seckill.entity.SeckillStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillStockMapper extends BaseMapper<SeckillStock> {

    // 条件扣减：DB 侧最终防超卖（Redis 只是预扣快照）。返回 0 表示 DB 库存已尽，
    // 调用方据此回滚事务并补偿 Redis 预扣。
    @Update("UPDATE seckill_stock SET available = available - 1 "
            + "WHERE activity_id = #{activityId} AND available > 0")
    int deduct(@Param("activityId") long activityId);
}
