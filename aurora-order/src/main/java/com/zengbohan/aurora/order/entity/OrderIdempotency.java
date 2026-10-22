package com.zengbohan.aurora.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 下单幂等映射：同一用户、同一 Idempotency-Key 永远对应同一张订单。
 * 与订单在同一个本地事务里写入，因此响应丢失后的重试能拿回原来的订单号。
 */
@TableName("order_idempotency")
public class OrderIdempotency {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    private String idemKey;
    private Long orderId;
    private String requestHash;
    private LocalDateTime createdAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public void setIdemKey(String idemKey) {
        this.idemKey = idemKey;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
