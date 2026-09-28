package com.zengbohan.aurora.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zengbohan.aurora.product.cache.ProductCacheService;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.mapper.SkuMapper;
import org.springframework.stereotype.Service;

import java.util.List;

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
                new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getStatus, 1)
                        .orderByDesc(Sku::getId));
    }

    /** Batch lookup for cart snapshots; unknown ids are simply absent from the result. */
    public List<Sku> batch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>().in(Sku::getId, ids));
    }
}
