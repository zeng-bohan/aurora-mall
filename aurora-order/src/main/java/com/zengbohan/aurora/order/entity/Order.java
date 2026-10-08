package com.zengbohan.aurora.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zengbohan.aurora.api.order.OrderSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("orders")
public class Order {

    // CREATED -> PAID / CLOSED；每次迁移都由状态 SQL 守卫。
    // 取值以 aurora-api 的 OrderSummary 为准（跨服务状态语义的单一来源）。
    public static final int STATUS_CREATED = OrderSummary.STATUS_CREATED;
    public static final int STATUS_PAID = OrderSummary.STATUS_PAID;
    public static final int STATUS_CLOSED = OrderSummary.STATUS_CLOSED;

    // 事务编排模式：mq = redis 预扣 + 事务消息（主路径）；at = Seata AT 对比场景。
    public static final String TX_MODE_MQ = "mq";
    public static final String TX_MODE_AT = "at";

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long skuId;
    private Integer quantity;
    private BigDecimal totalAmount;
    private Integer status;
    private String txMode;
    private Integer stockReleased;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Integer getStatus() {
        return status;
    }

    public String getTxMode() {
        return txMode;
    }

    public void setTxMode(String txMode) {
        this.txMode = txMode;
    }

    public boolean isStockReleased() {
        return stockReleased != null && stockReleased == 1;
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
