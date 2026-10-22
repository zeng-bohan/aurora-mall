package com.zengbohan.aurora.product.controller;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.product.dto.ProductDtos.AttributeView;
import com.zengbohan.aurora.product.dto.ProductDtos.CategoryView;
import com.zengbohan.aurora.product.dto.ProductDtos.PageResult;
import com.zengbohan.aurora.product.dto.ProductDtos.SpuCard;
import com.zengbohan.aurora.product.dto.ProductDtos.SpuDetail;
import com.zengbohan.aurora.product.service.ProductQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductQueryService queryService;

    public ProductController(ProductQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public Result<PageResult<SpuCard>> page(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String attrs,
            @RequestParam(defaultValue = "recommended") String sort,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "12") long size) {
        return Result.ok(queryService.page(categoryId, keyword, minPrice, maxPrice,
                attrs, sort, current, size, false));
    }

    @GetMapping("/categories")
    public Result<List<CategoryView>> categories() {
        return Result.ok(queryService.categories(false));
    }

    @GetMapping("/attributes")
    public Result<List<AttributeView>> attributes(@RequestParam(required = false) Long categoryId) {
        return Result.ok(queryService.attributes(categoryId, false));
    }

    @GetMapping("/batch")
    public Result<List<ProductSnapshot>> batch(@RequestParam List<Long> ids) {
        return Result.ok(queryService.batchSnapshots(ids));
    }

    @GetMapping("/skus/{id}")
    public Result<ProductSnapshot> snapshot(@PathVariable long id) {
        return Result.ok(queryService.snapshot(id));
    }

    @GetMapping("/{id}/related")
    public Result<List<SpuCard>> related(@PathVariable long id) {
        return Result.ok(queryService.related(id, 4));
    }

    @GetMapping("/{id}")
    public Result<SpuDetail> detail(@PathVariable long id) {
        return Result.ok(queryService.detail(id, false));
    }
}
