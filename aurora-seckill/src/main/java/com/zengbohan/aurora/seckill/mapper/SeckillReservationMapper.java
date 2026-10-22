package com.zengbohan.aurora.seckill.mapper;

import com.zengbohan.aurora.seckill.entity.SeckillReservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SeckillReservationMapper {

    @Select("SELECT * FROM seckill_reservation WHERE activity_id = #{activityId} AND user_id = #{userId}")
    SeckillReservation find(@Param("activityId") long activityId, @Param("userId") long userId);

    @Select("SELECT * FROM seckill_reservation WHERE trade_id = #{tradeId} LIMIT 1")
    SeckillReservation findByTrade(@Param("tradeId") long tradeId);

    @Insert("INSERT IGNORE INTO seckill_reservation (activity_id, user_id, status) "
            + "VALUES (#{activityId}, #{userId}, 'RESERVED')")
    int insertIfAbsent(@Param("activityId") long activityId, @Param("userId") long userId);

    @Update("UPDATE seckill_reservation SET status = 'CONSUMED', trade_id = #{tradeId} "
            + "WHERE activity_id = #{activityId} AND user_id = #{userId}")
    int markConsumed(@Param("activityId") long activityId, @Param("userId") long userId,
                     @Param("tradeId") long tradeId);

    @Update("UPDATE seckill_reservation SET status = 'RELEASED' "
            + "WHERE activity_id = #{activityId} AND user_id = #{userId} AND status = 'RESERVED'")
    int markReleased(@Param("activityId") long activityId, @Param("userId") long userId);

    @Update("UPDATE seckill_reservation SET status = 'RELEASED' "
            + "WHERE trade_id = #{tradeId} AND status = 'CONSUMED'")
    int markReleasedByTrade(@Param("tradeId") long tradeId);
}
