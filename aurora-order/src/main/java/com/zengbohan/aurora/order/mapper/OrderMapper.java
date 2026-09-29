package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface OrderMapper extends BaseMapper<Order> {

    /**
     * The state machine: a guarded transition succeeds only from the expected
     * status, so concurrent events (pay vs close) cannot both win.
     *
     * @return 1 when the transition applied, 0 when the row was in another state
     */
    @Update("UPDATE orders SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int transition(@Param("id") long id, @Param("from") int from, @Param("to") int to);

    @Select("SELECT * FROM orders WHERE status = 0 AND created_at <= #{before} LIMIT 200")
    List<Order> findTimedOut(@Param("before") java.time.LocalDateTime before);

    /** Compensation scan: closed orders whose stock release never completed. */
    @Update("UPDATE orders SET stock_released = 1 WHERE id = #{id} AND stock_released = 0")
    int markStockReleased(@Param("id") long id);

    @Select("SELECT * FROM orders WHERE status = 2 AND stock_released = 0 LIMIT 200")
    List<Order> findUnreleasedClosed();
}
