package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.api.order.TradeSummary;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.order.dto.TradeDtos.PlaceSeckillRequest;
import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.entity.TradeOrderItem;
import com.zengbohan.aurora.order.mapper.TradeOrderMapper;
import com.zengbohan.aurora.order.service.TradeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TradeInternalController {

    private final TradeService tradeService;
    private final TradeOrderMapper tradeMapper;

    public TradeInternalController(TradeService tradeService, TradeOrderMapper tradeMapper) {
        this.tradeService = tradeService;
        this.tradeMapper = tradeMapper;
    }

    @GetMapping("/internal/trades/{id}")
    public Result<TradeSummary> summary(@PathVariable long id) {
        TradeOrder trade = tradeService.requireTrade(id);
        List<TradeOrderItem> items = tradeService.items(id);
        return Result.ok(new TradeSummary(trade.getId(), trade.getTradeNo(), trade.getUserId(),
                trade.getSource(), trade.getStatus(), trade.getPayableAmount(),
                trade.getRefundedAmount(),
                items.stream().map(item -> new TradeSummary.TradeLine(item.getSkuId(),
                        item.getSpuId(), item.getSpuTitle(), item.getQuantity(),
                        item.getUnitPrice(), item.getItemAmount())).toList()));
    }

    @PostMapping("/internal/trades/seckill")
    public Result<Long> placeSeckill(@Valid @RequestBody PlaceSeckillRequest request) {
        return Result.ok(tradeService.placeSeckill(request));
    }

    @GetMapping("/internal/trades/seckill")
    public Result<Long> findSeckill(@RequestParam long activityId, @RequestParam long userId) {
        TradeOrder trade = tradeMapper.findSeckillTrade(userId, activityId);
        if (trade == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return Result.ok(trade.getId());
    }
}
