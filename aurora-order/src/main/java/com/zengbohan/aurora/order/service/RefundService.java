package com.zengbohan.aurora.order.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zengbohan.aurora.api.order.PaymentSummary;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.order.client.InventoryClient;
import com.zengbohan.aurora.order.client.PaymentClient;
import com.zengbohan.aurora.order.client.SeckillClient;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundApplyRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundApplyResult;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundItemView;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundPage;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundQuote;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundView;
import com.zengbohan.aurora.order.entity.OrderRefund;
import com.zengbohan.aurora.order.entity.OrderRefundItem;
import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.entity.TradeOrderItem;
import com.zengbohan.aurora.order.mapper.OrderRefundItemMapper;
import com.zengbohan.aurora.order.mapper.OrderRefundMapper;
import com.zengbohan.aurora.order.mapper.TradeOrderItemMapper;
import com.zengbohan.aurora.order.mapper.TradeOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final long MAX_PAGE_SIZE = 50;

    private final TradeService tradeService;
    private final TradePricingService pricingService;
    private final CouponService couponService;
    private final TradeOrderMapper tradeMapper;
    private final TradeOrderItemMapper itemMapper;
    private final OrderRefundMapper refundMapper;
    private final OrderRefundItemMapper refundItemMapper;
    private final PaymentClient paymentClient;
    private final InventoryClient inventoryClient;
    private final SeckillClient seckillClient;
    private final TransactionTemplate transactionTemplate;

    public RefundService(TradeService tradeService, TradePricingService pricingService,
                         CouponService couponService, TradeOrderMapper tradeMapper,
                         TradeOrderItemMapper itemMapper, OrderRefundMapper refundMapper,
                         OrderRefundItemMapper refundItemMapper, PaymentClient paymentClient,
                         InventoryClient inventoryClient, SeckillClient seckillClient,
                         TransactionTemplate transactionTemplate) {
        this.tradeService = tradeService;
        this.pricingService = pricingService;
        this.couponService = couponService;
        this.tradeMapper = tradeMapper;
        this.itemMapper = itemMapper;
        this.refundMapper = refundMapper;
        this.refundItemMapper = refundItemMapper;
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
        this.seckillClient = seckillClient;
        this.transactionTemplate = transactionTemplate;
    }

    public RefundQuote quote(long userId, long tradeId, List<Long> itemIds) {
        TradeOrder trade = tradeService.requireOwned(userId, tradeId);
        requireRefundable(trade);
        List<TradeOrderItem> items = tradeService.items(tradeId);
        TradePricingService.RefundCalculation calc = pricingService.calculateRefund(
                trade, items, itemIds, couponService.thresholdOf(trade.getUserCouponId()));
        return new RefundQuote(calc.allowed(), calc.full(), calc.amount(),
                calc.remainingMerchandise(), calc.message());
    }

    public RefundApplyResult apply(long userId, long tradeId, RefundApplyRequest request) {
        TradeOrder trade = tradeService.requireOwned(userId, tradeId);
        requireRefundable(trade);
        if (refundMapper.countPendingByTrade(tradeId) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该订单已有待审批的退款申请");
        }
        long[] refundId = new long[1];
        BigDecimal[] refundAmount = new BigDecimal[1];
        transactionTemplate.executeWithoutResult(status -> {
            // 锁住父订单后重新读取明细并计算金额：避免与上一笔审批并发时
            // 用过期快照算出超过剩余实付的退款额。
            TradeOrder locked = tradeMapper.selectForUpdate(tradeId);
            if (locked == null || !TradeOrder.STATUS_PAID.equals(locked.getStatus())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "当前订单状态不支持退款");
            }
            if (refundMapper.countPendingByTrade(tradeId) > 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "该订单已有待审批的退款申请");
            }
            List<TradeOrderItem> items = itemMapper.selectList(new QueryWrapper<TradeOrderItem>()
                    .eq("trade_id", tradeId).orderByAsc("id"));
            TradePricingService.RefundCalculation calc = pricingService.calculateRefund(
                    locked, items, request.itemIds(), couponService.thresholdOf(locked.getUserCouponId()));
            if (!calc.allowed()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, calc.message());
            }
            List<TradeOrderItem> selected = request.itemIds().stream()
                    .map(itemId -> items.stream()
                            .filter(item -> item.getId().equals(itemId))
                            .findFirst()
                            .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR,
                                    "退款商品不属于该订单")))
                    .toList();
            List<BigDecimal> refundAmounts = pricingService.allocateRefundAmounts(
                    selected.stream().map(TradeOrderItem::getItemAmount).toList(), calc.amount());
            OrderRefund refund = new OrderRefund();
            refund.setRefundNo("R" + tradeId + "-" + UUID.randomUUID().toString().substring(0, 8));
            refund.setTradeId(tradeId);
            refund.setUserId(userId);
            refund.setAmount(calc.amount());
            refund.setReason(request.reason().trim());
            refund.setStatus(OrderRefund.STATUS_PENDING);
            refund.setSettled(0);
            refundMapper.insert(refund);
            refundId[0] = refund.getId();
            refundAmount[0] = calc.amount();
            for (int i = 0; i < selected.size(); i++) {
                TradeOrderItem item = selected.get(i);
                if (itemMapper.markRefunding(item.getId()) == 0) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "所选商品状态已变化，请刷新后重试");
                }
                OrderRefundItem refundItem = new OrderRefundItem();
                refundItem.setRefundId(refund.getId());
                refundItem.setOrderItemId(item.getId());
                refundItem.setSkuId(item.getSkuId());
                refundItem.setQuantity(item.getQuantity());
                refundItem.setAmount(refundAmounts.get(i));
                refundItemMapper.insert(refundItem);
            }
        });
        return new RefundApplyResult(refundId[0], refundAmount[0], OrderRefund.STATUS_PENDING);
    }

    public RefundView approve(long refundId, String reviewer) {
        OrderRefund refund = requireRefund(refundId);
        if (OrderRefund.STATUS_APPROVED.equals(refund.getStatus())) {
            return view(refund);
        }
        if (!OrderRefund.STATUS_PENDING.equals(refund.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "退款申请已处理");
        }
        TradeOrder trade = tradeService.requireTrade(refund.getTradeId());
        List<OrderRefundItem> refundItems = refundItems(refundId);
        transactionTemplate.executeWithoutResult(status -> {
            if (refundMapper.approve(refundId, reviewer, LocalDateTime.now()) == 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "退款申请已被其他操作处理");
            }
            for (OrderRefundItem refundItem : refundItems) {
                if (itemMapper.markRefunded(refundItem.getOrderItemId(), refundItem.getAmount()) == 0) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "退款商品状态已变化");
                }
                // 已退款商品不再承担订单优惠；剩余商品重新分摊整张券，
                // 保证明细优惠之和仍等于父订单优惠。
                itemMapper.clearDiscount(refundItem.getOrderItemId());
            }
            BigDecimal newRefunded = trade.getRefundedAmount().add(refund.getAmount());
            long remaining = itemMapper.selectCount(new QueryWrapper<TradeOrderItem>()
                    .eq("trade_id", trade.getId())
                    .ne("status", TradeOrderItem.STATUS_REFUNDED));
            boolean full = remaining == 0;
            String nextStatus = full ? TradeOrder.STATUS_REFUNDED : trade.getStatus();
            String refundStatus = full ? TradeOrder.REFUND_FULL : TradeOrder.REFUND_PARTIAL;
            tradeMapper.applyRefund(trade.getId(), refund.getAmount(), refundStatus, nextStatus);
            if (!full && trade.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
                List<TradeOrderItem> remainingItems = itemMapper.selectList(
                        new QueryWrapper<TradeOrderItem>().eq("trade_id", trade.getId())
                                .eq("status", TradeOrderItem.STATUS_NORMAL));
                for (TradePricingService.LineQuote line : pricingService.reallocateDiscount(
                        remainingItems, trade.getDiscountAmount())) {
                    remainingItems.stream()
                            .filter(item -> item.getSkuId() == line.skuId()
                                    && item.getUnitPrice().compareTo(line.unitPrice()) == 0)
                            .findFirst()
                            .ifPresent(item -> itemMapper.updateDiscount(item.getId(),
                                    line.discountAmount()));
                }
            }
        });
        try {
            settle(refundId);
        } catch (RuntimeException e) {
            log.error("refund {} approved but settlement failed; it will be retried", refundId, e);
        }
        return view(requireRefund(refundId));
    }

    public RefundView reject(long refundId, String reason, String reviewer) {
        OrderRefund refund = requireRefund(refundId);
        if (!OrderRefund.STATUS_PENDING.equals(refund.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "退款申请已处理");
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (refundMapper.reject(refundId, reason, reviewer, LocalDateTime.now()) == 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "退款申请已被其他操作处理");
            }
            for (OrderRefundItem item : refundItems(refundId)) {
                itemMapper.releaseRefunding(item.getOrderItemId());
            }
        });
        return view(requireRefund(refundId));
    }

    public void settle(long refundId) {
        OrderRefund refund = refundMapper.selectById(refundId);
        if (refund == null || !OrderRefund.STATUS_APPROVED.equals(refund.getStatus())
                || (refund.getSettled() != null && refund.getSettled() == 1)) {
            return;
        }
        TradeOrder trade = tradeService.requireTrade(refund.getTradeId());
        Result<PaymentSummary> payment = RemoteCall.invoke("支付", "退款 " + refundId,
                () -> paymentClient.refund(refund.getTradeId(),
                        new PaymentClient.RefundRequest(refundId, refund.getAmount())));
        RemoteCall.requireSuccess(payment, "支付");
        List<OrderRefundItem> items = refundItems(refundId);
        if (TradeOrder.SOURCE_SECKILL.equals(trade.getSource())) {
            int quantity = items.stream().mapToInt(OrderRefundItem::getQuantity).sum();
            Result<Void> release = RemoteCall.invoke("秒杀", "退款释放 " + refundId,
                    () -> seckillClient.release(new SeckillClient.ReleaseRequest(
                            trade.getId(), trade.getSeckillActivityId(), quantity, "REFUND")));
            RemoteCall.requireSuccess(release, "秒杀");
        } else {
            List<InventoryClient.StockItem> stockItems = items.stream()
                    .map(item -> new InventoryClient.StockItem(item.getSkuId(), item.getQuantity()))
                    .toList();
            Result<Void> restore = RemoteCall.invoke("库存", "退款回补 " + refundId,
                    () -> inventoryClient.restore(new InventoryClient.RestoreRequest(
                            refundId, trade.getId(), stockItems)));
            RemoteCall.requireSuccess(restore, "库存");
        }
        if (TradeOrder.REFUND_FULL.equals(trade.getRefundStatus())) {
            couponService.refundReturn(trade.getId());
        }
        refundMapper.markSettled(refundId);
    }

    public int settlePending() {
        int changed = 0;
        for (OrderRefund refund : refundMapper.findUnsettled(LocalDateTime.now().minusSeconds(5))) {
            try {
                settle(refund.getId());
                changed++;
            } catch (RuntimeException e) {
                log.error("refund settlement retry failed for {}", refund.getId(), e);
            }
        }
        return changed;
    }

    public RefundPage myRefunds(long userId, long current, long size) {
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long safeCurrent = Math.max(current, 1);
        QueryWrapper<OrderRefund> wrapper = new QueryWrapper<OrderRefund>()
                .eq("user_id", userId).orderByDesc("id");
        long total = refundMapper.selectCount(wrapper);
        long offset = offsetOrBeyond(safeCurrent, safeSize);
        List<OrderRefund> refunds = offset >= total ? List.of()
                : refundMapper.selectList(wrapper.last("LIMIT " + safeSize + " OFFSET " + offset));
        return new RefundPage(views(refunds), total, safeCurrent, safeSize,
                (total + safeSize - 1) / safeSize);
    }

    public RefundPage adminPage(String status, long current, long size) {
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long safeCurrent = Math.max(current, 1);
        QueryWrapper<OrderRefund> wrapper = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("id");
        long total = refundMapper.selectCount(wrapper);
        long offset = offsetOrBeyond(safeCurrent, safeSize);
        List<OrderRefund> refunds = offset >= total ? List.of()
                : refundMapper.selectList(wrapper.last("LIMIT " + safeSize + " OFFSET " + offset));
        return new RefundPage(views(refunds), total, safeCurrent, safeSize,
                (total + safeSize - 1) / safeSize);
    }

    public RefundView view(long refundId) {
        return view(requireRefund(refundId));
    }

    private RefundView view(OrderRefund refund) {
        TradeOrder trade = tradeMapper.selectById(refund.getTradeId());
        List<OrderRefundItem> refundItems = refundItems(refund.getId());
        Map<Long, TradeOrderItem> items = refundItems.isEmpty() ? Map.of()
                : itemMapper.selectBatchIds(refundItems.stream()
                        .map(OrderRefundItem::getOrderItemId).toList()).stream()
                .collect(Collectors.toMap(TradeOrderItem::getId, Function.identity()));
        List<RefundItemView> itemViews = refundItems.stream()
                .map(item -> {
                    TradeOrderItem orderItem = items.get(item.getOrderItemId());
                    return new RefundItemView(item.getOrderItemId(), item.getSkuId(),
                            orderItem == null ? "" : orderItem.getSpuTitle(),
                            item.getQuantity(), item.getAmount());
                }).toList();
        return new RefundView(refund.getId(), refund.getRefundNo(), refund.getTradeId(),
                refund.getUserId(), refund.getAmount(), refund.getReason(), refund.getStatus(),
                refund.getSettled() != null && refund.getSettled() == 1, refund.getRejectReason(),
                refund.getReviewer(), refund.getCreatedAt(), refund.getReviewedAt(),
                trade == null ? "" : trade.getTradeNo(),
                trade == null ? "" : trade.getReceiverName(), itemViews);
    }

    private List<RefundView> views(List<OrderRefund> refunds) {
        return refunds.stream().map(this::view).toList();
    }

    private List<OrderRefundItem> refundItems(long refundId) {
        return refundItemMapper.selectList(new QueryWrapper<OrderRefundItem>()
                .eq("refund_id", refundId).orderByAsc("id"));
    }

    private OrderRefund requireRefund(long refundId) {
        OrderRefund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return refund;
    }

    private static void requireRefundable(TradeOrder trade) {
        if (!TradeOrder.STATUS_PAID.equals(trade.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    TradeOrder.STATUS_SHIPPED.equals(trade.getStatus())
                            ? "订单已发货，不支持发货前退款" : "当前订单状态不支持退款");
        }
    }

    private static long offsetOrBeyond(long current, long size) {
        try {
            return Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
