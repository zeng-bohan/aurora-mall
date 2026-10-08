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

    // 管理端视图：包含所有状态，让已下架商品仍可管理。
    public Page<Sku> adminPage(long current, long size) {
        return skuMapper.selectPage(new Page<>(current, Math.min(size, 100)),
                new LambdaQueryWrapper<Sku>().orderByDesc(Sku::getId));
    }

    // 购物车快照的批量查询；未知 id 直接不出现在结果里。
    public List<Sku> batch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>().in(Sku::getId, ids));
    }
}
