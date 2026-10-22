package com.zengbohan.aurora.seckill.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SeckillReleaseMapper {

    @Insert("INSERT IGNORE INTO seckill_release (trade_id, activity_id, quantity, reason) "
            + "VALUES (#{tradeId}, #{activityId}, #{quantity}, #{reason})")
    int insertIfAbsent(@Param("tradeId") long tradeId, @Param("activityId") long activityId,
                       @Param("quantity") int quantity, @Param("reason") String reason);

    @Select("SELECT COUNT(*) FROM seckill_release WHERE trade_id = #{tradeId}")
    long exists(@Param("tradeId") long tradeId);
}
