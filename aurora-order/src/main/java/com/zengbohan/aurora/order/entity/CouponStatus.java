package com.zengbohan.aurora.order.entity;

/**
 * 用户券状态。
 * <p>
 * 前三个是**落库**的状态（由订单流程推进：UNUSED → LOCKED → USED，回退时 LOCKED → UNUSED）；
 * {@link #EXPIRED} 只在读取/展示时按 {@code expire_at} 推导，从不写进库里——
 * 过期是时间的函数，落成状态就需要一个定时任务去追它，且必然出现"到点但没扫到"的窗口。
 */
public enum CouponStatus {

    UNUSED,
    LOCKED,
    USED,
    /** 派生状态：仅用于展示与使用前校验，不落库。 */
    EXPIRED
}
