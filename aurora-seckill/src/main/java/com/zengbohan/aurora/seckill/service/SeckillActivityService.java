package com.zengbohan.aurora.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.client.ProductClient;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillStock;
import com.zengbohan.aurora.seckill.mapper.SeckillActivityMapper;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class SeckillActivityService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockMapper stockMapper;
    private final ProductClient productClient;

    public SeckillActivityService(SeckillActivityMapper activityMapper,
                                  SeckillStockMapper stockMapper, ProductClient productClient) {
        this.activityMapper = activityMapper;
        this.stockMapper = stockMapper;
        this.productClient = productClient;
    }

    @Transactional
    public SeckillActivity create(String title, long skuId, BigDecimal seckillPrice,
                                  int totalStock, int perUserLimit,
                                  LocalDateTime startAt, LocalDateTime endAt) {
        if (title == null || title.isBlank() || title.length() > 64) {
            fail("活动标题必填且不超过 64 字");
        }
        if (skuId <= 0) {
            fail("skuId 必须为正数");
        }
        if (seckillPrice == null || seckillPrice.signum() <= 0) {
            fail("活动价必须大于 0");
        }
        if (totalStock <= 0) {
            fail("活动总量必须大于 0");
        }
        if (perUserLimit != 1) {
            fail("当前实现只支持每人限购 1 件");
        }
        if (startAt == null || endAt == null) {
            fail("活动起止时间必填");
        }
        if (!startAt.isBefore(endAt)) {
            fail("开始时间必须早于结束时间");
        }
        if (endAt.isBefore(now())) {
            fail("结束时间不能在过去");
        }
        ProductSnapshot product = loadProduct(skuId);
        SeckillActivity activity = new SeckillActivity();
        activity.setTitle(title.trim());
        activity.setSpuId(product.spuId());
        activity.setSkuId(skuId);
        activity.setProductTitle(product.title());
        activity.setProductImage(product.imageUrl());
        activity.setOriginalPrice(product.price());
        activity.setSeckillPrice(seckillPrice);
        activity.setTotalStock(totalStock);
        activity.setPerUserLimit(perUserLimit);
        activity.setStartAt(startAt);
        activity.setEndAt(endAt);
        activityMapper.insert(activity);

        SeckillStock stock = new SeckillStock();
        stock.setActivityId(activity.getId());
        stock.setTotal(totalStock);
        stock.setAvailable(totalStock);
        stockMapper.insert(stock);
        return activity;
    }

    public SeckillActivity require(long id) {
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return activity;
    }

    public List<SeckillActivityView> listNotEndedViews() {
        LocalDateTime now = now();
        List<SeckillActivity> activities = new ArrayList<>(activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().gt(SeckillActivity::getEndAt, now)));
        activities.sort(Comparator
                .comparingInt((SeckillActivity a) -> phaseOf(a, now) == SeckillPhase.RUNNING ? 0 : 1)
                .thenComparing(SeckillActivity::getStartAt));
        return activities.stream().map(a -> toView(a, now)).toList();
    }

    public SeckillActivityView viewOf(long id) {
        return toView(require(id), now());
    }

    /** 运营视图保留已结束活动，便于查看历史与库存结果。 */
    public List<SeckillActivityView> adminViews() {
        LocalDateTime now = now();
        List<SeckillActivity> activities = new ArrayList<>(activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().orderByDesc(SeckillActivity::getStartAt)));
        return activities.stream().map(a -> toView(a, now)).toList();
    }

    public int availableStock(long activityId) {
        SeckillStock stock = stockMapper.selectById(activityId);
        return stock == null ? 0 : stock.getAvailable();
    }

    LocalDateTime now() {
        return LocalDateTime.now();
    }

    private SeckillActivityView toView(SeckillActivity activity, LocalDateTime now) {
        return new SeckillActivityView(activity.getId(), activity.getTitle(), activity.getSpuId(),
                activity.getSkuId(), activity.getProductTitle(), activity.getProductImage(),
                activity.getOriginalPrice(), activity.getSeckillPrice(),
                activity.getTotalStock(), activity.getPerUserLimit(),
                activity.getStartAt(), activity.getEndAt(), phaseOf(activity, now).name(),
                availableStock(activity.getId()));
    }

    private static SeckillPhase phaseOf(SeckillActivity activity, LocalDateTime now) {
        return SeckillPhase.of(activity.getStartAt(), activity.getEndAt(), now);
    }

    private ProductSnapshot loadProduct(long skuId) {
        Result<ProductSnapshot> result = RemoteCall.invoke("商品", "SKU " + skuId,
                () -> productClient.snapshot(skuId));
        ProductSnapshot snapshot = RemoteCall.data(result, "商品");
        if (snapshot == null || snapshot.status() == null
                || snapshot.status() != ProductSnapshot.STATUS_ON_SALE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品不存在或已下架");
        }
        return snapshot;
    }

    private static void fail(String message) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    public record SeckillActivityView(long id, String title, long spuId, long skuId,
                                      String productTitle, String productImage,
                                      BigDecimal originalPrice, BigDecimal seckillPrice,
                                      int totalStock, int perUserLimit,
                                      LocalDateTime startAt, LocalDateTime endAt,
                                      String phase, int availableStock) {
    }
}
