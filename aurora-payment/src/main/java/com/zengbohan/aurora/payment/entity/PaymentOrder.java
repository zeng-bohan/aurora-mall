package com.zengbohan.aurora.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("payment_orders")
public class PaymentOrder {

    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tradeId;
    private String tradeNo;
    private Long userId;
    private BigDecimal amount;
    private BigDecimal refundedAmount;
    private String status;
    private String channel;
    private LocalDateTime paidAt;
    private Integer eventPublished;
    private Integer publishAttempts;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTradeId() { return tradeId; }
    public void setTradeId(Long tradeId) { this.tradeId = tradeId; }
    public String getTradeNo() { return tradeNo; }
    public void setTradeNo(String tradeNo) { this.tradeNo = tradeNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public Integer getEventPublished() { return eventPublished; }
    public void setEventPublished(Integer eventPublished) { this.eventPublished = eventPublished; }
    public Integer getPublishAttempts() { return publishAttempts; }
    public void setPublishAttempts(Integer publishAttempts) { this.publishAttempts = publishAttempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
