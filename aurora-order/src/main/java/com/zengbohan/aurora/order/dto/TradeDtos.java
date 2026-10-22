package com.zengbohan.aurora.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class TradeDtos {

    private TradeDtos() {
    }

    public record ItemRequest(@NotNull @Min(1) Long skuId,
                              @NotNull @Min(1) @Max(99) Integer quantity) {
    }

    public record PlaceTradeRequest(@NotEmpty @Valid List<ItemRequest> items,
                                    @NotNull Long addressId,
                                    Long couponId) {
    }

    public record PlaceSeckillRequest(long userId, long activityId, long skuId,
                                      BigDecimal price, String idempotencyKey) {
    }

    public record AddressSnapshot(String receiverName, String phone, String province,
                                  String city, String district, String detail, String postalCode) {
    }

    public record TradeItemView(long id, long spuId, long skuId, String title, String skuCode,
                                String attributesText, String imageUrl, BigDecimal unitPrice,
                                int quantity, BigDecimal itemAmount, BigDecimal discountAmount,
                                BigDecimal refundedAmount, String status, boolean refundable) {
    }

    public record RefundBrief(long id, String refundNo, BigDecimal amount, String status,
                              String reason, String rejectReason, LocalDateTime createdAt,
                              LocalDateTime reviewedAt, List<Long> itemIds) {
    }

    public record RefundItemView(long orderItemId, long skuId, String title, int quantity,
                                 BigDecimal amount) {
    }

    public record TradeView(long id, String tradeNo, long userId, String source, String status,
                            String refundStatus, BigDecimal merchandiseAmount,
                            BigDecimal discountAmount, BigDecimal shippingFee,
                            BigDecimal payableAmount, BigDecimal refundedAmount,
                            Long couponId, String couponTitle, AddressSnapshot address,
                            String carrier, String trackingNo, LocalDateTime paidAt,
                            LocalDateTime shippedAt, LocalDateTime completedAt,
                            LocalDateTime closedAt, LocalDateTime createdAt,
                            List<TradeItemView> items, List<RefundBrief> refunds) {
    }

    public record TradeItemPreview(long id, long skuId, String title, String attributesText,
                                   String imageUrl, int quantity, BigDecimal unitPrice,
                                   String status) {
    }

    public record TradeRow(long id, String tradeNo, long userId, String source, String status,
                           String refundStatus, BigDecimal payableAmount,
                           BigDecimal refundedAmount, String receiverName, String receiverPhone,
                           LocalDateTime createdAt, LocalDateTime paidAt,
                           List<TradeItemPreview> items) {
    }

    public record TradePage(List<TradeRow> records, long total, long current, long size, long pages) {
    }

    public record RefundView(long id, String refundNo, long tradeId, long userId,
                             BigDecimal amount, String reason, String status, boolean settled,
                             String rejectReason, String reviewer, LocalDateTime createdAt,
                             LocalDateTime reviewedAt, String tradeNo, String receiverName,
                             List<RefundItemView> items) {
    }

    public record RefundPage(List<RefundView> records, long total, long current, long size, long pages) {
    }

    public record RefundApplyRequest(@NotEmpty List<Long> itemIds,
                                     @NotBlank @Size(max = 200) String reason) {
    }

    public record RefundQuote(boolean allowed, boolean full, BigDecimal amount,
                              BigDecimal remainingMerchandise, String message) {
    }

    public record RefundApplyResult(long refundId, BigDecimal amount, String status) {
    }

    public record ShipRequest(@NotBlank @Size(max = 32) String carrier,
                              @NotBlank @Size(max = 64) String trackingNo) {
    }

    public record AdminStats(long pendingShip, long pendingRefund, long orders30d,
                             BigDecimal revenue30d) {
    }

    public record AdminUserRow(long id, String username, String nickname, String role, int status,
                               LocalDateTime createdAt, long orderCount, LocalDateTime lastOrderAt) {
    }

    public record SimpleResult(long id) {
    }
}
