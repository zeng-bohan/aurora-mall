package com.zengbohan.aurora.product.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zengbohan.aurora.product.cache.ProductCacheService;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.mapper.SkuMapper;
import org.springframework.stereotype.Service;

@Service
public class ProductQueryService {

    private final SkuMapper skuMapper;
    private final ProductCacheService cacheService;

    public ProductQueryService(SkuMapper skuMapper, ProductCacheService cacheService) {
        this.skuMapper = skuMapper;
        this.cacheService = cacheService;
    }

    public Sku detail(long id) {
        return cacheService.getById(id, skuMapper::selectById);
    }

    public Page<Sku> page(long current, long size) {
        return skuMapper.selectPage(new Page<>(current, Math.min(size, 100)),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Sku>()
                        .eq(Sku::getStatus, 1)
                        .orderByDesc(Sku::getId));
    }
}
