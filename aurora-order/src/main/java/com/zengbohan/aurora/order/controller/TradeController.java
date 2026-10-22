package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.order.dto.TradeDtos.PlaceTradeRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundApplyRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundApplyResult;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundPage;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundQuote;
import com.zengbohan.aurora.order.dto.TradeDtos.TradePage;
import com.zengbohan.aurora.order.dto.TradeDtos.TradeView;
import com.zengbohan.aurora.order.service.RefundService;
import com.zengbohan.aurora.order.service.TradeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/trades")
public class TradeController {

    private final TradeService tradeService;
    private final RefundService refundService;

    public TradeController(TradeService tradeService, RefundService refundService) {
        this.tradeService = tradeService;
        this.refundService = refundService;
    }

    @PostMapping
    public Result<Long> place(@RequestHeader("X-User-Id") long userId,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @Valid @RequestBody PlaceTradeRequest request) {
        return Result.ok(tradeService.place(userId, request, idempotencyKey));
    }

    @GetMapping
    public Result<TradePage> list(@RequestHeader("X-User-Id") long userId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "10") long size) {
        return Result.ok(tradeService.listTrades(userId, current, size, status));
    }

    @GetMapping("/{id}")
    public Result<TradeView> detail(@RequestHeader("X-User-Id") long userId,
                                    @PathVariable long id) {
        return Result.ok(tradeService.getTrade(userId, id));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@RequestHeader("X-User-Id") long userId, @PathVariable long id) {
        tradeService.cancel(userId, id);
        return Result.ok();
    }

    @PostMapping("/{id}/confirm")
    public Result<Void> confirm(@RequestHeader("X-User-Id") long userId, @PathVariable long id) {
        tradeService.confirmReceipt(userId, id);
        return Result.ok();
    }

    @GetMapping("/{id}/refunds/quote")
    public Result<RefundQuote> quote(@RequestHeader("X-User-Id") long userId,
                                     @PathVariable long id,
                                     @RequestParam List<Long> itemIds) {
        return Result.ok(refundService.quote(userId, id, itemIds));
    }

    @PostMapping("/{id}/refunds")
    public Result<RefundApplyResult> refund(@RequestHeader("X-User-Id") long userId,
                                            @PathVariable long id,
                                            @Valid @RequestBody RefundApplyRequest request) {
        return Result.ok(refundService.apply(userId, id, request));
    }

    @GetMapping("/refunds/mine")
    public Result<RefundPage> myRefunds(@RequestHeader("X-User-Id") long userId,
                                        @RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "10") long size) {
        return Result.ok(refundService.myRefunds(userId, current, size));
    }
}
