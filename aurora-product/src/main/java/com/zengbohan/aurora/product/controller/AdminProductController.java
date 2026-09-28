package com.zengbohan.aurora.product.controller;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.service.ProductAdminService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/admin/products")
public class AdminProductController {

    private final ProductAdminService adminService;

    public AdminProductController(ProductAdminService adminService) {
        this.adminService = adminService;
    }

    public record CreateRequest(@NotBlank String title,
                                @DecimalMin("0.01") BigDecimal price,
                                int stock) {
    }

    @PostMapping
    public Result<Long> create(@RequestHeader(value = "X-User-Role", required = false) String role,
                               @RequestBody CreateRequest request) {
        requireAdmin(role);
        Sku sku = new Sku();
        sku.setTitle(request.title());
        sku.setPrice(request.price());
        sku.setStock(request.stock());
        sku.setStatus(1);
        return Result.ok(adminService.create(sku));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@RequestHeader(value = "X-User-Role", required = false) String role,
                               @PathVariable long id,
                               @RequestBody CreateRequest request) {
        requireAdmin(role);
        Sku sku = new Sku();
        sku.setId(id);
        sku.setTitle(request.title());
        sku.setPrice(request.price());
        adminService.update(sku);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> offShelf(@RequestHeader(value = "X-User-Role", required = false) String role,
                                 @PathVariable long id) {
        requireAdmin(role);
        adminService.changeStatus(id, 0);
        return Result.ok();
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
