package com.zengbohan.aurora.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("payment_orders")
public class PaymentOrder {

    public static final int STATUS_PAYING = 0;
    public static final int STATUS_PAID = 1;

    /** 迟到回调：订单已关单时 mock 通道语义为自动退款。 */
    public static final int STATUS_REFUNDED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private Integer status;
    /** 1 = order-paid 事件已发布（补发 job 依据，见 09 migration）。 */
    private Integer eventPublished;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
