package com.zengbohan.aurora.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.service.ProductAdminService;
import com.zengbohan.aurora.product.service.ProductQueryService;
import com.zengbohan.aurora.product.web.RequireAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/admin/products")
public class AdminProductController {

    private final ProductAdminService adminService;
    private final ProductQueryService queryService;

    public AdminProductController(ProductAdminService adminService,
                                  ProductQueryService queryService) {
        this.adminService = adminService;
        this.queryService = queryService;
    }

    public record CreateRequest(@NotBlank String title,
                                @DecimalMin("0.01") BigDecimal price,
                                int stock) {
    }

    // 管理端视图包含已下架商品，与公开列表不同。
    @GetMapping
    @RequireAdmin
    public Result<Page<Sku>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "10") long size) {
        return Result.ok(queryService.adminPage(current, size));
    }

    @PostMapping
    @RequireAdmin
    public Result<Long> create(@Valid @RequestBody CreateRequest request) {
        Sku sku = new Sku();
        sku.setTitle(request.title());
        sku.setPrice(request.price());
        sku.setStock(request.stock());
        sku.setStatus(ProductSnapshot.STATUS_ON_SALE);
        return Result.ok(adminService.create(sku));
    }

    @PutMapping("/{id}")
    @RequireAdmin
    public Result<Void> update(@PathVariable long id,
                               @Valid @RequestBody CreateRequest request) {
        Sku sku = new Sku();
        sku.setId(id);
        sku.setTitle(request.title());
        sku.setPrice(request.price());
        adminService.update(sku);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireAdmin
    public Result<Void> offShelf(@PathVariable long id) {
        adminService.changeStatus(id, ProductSnapshot.STATUS_OFF_SHELF);
        return Result.ok();
    }
}
