package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 状态机：带守卫的状态迁移只在期望状态下成功，
     * 因此并发事件（支付 vs 关单）不可能同时获胜。
     *
     * @return 迁移成功返回 1；该行处于其他状态时返回 0
     */
    @Update("UPDATE orders SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int transition(@Param("id") long id, @Param("from") int from, @Param("to") int to);

    @Select("SELECT * FROM orders WHERE status = 0 AND created_at <= #{before} LIMIT 200")
    List<Order> findTimedOut(@Param("before") java.time.LocalDateTime before);

    // 补偿扫描：已关闭但库存释放从未完成的订单。
    @Update("UPDATE orders SET stock_released = 1 WHERE id = #{id} AND stock_released = 0")
    int markStockReleased(@Param("id") long id);

    @Select("SELECT * FROM orders WHERE status = 2 AND stock_released = 0 LIMIT 200")
    List<Order> findUnreleasedClosed();

    /**
     * 我的订单：走 idx_orders_user(user_id, id)，按 id 倒序即最近下单在前。
     * 排序用主键而不是 created_at：id 由号段生成器供号、天然递增，且这一列就在
     * 索引里，不需要额外排序。user_id 写进 WHERE 而不是查出来再过滤——
     * 越权看别人的订单在 SQL 这一层就不成立。
     */
    @Select("SELECT * FROM orders WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Order> pageByUser(@Param("userId") long userId,
                           @Param("limit") long limit,
                           @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM orders WHERE user_id = #{userId}")
    long countByUser(@Param("userId") long userId);
}
