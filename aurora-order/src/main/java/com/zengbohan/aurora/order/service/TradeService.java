package com.zengbohan.aurora.order.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.mq.TradeTopics;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.id.SegmentIdGenerator;
import com.zengbohan.aurora.order.client.AddressClient;
import com.zengbohan.aurora.order.client.InventoryClient;
import com.zengbohan.aurora.order.client.PaymentClient;
import com.zengbohan.aurora.order.client.ProductClient;
import com.zengbohan.aurora.order.client.SeckillClient;
import com.zengbohan.aurora.order.client.UserAdminClient;
import com.zengbohan.aurora.order.dto.TradeDtos.AddressSnapshot;
import com.zengbohan.aurora.order.dto.TradeDtos.AdminStats;
import com.zengbohan.aurora.order.dto.TradeDtos.AdminUserRow;
import com.zengbohan.aurora.order.dto.TradeDtos.ItemRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.PlaceSeckillRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.PlaceTradeRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundBrief;
import com.zengbohan.aurora.order.dto.TradeDtos.ShipRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.TradeItemPreview;
import com.zengbohan.aurora.order.dto.TradeDtos.TradeItemView;
import com.zengbohan.aurora.order.dto.TradeDtos.TradePage;
import com.zengbohan.aurora.order.dto.TradeDtos.TradeRow;
import com.zengbohan.aurora.order.dto.TradeDtos.TradeView;
import com.zengbohan.aurora.order.entity.OrderIdempotency;
import com.zengbohan.aurora.order.entity.OrderRefund;
import com.zengbohan.aurora.order.entity.OrderRefundItem;
import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.entity.TradeOrderItem;
import com.zengbohan.aurora.order.entity.TxMessage;
import com.zengbohan.aurora.order.mapper.OrderIdempotencyMapper;
import com.zengbohan.aurora.order.mapper.OrderRefundItemMapper;
import com.zengbohan.aurora.order.mapper.OrderRefundMapper;
import com.zengbohan.aurora.order.mapper.TradeOrderItemMapper;
import com.zengbohan.aurora.order.mapper.TradeOrderMapper;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import com.zengbohan.aurora.order.mq.OrderEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);
    private static final long MAX_PAGE_SIZE = 50;

    private final SegmentIdGenerator idGenerator;
    private final TradePricingService pricingService;
    private final CouponService couponService;
    private final ProductClient productClient;
    private final AddressClient addressClient;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final SeckillClient seckillClient;
    private final UserAdminClient userAdminClient;
    private final TradeOrderMapper tradeMapper;
    private final TradeOrderItemMapper itemMapper;
    private final OrderRefundMapper refundMapper;
    private final OrderRefundItemMapper refundItemMapper;
    private final OrderIdempotencyMapper idempotencyMapper;
    private final TxMessageMapper txMessageMapper;
    private final OrderEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final int closeDelayLevel;
    private final long closeTimeoutSeconds;
    private final long autoCompleteDays;

    public TradeService(SegmentIdGenerator idGenerator, TradePricingService pricingService,
                        CouponService couponService, ProductClient productClient,
                        AddressClient addressClient, InventoryClient inventoryClient,
                        PaymentClient paymentClient, SeckillClient seckillClient,
                        UserAdminClient userAdminClient, TradeOrderMapper tradeMapper,
                        TradeOrderItemMapper itemMapper, OrderRefundMapper refundMapper,
                        OrderRefundItemMapper refundItemMapper,
                        OrderIdempotencyMapper idempotencyMapper, TxMessageMapper txMessageMapper,
                        OrderEventPublisher publisher, TransactionTemplate transactionTemplate,
                        ObjectMapper objectMapper,
                        @Value("${aurora.order.close-delay-level:16}") int closeDelayLevel,
                        @Value("${aurora.order.close-timeout-seconds:1800}") long closeTimeoutSeconds,
                        @Value("${aurora.order.auto-complete-days:7}") long autoCompleteDays) {
        this.idGenerator = idGenerator;
        this.pricingService = pricingService;
        this.couponService = couponService;
        this.productClient = productClient;
        this.addressClient = addressClient;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.seckillClient = seckillClient;
        this.userAdminClient = userAdminClient;
        this.tradeMapper = tradeMapper;
        this.itemMapper = itemMapper;
        this.refundMapper = refundMapper;
        this.refundItemMapper = refundItemMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.txMessageMapper = txMessageMapper;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.closeDelayLevel = closeDelayLevel;
        this.closeTimeoutSeconds = closeTimeoutSeconds;
        this.autoCompleteDays = autoCompleteDays;
    }

    public long closeTimeoutSeconds() {
        return closeTimeoutSeconds;
    }

    public long place(long userId, PlaceTradeRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        Map<Long, Integer> quantities = mergeItems(request.items());
        List<ProductSnapshot> snapshots = loadSnapshots(quantities.keySet());
        Map<Long, ProductSnapshot> snapshotMap = snapshots.stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
        for (Long skuId : quantities.keySet()) {
            ProductSnapshot snapshot = snapshotMap.get(skuId);
            if (snapshot == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "商品不存在或已下架");
            }
            if (snapshot.status() == null || snapshot.status() != ProductSnapshot.STATUS_ON_SALE) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "商品已下架");
            }
        }
        List<TradePricingService.PricedLine> lines = quantities.entrySet().stream()
                .map(entry -> new TradePricingService.PricedLine(entry.getKey(),
                        snapshotMap.get(entry.getKey()).price(), entry.getValue()))
                .toList();
        BigDecimal merchandise = lines.stream()
                .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        CouponService.CouponUse coupon = request.couponId() == null
                ? null : couponService.prepareUse(request.couponId(), userId);
        BigDecimal discount = coupon == null ? BigDecimal.ZERO
                : couponService.discountFor(coupon, merchandise);
        TradePricingService.Quote quote = pricingService.quote(lines, discount);
        String requestHash = fingerprint("MALL|" + request.addressId() + "|" + request.couponId()
                + "|" + quantities + "|" + quote.payableAmount());

        OrderIdempotency existing = idempotencyMapper.find(userId, idempotencyKey);
        if (existing != null) {
            return replay(existing, requestHash);
        }
        AddressClient.AddressView address = loadAddress(userId, request.addressId());
        long tradeId = idGenerator.nextId();
        List<InventoryClient.StockItem> stockItems = quantities.entrySet().stream()
                .map(entry -> new InventoryClient.StockItem(entry.getKey(), entry.getValue()))
                .toList();
        reserveStock(tradeId, stockItems);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                TradeOrder trade = buildTrade(tradeId, userId, TradeOrder.SOURCE_MALL, quote,
                        address, request.couponId(), coupon == null ? null : coupon.templateId(), null);
                tradeMapper.insert(trade);
                insertItems(tradeId, quote, snapshotMap);
                if (coupon != null) {
                    couponService.bindInTransaction(tradeId, coupon);
                }
                insertIdempotency(userId, idempotencyKey, tradeId, requestHash);
                insertCloseMessage(tradeId);
            });
        } catch (ConcurrentIdempotentRequest e) {
            releaseStockQuietly(tradeId, stockItems, "concurrent idempotent request");
            OrderIdempotency winner = idempotencyMapper.find(userId, idempotencyKey);
            if (winner != null) {
                return replay(winner, requestHash);
            }
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        } catch (RuntimeException e) {
            releaseStockQuietly(tradeId, stockItems, "local transaction failed");
            throw e;
        }
        sendClose(tradeId);
        return tradeId;
    }

    public long placeSeckill(PlaceSeckillRequest request) {
        String idempotencyKey = "seckill:" + request.activityId();
        String requestHash = fingerprint("SECKILL|" + request.activityId() + "|"
                + request.skuId() + "|" + request.price());
        OrderIdempotency existing = idempotencyMapper.find(request.userId(), idempotencyKey);
        if (existing != null) {
            return replay(existing, requestHash);
        }
        ProductSnapshot snapshot = loadSnapshot(request.skuId());
        AddressClient.AddressView address = loadDefaultAddress(request.userId());
        TradePricingService.Quote quote = pricingService.seckillQuote(request.skuId(), request.price());
        long tradeId = idGenerator.nextId();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                TradeOrder trade = buildTrade(tradeId, request.userId(), TradeOrder.SOURCE_SECKILL,
                        quote, address, null, null, request.activityId());
                tradeMapper.insert(trade);
                Map<Long, ProductSnapshot> snapshotMap = Map.of(snapshot.id(), snapshot);
                insertItems(tradeId, quote, snapshotMap);
                insertIdempotency(request.userId(), idempotencyKey, tradeId, requestHash);
                insertCloseMessage(tradeId);
            });
        } catch (ConcurrentIdempotentRequest e) {
            OrderIdempotency winner = idempotencyMapper.find(request.userId(), idempotencyKey);
            if (winner != null) {
                return replay(winner, requestHash);
            }
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        }
        sendClose(tradeId);
        return tradeId;
    }

    public TradeView getTrade(long userId, long tradeId) {
        TradeOrder trade = requireTrade(tradeId);
        if (trade.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return view(trade);
    }

    public TradePage listTrades(long userId, long current, long size, String status) {
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long safeCurrent = Math.max(current, 1);
        QueryWrapper<TradeOrder> countWrapper = new QueryWrapper<TradeOrder>().eq("user_id", userId);
        if (status != null && !status.isBlank()) {
            countWrapper.eq("status", status);
        }
        long total = tradeMapper.selectCount(countWrapper);
        long offset = offsetOrBeyond(safeCurrent, safeSize);
        List<TradeOrder> trades = offset >= total ? List.of()
                : tradeMapper.selectList(countWrapper.orderByDesc("id")
                .last("LIMIT " + safeSize + " OFFSET " + offset));
        return new TradePage(rows(trades), total, safeCurrent, safeSize,
                (total + safeSize - 1) / safeSize);
    }

    public void cancel(long userId, long tradeId) {
        TradeOrder trade = requireOwned(userId, tradeId);
        if (!TradeOrder.STATUS_CREATED.equals(trade.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只有待支付订单可以取消");
        }
        closeIfPending(tradeId);
    }

    public void confirmReceipt(long userId, long tradeId) {
        requireOwned(userId, tradeId);
        if (tradeMapper.markCompleted(tradeId) == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前状态不能确认收货");
        }
    }

    public void ship(long tradeId, ShipRequest request) {
        TradeOrder trade = requireTrade(tradeId);
        if (!TradeOrder.STATUS_PAID.equals(trade.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只有已支付订单可以发货");
        }
        if (refundMapper.countPendingByTrade(tradeId) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单有待审批退款，处理后再发货");
        }
        long normalItems = itemMapper.selectCount(new QueryWrapper<TradeOrderItem>()
                .eq("trade_id", tradeId).eq("status", TradeOrderItem.STATUS_NORMAL));
        if (normalItems == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单商品已全部退款，不能发货");
        }
        if (tradeMapper.markShipped(tradeId, request.carrier(), request.trackingNo()) == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单状态已变化，请刷新后重试");
        }
    }

    public void markPaid(long tradeId, BigDecimal amount) {
        TradeOrder trade = tradeMapper.selectById(tradeId);
        if (trade == null) {
            log.warn("payment for unknown trade {} ignored", tradeId);
            return;
        }
        if (amount != null && trade.getPayableAmount().compareTo(amount) != 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "支付金额与订单应付金额不符");
        }
        if (TradeOrder.STATUS_CLOSED.equals(trade.getStatus())) {
            latePaymentRefund(trade);
            return;
        }
        if (TradeOrder.STATUS_CREATED.equals(trade.getStatus())) {
            if (tradeMapper.markPaid(tradeId, LocalDateTime.now()) > 0) {
                couponService.markUsed(tradeId);
            } else {
                trade = tradeMapper.selectById(tradeId);
                if (trade == null || TradeOrder.STATUS_CREATED.equals(trade.getStatus())) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单支付状态迁移失败");
                }
                if (TradeOrder.STATUS_CLOSED.equals(trade.getStatus())) {
                    latePaymentRefund(trade);
                    return;
                }
            }
        }
        if (!TradeOrder.SOURCE_SECKILL.equals(trade.getSource())
                && (trade.getStockConfirmed() == null || trade.getStockConfirmed() == 0)) {
            confirmStock(trade);
        }
    }

    public boolean closeIfPending(long tradeId) {
        TradeOrder trade = tradeMapper.selectById(tradeId);
        if (trade == null) {
            return false;
        }
        boolean newlyClosed = false;
        if (TradeOrder.STATUS_CREATED.equals(trade.getStatus())) {
            newlyClosed = tradeMapper.markClosed(tradeId) > 0;
            trade = tradeMapper.selectById(tradeId);
            if (trade == null || !TradeOrder.STATUS_CLOSED.equals(trade.getStatus())) {
                return false;
            }
        } else if (!TradeOrder.STATUS_CLOSED.equals(trade.getStatus())) {
            return false;
        }
        if (trade.getStockReleased() != null && trade.getStockReleased() == 1) {
            return newlyClosed;
        }
        if (TradeOrder.SOURCE_SECKILL.equals(trade.getSource())) {
            releaseSeckill(trade, "CLOSE");
        } else {
            inventoryClient.release(new InventoryClient.StockBatchRequest(tradeId, stockItems(tradeId)));
        }
        couponService.release(tradeId);
        tradeMapper.markStockReleased(tradeId);
        return newlyClosed;
    }

    public int autoComplete() {
        LocalDateTime before = LocalDateTime.now().minusDays(autoCompleteDays);
        int changed = 0;
        for (TradeOrder trade : tradeMapper.findShippedBefore(before)) {
            try {
                if (tradeMapper.markCompleted(trade.getId()) > 0) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.error("auto complete failed for trade {}", trade.getId(), e);
            }
        }
        return changed;
    }

    public TradePage adminPage(String status, String source, String keyword,
                               long current, long size) {
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long safeCurrent = Math.max(current, 1);
        QueryWrapper<TradeOrder> wrapper = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        if (source != null && !source.isBlank()) {
            wrapper.eq("source", source);
        }
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            wrapper.and(q -> q.like("trade_no", trimmed).or()
                    .like("receiver_name", trimmed).or().like("receiver_phone", trimmed));
        }
        long total = tradeMapper.selectCount(wrapper);
        long offset = offsetOrBeyond(safeCurrent, safeSize);
        List<TradeOrder> trades = offset >= total ? List.of()
                : tradeMapper.selectList(wrapper.orderByDesc("id")
                .last("LIMIT " + safeSize + " OFFSET " + offset));
        return new TradePage(rows(trades), total, safeCurrent, safeSize,
                (total + safeSize - 1) / safeSize);
    }

    public TradeView adminDetail(long tradeId) {
        return view(requireTrade(tradeId));
    }

    public AdminStats adminStats() {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        return new AdminStats(tradeMapper.countByStatus(TradeOrder.STATUS_PAID),
                refundMapper.countPending(), tradeMapper.countSince(since),
                tradeMapper.paidRevenueSince(since));
    }

    public List<AdminUserRow> adminUsers() {
        Result<List<UserAdminClient.UserView>> result = RemoteCall.invoke("用户", "运营用户列表",
                userAdminClient::users);
        List<UserAdminClient.UserView> users = RemoteCall.data(result, "用户");
        Map<Long, TradeOrderMapper.UserOrderStat> stats = tradeMapper.userStats().stream()
                .collect(Collectors.toMap(TradeOrderMapper.UserOrderStat::getUserId, Function.identity()));
        List<AdminUserRow> rows = new ArrayList<>();
        for (UserAdminClient.UserView user : users) {
            TradeOrderMapper.UserOrderStat stat = stats.get(user.id());
            rows.add(new AdminUserRow(user.id(), user.username(), user.nickname(), user.role(),
                    user.status(), user.createdAt(),
                    stat == null || stat.getOrderCount() == null ? 0 : stat.getOrderCount(),
                    stat == null ? null : stat.getLastOrderAt()));
        }
        return rows;
    }

    public TradeOrder requireTrade(long tradeId) {
        TradeOrder trade = tradeMapper.selectById(tradeId);
        if (trade == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return trade;
    }

    public TradeOrder requireOwned(long userId, long tradeId) {
        TradeOrder trade = requireTrade(tradeId);
        if (trade.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return trade;
    }

    public List<TradeOrderItem> items(long tradeId) {
        return itemMapper.selectList(new QueryWrapper<TradeOrderItem>()
                .eq("trade_id", tradeId).orderByAsc("id"));
    }

    private void confirmStock(TradeOrder trade) {
        if (TradeOrder.SOURCE_SECKILL.equals(trade.getSource())) {
            tradeMapper.markStockConfirmed(trade.getId());
            return;
        }
        List<InventoryClient.StockItem> items = stockItems(trade.getId());
        Result<Void> result = RemoteCall.invoke("库存", "确认订单 " + trade.getId(),
                () -> inventoryClient.confirm(
                        new InventoryClient.StockBatchRequest(trade.getId(), items)));
        RemoteCall.requireSuccess(result, "库存");
        tradeMapper.markStockConfirmed(trade.getId());
    }

    private void latePaymentRefund(TradeOrder trade) {
        Result<?> refund = RemoteCall.invoke("支付", "迟到支付退款 " + trade.getId(),
                () -> paymentClient.lateRefund(trade.getId()));
        RemoteCall.requireSuccess(refund, "支付");
        if (TradeOrder.SOURCE_SECKILL.equals(trade.getSource())) {
            releaseSeckill(trade, "LATE_PAYMENT");
        } else if (trade.getStockReleased() == null || trade.getStockReleased() == 0) {
            Result<Void> release = RemoteCall.invoke("库存", "释放迟到支付订单 " + trade.getId(),
                    () -> inventoryClient.release(new InventoryClient.StockBatchRequest(
                            trade.getId(), stockItems(trade.getId()))));
            RemoteCall.requireSuccess(release, "库存");
            tradeMapper.markStockReleased(trade.getId());
        }
        log.warn("late payment for closed trade {} refunded", trade.getId());
    }

    private void releaseSeckill(TradeOrder trade, String reason) {
        if (trade.getSeckillActivityId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "秒杀订单缺少活动号");
        }
        int quantity = items(trade.getId()).stream()
                .mapToInt(TradeOrderItem::getQuantity).sum();
        Result<Void> result = RemoteCall.invoke("秒杀", "释放活动库存 " + trade.getId(),
                () -> seckillClient.release(new SeckillClient.ReleaseRequest(
                        trade.getId(), trade.getSeckillActivityId(), quantity, reason)));
        RemoteCall.requireSuccess(result, "秒杀");
    }

    private TradeOrder buildTrade(long tradeId, long userId, String source,
                                  TradePricingService.Quote quote,
                                  AddressClient.AddressView address, Long userCouponId,
                                  Long couponTemplateId, Long seckillActivityId) {
        TradeOrder trade = new TradeOrder();
        trade.setId(tradeId);
        trade.setTradeNo("A" + tradeId);
        trade.setUserId(userId);
        trade.setSource(source);
        trade.setStatus(TradeOrder.STATUS_CREATED);
        trade.setRefundStatus(TradeOrder.REFUND_NONE);
        trade.setMerchandiseAmount(quote.merchandiseAmount());
        trade.setDiscountAmount(quote.discountAmount());
        trade.setShippingFee(quote.shippingFee());
        trade.setPayableAmount(quote.payableAmount());
        trade.setRefundedAmount(BigDecimal.ZERO.setScale(2));
        trade.setUserCouponId(userCouponId);
        trade.setCouponTemplateId(couponTemplateId);
        trade.setReceiverName(address.receiverName());
        trade.setReceiverPhone(address.phone());
        trade.setProvince(address.province());
        trade.setCity(address.city());
        trade.setDistrict(address.district());
        trade.setAddressDetail(address.detail());
        trade.setPostalCode(address.postalCode());
        trade.setSeckillActivityId(seckillActivityId);
        trade.setStockConfirmed(0);
        trade.setStockReleased(0);
        trade.setVersion(0);
        return trade;
    }

    private void insertItems(long tradeId, TradePricingService.Quote quote,
                             Map<Long, ProductSnapshot> snapshots) {
        for (TradePricingService.LineQuote line : quote.lines()) {
            ProductSnapshot snapshot = snapshots.get(line.skuId());
            if (snapshot == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "商品快照缺失");
            }
            TradeOrderItem item = new TradeOrderItem();
            item.setTradeId(tradeId);
            item.setSpuId(snapshot.spuId());
            item.setSkuId(line.skuId());
            item.setSpuTitle(snapshot.title());
            item.setSkuCode(snapshot.skuCode());
            item.setAttributesText(snapshot.attributesText());
            item.setImageUrl(snapshot.imageUrl());
            item.setUnitPrice(line.unitPrice());
            item.setQuantity(line.quantity());
            item.setItemAmount(line.itemAmount());
            item.setDiscountAmount(line.discountAmount());
            item.setRefundedAmount(BigDecimal.ZERO.setScale(2));
            item.setStatus(TradeOrderItem.STATUS_NORMAL);
            itemMapper.insert(item);
        }
    }

    private void insertIdempotency(long userId, String idempotencyKey, long tradeId,
                                   String requestHash) {
        OrderIdempotency mapping = new OrderIdempotency();
        mapping.setUserId(userId);
        mapping.setIdemKey(idempotencyKey);
        mapping.setTradeId(tradeId);
        mapping.setRequestHash(requestHash);
        try {
            idempotencyMapper.insert(mapping);
        } catch (DuplicateKeyException e) {
            throw new ConcurrentIdempotentRequest();
        }
    }

    private void insertCloseMessage(long tradeId) {
        TxMessage message = new TxMessage();
        message.setBizKey(String.valueOf(tradeId));
        message.setTopic(TradeTopics.TOPIC_TRADE);
        message.setTag(TradeTopics.TAG_ORDER_CLOSE_TIMEOUT);
        message.setPayload(String.valueOf(tradeId));
        message.setStatus(TxMessage.STATUS_PENDING);
        txMessageMapper.insert(message);
    }

    private void sendClose(long tradeId) {
        TxMessage message = txMessageMapper.findByBizKey(String.valueOf(tradeId),
                TradeTopics.TAG_ORDER_CLOSE_TIMEOUT);
        if (publisher.sendCloseTimeout(tradeId, closeDelayLevel) && message != null) {
            txMessageMapper.markSent(message.getId());
        }
    }

    private long replay(OrderIdempotency existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST, "该幂等键已用于另一笔下单");
        }
        return existing.getTradeId();
    }

    private void reserveStock(long tradeId, List<InventoryClient.StockItem> items) {
        Result<Void> result;
        try {
            result = RemoteCall.invoke("库存", "预扣订单 " + tradeId,
                    () -> inventoryClient.reserve(new InventoryClient.StockBatchRequest(tradeId, items)));
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SYSTEM_ERROR) {
                releaseStockQuietly(tradeId, items, "reserve outcome unknown");
            }
            throw e;
        }
        if (result.code() == ErrorCode.INVENTORY_INSUFFICIENT.getCode()) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        RemoteCall.requireSuccess(result, "库存");
    }

    private void releaseStockQuietly(long tradeId, List<InventoryClient.StockItem> items, String reason) {
        try {
            inventoryClient.release(new InventoryClient.StockBatchRequest(tradeId, items));
        } catch (RuntimeException e) {
            log.error("stock release compensation failed for trade {} ({})", tradeId, reason, e);
        }
    }

    private List<InventoryClient.StockItem> stockItems(long tradeId) {
        return items(tradeId).stream()
                .map(item -> new InventoryClient.StockItem(item.getSkuId(), item.getQuantity()))
                .toList();
    }

    private AddressClient.AddressView loadAddress(long userId, long addressId) {
        Result<AddressClient.AddressView> result = RemoteCall.invoke("用户", "地址 " + addressId,
                () -> addressClient.address(userId, addressId));
        return RemoteCall.data(result, "用户");
    }

    private AddressClient.AddressView loadDefaultAddress(long userId) {
        Result<AddressClient.AddressView> result = RemoteCall.invoke("用户", "默认地址",
                () -> addressClient.defaultAddress(userId));
        return RemoteCall.data(result, "用户");
    }

    private ProductSnapshot loadSnapshot(long skuId) {
        Result<ProductSnapshot> result = RemoteCall.invoke("商品", "SKU " + skuId,
                () -> productClient.snapshot(skuId));
        ProductSnapshot snapshot = RemoteCall.data(result, "商品");
        if (snapshot == null || snapshot.status() == null
                || snapshot.status() != ProductSnapshot.STATUS_ON_SALE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品不存在或已下架");
        }
        return snapshot;
    }

    private List<ProductSnapshot> loadSnapshots(java.util.Collection<Long> skuIds) {
        Result<List<ProductSnapshot>> result = RemoteCall.invoke("商品", skuIds.size() + " 个 SKU",
                () -> productClient.batch(new ArrayList<>(skuIds)));
        List<ProductSnapshot> snapshots = RemoteCall.data(result, "商品");
        return snapshots == null ? List.of() : snapshots;
    }

    private Map<Long, Integer> mergeItems(List<ItemRequest> items) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (ItemRequest item : items) {
            merged.merge(item.skuId(), item.quantity(), Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            if (entry.getValue() > 99) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "单个 SKU 最多购买 99 件");
            }
        }
        return merged;
    }

    private TradeView view(TradeOrder trade) {
        List<TradeOrderItem> items = items(trade.getId());
        List<OrderRefund> refunds = refundMapper.findByTrade(trade.getId());
        Map<Long, List<OrderRefundItem>> refundItems = refunds.isEmpty() ? Map.of()
                : refundItemMapper.selectList(new QueryWrapper<OrderRefundItem>().in("refund_id",
                                refunds.stream().map(OrderRefund::getId).toList()))
                        .stream().collect(Collectors.groupingBy(OrderRefundItem::getRefundId));
        boolean refundable = TradeOrder.STATUS_PAID.equals(trade.getStatus());
        List<TradeItemView> itemViews = items.stream()
                .map(item -> new TradeItemView(item.getId(), item.getSpuId(), item.getSkuId(),
                        item.getSpuTitle(), item.getSkuCode(), item.getAttributesText(),
                        item.getImageUrl(), item.getUnitPrice(), item.getQuantity(),
                        item.getItemAmount(), item.getDiscountAmount(), item.getRefundedAmount(),
                        item.getStatus(),
                        refundable && TradeOrderItem.STATUS_NORMAL.equals(item.getStatus())))
                .toList();
        List<RefundBrief> refundBriefs = refunds.stream()
                .map(refund -> new RefundBrief(refund.getId(), refund.getRefundNo(),
                        refund.getAmount(), refund.getStatus(), refund.getReason(),
                        refund.getRejectReason(), refund.getCreatedAt(), refund.getReviewedAt(),
                        refundItems.getOrDefault(refund.getId(), List.of()).stream()
                                .map(OrderRefundItem::getOrderItemId).toList()))
                .toList();
        AddressSnapshot address = new AddressSnapshot(trade.getReceiverName(), trade.getReceiverPhone(),
                trade.getProvince(), trade.getCity(), trade.getDistrict(),
                trade.getAddressDetail(), trade.getPostalCode());
        return new TradeView(trade.getId(), trade.getTradeNo(), trade.getUserId(),
                trade.getSource(), trade.getStatus(), trade.getRefundStatus(),
                trade.getMerchandiseAmount(), trade.getDiscountAmount(), trade.getShippingFee(),
                trade.getPayableAmount(), trade.getRefundedAmount(), trade.getUserCouponId(),
                couponService.titleOf(trade.getUserCouponId()), address, trade.getCarrier(),
                trade.getTrackingNo(), trade.getPaidAt(), trade.getShippedAt(),
                trade.getCompletedAt(), trade.getClosedAt(), trade.getCreatedAt(),
                itemViews, refundBriefs);
    }

    private List<TradeRow> rows(List<TradeOrder> trades) {
        if (trades.isEmpty()) {
            return List.of();
        }
        List<Long> tradeIds = trades.stream().map(TradeOrder::getId).toList();
        Map<Long, List<TradeOrderItem>> items = itemMapper.selectList(
                        new QueryWrapper<TradeOrderItem>().in("trade_id", tradeIds).orderByAsc("id"))
                .stream().collect(Collectors.groupingBy(TradeOrderItem::getTradeId));
        return trades.stream().map(trade -> new TradeRow(trade.getId(), trade.getTradeNo(),
                trade.getUserId(), trade.getSource(), trade.getStatus(), trade.getRefundStatus(),
                trade.getPayableAmount(), trade.getRefundedAmount(), trade.getReceiverName(),
                trade.getReceiverPhone(), trade.getCreatedAt(), trade.getPaidAt(),
                items.getOrDefault(trade.getId(), List.of()).stream()
                        .map(item -> new TradeItemPreview(item.getId(), item.getSkuId(),
                                item.getSpuTitle(), item.getAttributesText(), item.getImageUrl(),
                                item.getQuantity(), item.getUnitPrice(), item.getStatus()))
                        .toList())).toList();
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少 Idempotency-Key 请求头");
        }
        if (key.length() > 128) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Idempotency-Key 不能超过 128 个字符");
        }
    }

    private static String fingerprint(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long offsetOrBeyond(long current, long size) {
        try {
            return Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static final class ConcurrentIdempotentRequest extends RuntimeException {
    }
}
