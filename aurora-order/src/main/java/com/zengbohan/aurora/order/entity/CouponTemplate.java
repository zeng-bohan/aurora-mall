package com.zengbohan.aurora.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 券模板（运营创建）。满减语义：订单金额 >= thresholdAmount 时抵扣 discountAmount。
 * {@code claimed} 与 {@code total} 一起构成防超发的守卫（条件更新），不是展示字段。
 */
@TableName("coupon_template")
public class CouponTemplate {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String title;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private Integer total;
    private Integer claimed;
    private LocalDateTime claimStartAt;
    private LocalDateTime claimEndAt;
    private Integer validDays;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public BigDecimal getThresholdAmount() {
        return thresholdAmount;
    }

    public void setThresholdAmount(BigDecimal thresholdAmount) {
        this.thresholdAmount = thresholdAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getClaimed() {
        return claimed;
    }

    public void setClaimed(Integer claimed) {
        this.claimed = claimed;
    }

    public LocalDateTime getClaimStartAt() {
        return claimStartAt;
    }

    public void setClaimStartAt(LocalDateTime claimStartAt) {
        this.claimStartAt = claimStartAt;
    }

    public LocalDateTime getClaimEndAt() {
        return claimEndAt;
    }

    public void setClaimEndAt(LocalDateTime claimEndAt) {
        this.claimEndAt = claimEndAt;
    }

    public Integer getValidDays() {
        return validDays;
    }

    public void setValidDays(Integer validDays) {
        this.validDays = validDays;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
