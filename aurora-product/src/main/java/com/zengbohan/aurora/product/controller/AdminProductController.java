package com.zengbohan.aurora.product.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.common.web.RequireAdmin;
import com.zengbohan.aurora.product.dto.ProductDtos;
import com.zengbohan.aurora.product.dto.ProductDtos.AttributeView;
import com.zengbohan.aurora.product.dto.ProductDtos.CategoryView;
import com.zengbohan.aurora.product.dto.ProductDtos.PageResult;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveAttributeRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveAttributeValueRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveCategoryRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SaveSpuRequest;
import com.zengbohan.aurora.product.dto.ProductDtos.SpuCard;
import com.zengbohan.aurora.product.dto.ProductDtos.SpuDetail;
import com.zengbohan.aurora.product.service.ProductAdminService;
import com.zengbohan.aurora.product.service.ProductQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequireAdmin
public class AdminProductController {

    private final ProductAdminService adminService;
    private final ProductQueryService queryService;

    public AdminProductController(ProductAdminService adminService, ProductQueryService queryService) {
        this.adminService = adminService;
        this.queryService = queryService;
    }

    @GetMapping("/stats")
    public Result<ProductDtos.CatalogStats> stats() {
        return Result.ok(queryService.catalogStats());
    }

    @GetMapping("/skus")
    public Result<java.util.List<ProductDtos.AdminSkuRow>> skus(
            @RequestParam(required = false) String keyword) {
        return Result.ok(queryService.adminSkus(keyword));
    }

    @GetMapping("/products")
    public Result<PageResult<SpuCard>> page(@RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(queryService.page(categoryId, keyword, null, null, null,
                "recommended", current, size, true));
    }

    @GetMapping("/products/{id}")
    public Result<SpuDetail> detail(@PathVariable long id) {
        return Result.ok(queryService.detail(id, true));
    }

    @PostMapping("/products")
    public Result<Long> create(@Valid @RequestBody SaveSpuRequest request) {
        return Result.ok(adminService.save(new SaveSpuRequest(null, request.categoryId(),
                request.title(), request.subtitle(), request.description(),
                request.attributeIds(), request.attributeValues(), request.specs(),
                request.skus(), request.status())));
    }

    @PutMapping("/products/{id}")
    public Result<Long> update(@PathVariable long id, @Valid @RequestBody SaveSpuRequest request) {
        return Result.ok(adminService.save(new SaveSpuRequest(id, request.categoryId(),
                request.title(), request.subtitle(), request.description(),
                request.attributeIds(), request.attributeValues(), request.specs(),
                request.skus(), request.status())));
    }

    @PutMapping("/products/{id}/status")
    public Result<Void> status(@PathVariable long id, @RequestParam int status) {
        adminService.changeStatus(id, status);
        return Result.ok();
    }

    @GetMapping("/categories")
    public Result<List<CategoryView>> categories() {
        return Result.ok(queryService.categories(true));
    }

    @PostMapping("/categories")
    public Result<Long> createCategory(@Valid @RequestBody SaveCategoryRequest request) {
        return Result.ok(adminService.createCategory(request));
    }

    @PutMapping("/categories/{id}")
    public Result<Void> updateCategory(@PathVariable long id,
                                       @Valid @RequestBody SaveCategoryRequest request) {
        adminService.updateCategory(id, request);
        return Result.ok();
    }

    @GetMapping("/attributes")
    public Result<List<AttributeView>> attributes() {
        return Result.ok(queryService.attributes(null, true));
    }

    @PostMapping("/attributes")
    public Result<Long> createAttribute(@Valid @RequestBody SaveAttributeRequest request) {
        return Result.ok(adminService.createAttribute(request));
    }

    @PutMapping("/attributes/{id}")
    public Result<Void> updateAttribute(@PathVariable long id,
                                        @Valid @RequestBody SaveAttributeRequest request) {
        adminService.updateAttribute(id, request);
        return Result.ok();
    }

    @PostMapping("/attributes/{id}/values")
    public Result<Long> addValue(@PathVariable long id,
                                 @Valid @RequestBody SaveAttributeValueRequest request) {
        return Result.ok(adminService.addValue(id, request));
    }

    @PutMapping("/attributes/values/{valueId}")
    public Result<Void> updateValue(@PathVariable long valueId,
                                    @Valid @RequestBody SaveAttributeValueRequest request) {
        adminService.updateValue(valueId, request);
        return Result.ok();
    }
}
