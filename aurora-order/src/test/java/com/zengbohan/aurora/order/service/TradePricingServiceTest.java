package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.entity.TradeOrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradePricingServiceTest {

    private final TradePricingService pricing = new TradePricingService();

    @Test
    void discountAllocatedByLargestRemainderAndShippingIsFreeOverThreshold() {
        TradePricingService.Quote quote = pricing.quote(
                List.of(
                        new TradePricingService.PricedLine(1L, new BigDecimal("100.00"), 1),
                        new TradePricingService.PricedLine(2L, new BigDecimal("250.00"), 1),
                        new TradePricingService.PricedLine(3L, new BigDecimal("250.00"), 1)),
                new BigDecimal("50.00"));

        assertThat(quote.merchandiseAmount()).isEqualByComparingTo("600.00");
        assertThat(quote.discountAmount()).isEqualByComparingTo("50.00");
        assertThat(quote.shippingFee()).isEqualByComparingTo("0.00");
        assertThat(quote.payableAmount()).isEqualByComparingTo("550.00");
        assertThat(quote.lines()).extracting(TradePricingService.LineQuote::discountAmount)
                .containsExactly(new BigDecimal("8.34"), new BigDecimal("20.83"), new BigDecimal("20.83"));
        assertThat(quote.lines().stream()
                .map(TradePricingService.LineQuote::discountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("50.00");
    }

    @Test
    void shippingUsesPostCouponMerchandiseAmount() {
        TradePricingService.Quote free = pricing.quote(
                List.of(new TradePricingService.PricedLine(1L, new BigDecimal("349.00"), 1)),
                new BigDecimal("50.00"));
        TradePricingService.Quote charged = pricing.quote(
                List.of(new TradePricingService.PricedLine(1L, new BigDecimal("348.00"), 1)),
                new BigDecimal("50.00"));

        assertThat(free.shippingFee()).isEqualByComparingTo("0.00");
        assertThat(charged.shippingFee()).isEqualByComparingTo("12.00");
        assertThat(charged.payableAmount()).isEqualByComparingTo("310.00");
    }

    @Test
    void partialRefundUsesItemPriceAndKeepsCouponThreshold() {
        TradeOrder trade = trade("550.00", "0.00", TradeOrder.SOURCE_MALL, 1L);
        List<TradeOrderItem> items = List.of(
                item(1L, 1L, "100.00"),
                item(2L, 2L, "250.00"),
                item(3L, 3L, "250.00"));

        TradePricingService.RefundCalculation calc = pricing.calculateRefund(
                trade, items, List.of(1L), new BigDecimal("500.00"));

        assertThat(calc.allowed()).isTrue();
        assertThat(calc.full()).isFalse();
        assertThat(calc.amount()).isEqualByComparingTo("100.00");
        assertThat(calc.remainingMerchandise()).isEqualByComparingTo("500.00");
    }

    @Test
    void partialRefundRejectedWhenRemainingBelowCouponThreshold() {
        TradeOrder trade = trade("550.00", "0.00", TradeOrder.SOURCE_MALL, 1L);
        List<TradeOrderItem> items = List.of(
                item(1L, 1L, "100.00"),
                item(2L, 2L, "250.00"),
                item(3L, 3L, "250.00"));

        TradePricingService.RefundCalculation calc = pricing.calculateRefund(
                trade, items, List.of(2L), new BigDecimal("500.00"));

        assertThat(calc.allowed()).isFalse();
        assertThat(calc.message()).contains("优惠券门槛");
        assertThat(calc.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void fullRefundReturnsRemainingPaidAmountAndShipping() {
        TradeOrder trade = trade("550.00", "0.00", TradeOrder.SOURCE_MALL, 1L);
        List<TradeOrderItem> items = List.of(
                item(1L, 1L, "100.00"),
                item(2L, 2L, "250.00"),
                item(3L, 3L, "250.00"));

        TradePricingService.RefundCalculation calc = pricing.calculateRefund(
                trade, items, List.of(1L, 2L, 3L), new BigDecimal("500.00"));

        assertThat(calc.allowed()).isTrue();
        assertThat(calc.full()).isTrue();
        assertThat(calc.amount()).isEqualByComparingTo("550.00");
    }

    @Test
    void fullRefundAfterPartialReturnsRemainingPaidOnly() {
        TradeOrder trade = trade("550.00", "100.00", TradeOrder.SOURCE_MALL, 1L);
        List<TradeOrderItem> items = List.of(
                item(1L, 1L, "100.00"),
                item(2L, 2L, "250.00"),
                item(3L, 3L, "250.00"));
        items.get(0).setStatus(TradeOrderItem.STATUS_REFUNDED);

        TradePricingService.RefundCalculation calc = pricing.calculateRefund(
                trade, items, List.of(2L, 3L), new BigDecimal("500.00"));

        assertThat(calc.allowed()).isTrue();
        assertThat(calc.full()).isTrue();
        assertThat(calc.amount()).isEqualByComparingTo("450.00");
    }

    @Test
    void seckillOrderOnlyAllowsFullRefund() {
        TradeOrder trade = trade("149.00", "0.00", TradeOrder.SOURCE_SECKILL, null);
        List<TradeOrderItem> items = List.of(
                item(1L, 1L, "149.00"),
                item(2L, 2L, "100.00"));

        TradePricingService.RefundCalculation partial = pricing.calculateRefund(
                trade, items, List.of(1L), null);
        TradePricingService.RefundCalculation full = pricing.calculateRefund(
                trade, items, List.of(1L, 2L), null);

        assertThat(partial.allowed()).isFalse();
        assertThat(partial.message()).contains("整单退款");
        assertThat(full.allowed()).isTrue();
        assertThat(full.amount()).isEqualByComparingTo("149.00");
    }

    @Test
    void remainingItemsReallocateOriginalDiscount() {
        List<TradeOrderItem> items = List.of(
                item(2L, 2L, "250.00"),
                item(3L, 3L, "250.00"));

        List<TradePricingService.LineQuote> lines = pricing.reallocateDiscount(
                items, new BigDecimal("50.00"));

        assertThat(lines).extracting(TradePricingService.LineQuote::discountAmount)
                .containsExactly(new BigDecimal("25.00"), new BigDecimal("25.00"));
    }


    @Test
    void refundAllocationCapsDetailAmountsAtActualRefund() {
        List<BigDecimal> allocated = pricing.allocateRefundAmounts(
                List.of(new BigDecimal("100.00"), new BigDecimal("100.00")),
                new BigDecimal("162.00"));

        assertThat(allocated).containsExactly(new BigDecimal("81.00"), new BigDecimal("81.00"));
        assertThat(allocated.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("162.00");
    }

    @Test
    void oneCentAmountsAndFreeShippingThresholdUseIntegerCents() {
        TradePricingService.Quote oneCent = pricing.quote(
                List.of(new TradePricingService.PricedLine(1L, new BigDecimal("0.01"), 1)),
                null);
        assertThat(oneCent.merchandiseAmount()).isEqualByComparingTo("0.01");
        assertThat(oneCent.shippingFee()).isEqualByComparingTo("12.00");
        assertThat(oneCent.payableAmount()).isEqualByComparingTo("12.01");

        TradePricingService.Quote exact = pricing.quote(
                List.of(new TradePricingService.PricedLine(1L, new BigDecimal("299.00"), 1)),
                null);
        TradePricingService.Quote below = pricing.quote(
                List.of(new TradePricingService.PricedLine(1L, new BigDecimal("298.99"), 1)),
                null);
        assertThat(exact.shippingFee()).isEqualByComparingTo("0.00");
        assertThat(below.shippingFee()).isEqualByComparingTo("12.00");

        List<BigDecimal> oneCentRefund = pricing.allocateRefundAmounts(
                List.of(new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.01")),
                new BigDecimal("0.01"));
        assertThat(oneCentRefund.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.01");
    }

    private static TradeOrder trade(String payable, String refunded, String source, Long couponId) {
        TradeOrder trade = new TradeOrder();
        trade.setPayableAmount(new BigDecimal(payable));
        trade.setRefundedAmount(new BigDecimal(refunded));
        trade.setSource(source);
        trade.setUserCouponId(couponId);
        return trade;
    }

    private static TradeOrderItem item(long id, long skuId, String amount) {
        TradeOrderItem item = new TradeOrderItem();
        item.setId(id);
        item.setSkuId(skuId);
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal(amount));
        item.setItemAmount(new BigDecimal(amount));
        item.setStatus(TradeOrderItem.STATUS_NORMAL);
        return item;
    }
}
