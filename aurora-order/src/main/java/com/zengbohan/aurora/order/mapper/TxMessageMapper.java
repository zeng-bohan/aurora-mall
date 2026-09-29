package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.TxMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TxMessageMapper extends BaseMapper<TxMessage> {

    @Select("SELECT * FROM tx_message WHERE biz_key = #{bizKey} AND tag = #{tag}")
    TxMessage findByBizKey(@Param("bizKey") String bizKey, @Param("tag") String tag);

    @Update("UPDATE tx_message SET status = 1, sent_at = NOW() WHERE id = #{id}")
    int markSent(@Param("id") long id);

    @Select("SELECT * FROM tx_message WHERE status = 0 AND created_at <= #{before} LIMIT 200")
    List<TxMessage> findStalePending(@Param("before") java.time.LocalDateTime before);
}
