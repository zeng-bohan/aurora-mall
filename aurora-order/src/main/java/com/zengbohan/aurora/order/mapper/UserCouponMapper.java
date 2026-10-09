package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 我的券台账。不按状态过滤：过期与否由服务层按 {@code expire_at} 推导，
     * 查询只负责给出事实行；按最早到期排前面（先过期的先用）。
     */
    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} ORDER BY expire_at ASC, id ASC")
    List<UserCoupon> findByUser(@Param("userId") long userId);

    /**
     * 用券第一步：UNUSED → LOCKED（带守卫）。
     * 并发下同一张券被两笔订单同时用，只有一次能成功——另一次拿到 0，
     * 由调用方抛业务码并让订单事务回滚。
     *
     * @return 1 = 锁定成功；0 = 已被占用或状态不对
     */
    @Update("UPDATE user_coupon SET status = 'LOCKED' "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'UNUSED'")
    int lock(@Param("id") long id, @Param("userId") long userId);

    /** 把锁定的券绑到订单上（在订单事务内执行，与订单行同生共死）。 */
    @Update("UPDATE user_coupon SET order_id = #{orderId} WHERE id = #{id} AND status = 'LOCKED'")
    int bindToOrder(@Param("id") long id, @Param("orderId") long orderId);

    /**
     * 支付成功：LOCKED → USED。
     *
     * @return 1 = 本次事件核销了券；0 = 该订单没有待核销的券（重投递时自然为 0）
     */
    @Update("UPDATE user_coupon SET status = 'USED', used_at = NOW() "
            + "WHERE order_id = #{orderId} AND status = 'LOCKED'")
    int markUsedByOrder(@Param("orderId") long orderId);

    /**
     * 回退：LOCKED → UNUSED 并解绑（关单/超时）。
     * <p>
     * 只回退 LOCKED：USED 的券对应已支付订单，而本项目里已支付的订单不会被关单
     * （状态机保证），因此不存在"已核销却要回券"的路径。
     *
     * @return 1 = 本次回退了一张券
     */
    @Update("UPDATE user_coupon SET status = 'UNUSED', order_id = NULL "
            + "WHERE order_id = #{orderId} AND status = 'LOCKED'")
    int releaseByOrder(@Param("orderId") long orderId);
}
