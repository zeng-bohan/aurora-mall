package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.common.web.RequireAdmin;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端库存入口：把可售库存开出来。
 *
 * <p>库存原本只有 order 经 Feign 能写——{@code /stocks/**} 被
 * {@link com.zengbohan.aurora.inventory.web.StockUserIdentityGuardFilter} 守着，
 * 凡携带用户身份的请求一律 403，浏览器永远够不着。但运营建完商品后总得有个
 * HTTP 路径把库存开出来：商品表里的 stock 只是展示字段，真正决定能不能卖的
 * 是这里的 Redis + product_stock（M6 做前端时暴露出来的缺口，前端建的商品
 * 因此一直下不了单）。
 *
 * <p>所以单开一条 {@code /admin/stocks}：它不在 {@code /stocks/} 前缀下，
 * 不受那条身份守卫约束，但由 {@link RequireAdmin} 收口；服务本身仍然要求内部
 * 密钥（InternalSecretFilter），也就是只有「经网关、且角色为 ADMIN」的流量进得来。
 * 两条闸门一个都没少——放开的只是"供应商"这条腿，不是"任意登录用户"。
 */
@RestController
@RequestMapping("/admin/stocks")
@RequireAdmin
public class AdminStockController {

    /** 可售数量。available 为 null 表示这个 SKU 还没开过库存（不是卖完了）。 */
    public record StockView(long skuId, Integer available) {
    }

    private final StockService stockService;

    public AdminStockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/{skuId}")
    public Result<StockView> get(@PathVariable long skuId) {
        return Result.ok(new StockView(skuId, stockService.sellableStock(skuId)));
    }

    /** 与内部播种端点同语义：把可售数量置为给定值（允许 0，用于临时关卖）。 */
    @PutMapping("/{skuId}")
    public Result<Void> set(@PathVariable long skuId,
                            @Valid @RequestBody StockController.StockRequest request) {
        stockService.setStock(skuId, request.quantity());
        return Result.ok();
    }
}
