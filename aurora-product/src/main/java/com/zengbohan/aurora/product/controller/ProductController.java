package com.zengbohan.aurora.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.service.ProductQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {

    private final ProductQueryService queryService;

    public ProductController(ProductQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/products/{id}")
    public Result<Sku> detail(@PathVariable long id) {
        return Result.ok(queryService.detail(id));
    }

    @GetMapping("/products")
    public Result<Page<Sku>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "10") long size) {
        return Result.ok(queryService.page(current, size));
    }
}
