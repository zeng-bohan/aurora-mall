package com.zengbohan.aurora.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zengbohan.aurora.order.entity.CouponTemplate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /**
     * 领取计数 +1，但只在该模板仍有余额时成功——防超发的守卫在 SQL 里。
     * 先查再改（check-then-act）在并发下必然超发，这里与库存扣减同一手法。
     *
     * @return 1 = 占到名额；0 = 已领完
     */
    @Update("UPDATE coupon_template SET claimed = claimed + 1 WHERE id = #{id} AND claimed < total")
    int claimOne(@Param("id") long id);
}
