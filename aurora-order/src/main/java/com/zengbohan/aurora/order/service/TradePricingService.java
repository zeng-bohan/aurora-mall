package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.order.entity.OrderRefund;
import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.entity.TradeOrderItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 交易金额口径的唯一实现。所有金额在内部换算成整数分，
 * 分摊用最大余数法，避免逐项四舍五入后总额对不上。
 */
@Service
public class TradePricingService {

    public static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("299.00");
    public static final BigDecimal SHIPPING_FEE = new BigDecimal("12.00");

    public record PricedLine(long skuId, BigDecimal unitPrice, int quantity) {
    }

    public record LineQuote(long skuId, BigDecimal unitPrice, int quantity,
                            BigDecimal itemAmount, BigDecimal discountAmount) {
    }

    public record Quote(BigDecimal merchandiseAmount, BigDecimal discountAmount,
                        BigDecimal shippingFee, BigDecimal payableAmount,
                        List<LineQuote> lines) {
    }

    public record RefundCalculation(boolean allowed, boolean full, BigDecimal amount,
                                    BigDecimal remainingMerchandise, String message) {
    }

    public Quote quote(List<PricedLine> lines, BigDecimal requestedDiscount) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单至少包含一件商品");
        }
        List<Long> itemCents = new ArrayList<>(lines.size());
        long merchandiseCents = 0;
        for (PricedLine line : lines) {
            if (line.quantity() < 1) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "购买数量必须大于 0");
            }
            long cents = toCents(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            itemCents.add(cents);
            merchandiseCents += cents;
        }
        long discountCents = requestedDiscount == null ? 0 : toCents(requestedDiscount);
        discountCents = Math.min(Math.max(discountCents, 0), merchandiseCents);
        long postCoupon = merchandiseCents - discountCents;
        long shippingCents = postCoupon >= toCents(FREE_SHIPPING_THRESHOLD) ? 0 : toCents(SHIPPING_FEE);
        long payableCents = postCoupon + shippingCents;
        long[] allocated = allocate(discountCents, itemCents);
        List<LineQuote> quotes = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            PricedLine line = lines.get(i);
            quotes.add(new LineQuote(line.skuId(), line.unitPrice(), line.quantity(),
                    fromCents(itemCents.get(i)), fromCents(allocated[i])));
        }
        return new Quote(fromCents(merchandiseCents), fromCents(discountCents),
                fromCents(shippingCents), fromCents(payableCents), quotes);
    }

    public Quote seckillQuote(long skuId, BigDecimal price) {
        long cents = toCents(price);
        return new Quote(fromCents(cents), fromCents(0), fromCents(0), fromCents(cents),
                List.of(new LineQuote(skuId, price, 1, fromCents(cents), fromCents(0))));
    }

    /**
     * 退款金额口径：
     * 1. 全部剩余子单一起退 = 整单退款，退父订单剩余实付（含运费），券按规则返还；
     * 2. 部分退款只退被选子单的商品金额，不退运费；退款额不超过父订单剩余实付；
     * 3. 用了券且退款后剩余商品金额低于券门槛时，只允许整单退款。
     */
    public RefundCalculation calculateRefund(TradeOrder trade, List<TradeOrderItem> allItems,
                                             List<Long> selectedItemIds, BigDecimal couponThreshold) {
        if (selectedItemIds == null || selectedItemIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要退款的商品");
        }
        List<TradeOrderItem> selected = new ArrayList<>();
        for (Long id : selectedItemIds) {
            TradeOrderItem item = allItems.stream().filter(i -> i.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "退款商品不属于该订单"));
            if (!TradeOrderItem.STATUS_NORMAL.equals(item.getStatus())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "所选商品已在退款中或已退款");
            }
            selected.add(item);
        }
        if (selected.stream().map(TradeOrderItem::getId).distinct().count() != selected.size()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "退款商品不能重复");
        }
        List<TradeOrderItem> remaining = allItems.stream()
                .filter(item -> TradeOrderItem.STATUS_NORMAL.equals(item.getStatus()))
                .filter(item -> !selectedItemIds.contains(item.getId()))
                .toList();
        BigDecimal remainingMerchandise = remaining.stream()
                .map(TradeOrderItem::getItemAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        boolean full = remaining.isEmpty();
        BigDecimal remainingPaid = trade.getPayableAmount().subtract(trade.getRefundedAmount())
                .setScale(2, RoundingMode.HALF_UP);
        if (remainingPaid.compareTo(BigDecimal.ZERO) <= 0) {
            return new RefundCalculation(false, full, BigDecimal.ZERO, remainingMerchandise,
                    "该订单已无可退金额");
        }
        if (TradeOrder.SOURCE_SECKILL.equals(trade.getSource()) && !full) {
            return new RefundCalculation(false, false, BigDecimal.ZERO, remainingMerchandise,
                    "秒杀订单仅支持整单退款");
        }
        if (!full && trade.getUserCouponId() != null && couponThreshold != null
                && remainingMerchandise.compareTo(couponThreshold) < 0) {
            return new RefundCalculation(false, false, BigDecimal.ZERO, remainingMerchandise,
                    "退款后剩余商品金额低于优惠券门槛，请选择全部商品整单退款");
        }
        BigDecimal selectedAmount = selected.stream()
                .map(TradeOrderItem::getItemAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = full ? remainingPaid : selectedAmount.min(remainingPaid);
        return new RefundCalculation(true, full, amount, remainingMerchandise, "");
    }

    public List<LineQuote> reallocateDiscount(List<TradeOrderItem> remainingItems,
                                              BigDecimal discountAmount) {
        if (remainingItems.isEmpty()) {
            return List.of();
        }
        List<Long> cents = remainingItems.stream()
                .map(item -> toCents(item.getItemAmount()))
                .toList();
        long[] allocated = allocate(toCents(discountAmount),
                cents.stream().map(Math::abs).toList());
        List<LineQuote> lines = new ArrayList<>(remainingItems.size());
        for (int i = 0; i < remainingItems.size(); i++) {
            TradeOrderItem item = remainingItems.get(i);
            lines.add(new LineQuote(item.getSkuId(), item.getUnitPrice(), item.getQuantity(),
                    item.getItemAmount(), fromCents(allocated[i])));
        }
        return lines;
    }

    private static long[] allocate(long discountCents, List<Long> itemCents) {
        long[] result = new long[itemCents.size()];
        long total = itemCents.stream().mapToLong(Long::longValue).sum();
        if (discountCents <= 0 || total <= 0) {
            return result;
        }
        long assigned = 0;
        List<Remainder> remainders = new ArrayList<>(itemCents.size());
        for (int i = 0; i < itemCents.size(); i++) {
            long numerator = discountCents * itemCents.get(i);
            result[i] = numerator / total;
            assigned += result[i];
            remainders.add(new Remainder(i, numerator % total));
        }
        long leftover = discountCents - assigned;
        remainders.sort(Comparator.comparingLong(Remainder::remainder).reversed()
                .thenComparingInt(Remainder::index));
        for (int i = 0; i < leftover; i++) {
            result[remainders.get(i % remainders.size()).index()]++;
        }
        return result;
    }

    private record Remainder(int index, long remainder) {
    }

    public long toCents(BigDecimal amount) {
        return Objects.requireNonNull(amount).setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact();
    }

    public BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
