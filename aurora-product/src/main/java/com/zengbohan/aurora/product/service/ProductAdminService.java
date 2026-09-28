package com.zengbohan.aurora.product.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.product.cache.ProductCacheService;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.mapper.SkuMapper;
import org.springframework.stereotype.Service;

@Service
public class ProductAdminService {

    private final SkuMapper skuMapper;
    private final ProductCacheService cacheService;

    public ProductAdminService(SkuMapper skuMapper, ProductCacheService cacheService) {
        this.skuMapper = skuMapper;
        this.cacheService = cacheService;
    }

    public Long create(Sku sku) {
        skuMapper.insert(sku);
        cacheService.bloomPut(sku.getId());
        return sku.getId();
    }

    public void update(Sku sku) {
        requireExists(sku.getId());
        cacheService.doubleDeleteAfterUpdate(sku.getId(), () -> skuMapper.updateById(sku));
    }

    public void changeStatus(long id, int status) {
        requireExists(id);
        cacheService.doubleDeleteAfterUpdate(id, () -> {
            Sku patch = new Sku();
            patch.setId(id);
            patch.setStatus(status);
            skuMapper.updateById(patch);
        });
    }

    private void requireExists(long id) {
        if (skuMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }
}
