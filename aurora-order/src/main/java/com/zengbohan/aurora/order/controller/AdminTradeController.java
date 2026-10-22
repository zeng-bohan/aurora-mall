package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.common.web.RequireAdmin;
import com.zengbohan.aurora.order.dto.TradeDtos.AdminStats;
import com.zengbohan.aurora.order.dto.TradeDtos.AdminUserRow;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundPage;
import com.zengbohan.aurora.order.dto.TradeDtos.RefundView;
import com.zengbohan.aurora.order.dto.TradeDtos.ShipRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.TradePage;
import com.zengbohan.aurora.order.dto.TradeDtos.TradeView;
import com.zengbohan.aurora.order.service.RefundService;
import com.zengbohan.aurora.order.service.TradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/admin")
@RequireAdmin
public class AdminTradeController {

    private final TradeService tradeService;
    private final RefundService refundService;

    public AdminTradeController(TradeService tradeService, RefundService refundService) {
        this.tradeService = tradeService;
        this.refundService = refundService;
    }

    @GetMapping("/stats")
    public Result<AdminStats> stats() {
        return Result.ok(tradeService.adminStats());
    }

    @GetMapping("/trades")
    public Result<TradePage> trades(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "20") long size) {
        return Result.ok(tradeService.adminPage(status, source, keyword, current, size));
    }

    @GetMapping("/trades/{id}")
    public Result<TradeView> trade(@PathVariable long id) {
        return Result.ok(tradeService.adminDetail(id));
    }

    @PostMapping("/trades/{id}/ship")
    public Result<Void> ship(@PathVariable long id, @Valid @RequestBody ShipRequest request) {
        tradeService.ship(id, request);
        return Result.ok();
    }

    @GetMapping("/refunds")
    public Result<RefundPage> refunds(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size) {
        return Result.ok(refundService.adminPage(status, current, size));
    }

    @GetMapping("/refunds/{id}")
    public Result<RefundView> refund(@PathVariable long id) {
        return Result.ok(refundService.view(id));
    }

    @PostMapping("/refunds/{id}/approve")
    public Result<RefundView> approve(@PathVariable long id,
                                      @RequestHeader(value = "X-User-Id", required = false) String admin) {
        return Result.ok(refundService.approve(id, admin == null ? "admin" : admin));
    }

    @PostMapping("/refunds/{id}/reject")
    public Result<RefundView> reject(@PathVariable long id,
                                     @Valid @RequestBody RejectRequest request,
                                     @RequestHeader(value = "X-User-Id", required = false) String admin) {
        return Result.ok(refundService.reject(id, request.reason(),
                admin == null ? "admin" : admin));
    }

    @GetMapping("/users")
    public Result<List<AdminUserRow>> users() {
        return Result.ok(tradeService.adminUsers());
    }

    public record RejectRequest(@NotBlank String reason) {
    }
}
