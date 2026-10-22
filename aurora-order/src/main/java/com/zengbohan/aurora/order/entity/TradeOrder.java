package com.zengbohan.aurora.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("trade_order")
public class TradeOrder {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_SHIPPED = "SHIPPED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    public static final String SOURCE_MALL = "MALL";
    public static final String SOURCE_SECKILL = "SECKILL";

    public static final String REFUND_NONE = "NONE";
    public static final String REFUND_PARTIAL = "PARTIAL";
    public static final String REFUND_FULL = "FULL";

    @TableId(type = IdType.INPUT)
    private Long id;
    private String tradeNo;
    private Long userId;
    private String source;
    private String status;
    private String refundStatus;
    private BigDecimal merchandiseAmount;
    private BigDecimal discountAmount;
    private BigDecimal shippingFee;
    private BigDecimal payableAmount;
    private BigDecimal refundedAmount;
    private Long couponTemplateId;
    private Long userCouponId;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String addressDetail;
    private String postalCode;
    private String carrier;
    private String trackingNo;
    private Long seckillActivityId;
    private Integer stockConfirmed;
    private Integer stockReleased;
    private LocalDateTime paidAt;
    private LocalDateTime shippedAt;
    private LocalDateTime completedAt;
    private LocalDateTime closedAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTradeNo() { return tradeNo; }
    public void setTradeNo(String tradeNo) { this.tradeNo = tradeNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }
    public BigDecimal getMerchandiseAmount() { return merchandiseAmount; }
    public void setMerchandiseAmount(BigDecimal merchandiseAmount) { this.merchandiseAmount = merchandiseAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal shippingFee) { this.shippingFee = shippingFee; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public Long getCouponTemplateId() { return couponTemplateId; }
    public void setCouponTemplateId(Long couponTemplateId) { this.couponTemplateId = couponTemplateId; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverPhone() { return receiverPhone; }
    public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getAddressDetail() { return addressDetail; }
    public void setAddressDetail(String addressDetail) { this.addressDetail = addressDetail; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }
    public String getTrackingNo() { return trackingNo; }
    public void setTrackingNo(String trackingNo) { this.trackingNo = trackingNo; }
    public Long getSeckillActivityId() { return seckillActivityId; }
    public void setSeckillActivityId(Long seckillActivityId) { this.seckillActivityId = seckillActivityId; }
    public Integer getStockConfirmed() { return stockConfirmed; }
    public void setStockConfirmed(Integer stockConfirmed) { this.stockConfirmed = stockConfirmed; }
    public Integer getStockReleased() { return stockReleased; }
    public void setStockReleased(Integer stockReleased) { this.stockReleased = stockReleased; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public void setShippedAt(LocalDateTime shippedAt) { this.shippedAt = shippedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
