package com.zengbohan.aurora.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
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

/**
 * 秒杀活动：创建（运营）与只读视图。
 * <p>
 * 校验全部收在服务层（不依赖 web 层注解），保证任何调用方都过同一套规则；
 * 活动与其库存行在同一事务里写入，避免出现"有活动无库存"的半开状态。
 */
@Service
public class SeckillActivityService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockMapper stockMapper;

    public SeckillActivityService(SeckillActivityMapper activityMapper, SeckillStockMapper stockMapper) {
        this.activityMapper = activityMapper;
        this.stockMapper = stockMapper;
    }

    /**
     * 创建活动 + 初始库存行（同一事务）。校验失败抛 {@link ErrorCode#PARAM_ERROR}。
     */
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
            // 一人一单由 Redis 已购集合 + 订单唯一键共同保证，当前不支持限购 N 件：
            // 与其放行一个不会生效的配置（用户仍只能买 1 件），不如在这里明确拒绝
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

        SeckillActivity activity = new SeckillActivity();
        activity.setTitle(title.trim());
        activity.setSkuId(skuId);
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

    /** 单个活动（不存在抛 NOT_FOUND）。 */
    public SeckillActivity require(long id) {
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return activity;
    }

    /** 未结束活动的用户视图（进行中在前，含 DB 余量）。 */
    public List<SeckillActivityView> listNotEndedViews() {
        LocalDateTime now = now();
        // 拷进可变列表再排序：不假设 mapper 返回的列表可变（MyBatis 给 ArrayList，mock 可能给不可变列表）
        List<SeckillActivity> activities = new ArrayList<>(activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().gt(SeckillActivity::getEndAt, now)));
        activities.sort(Comparator
                .comparingInt((SeckillActivity a) -> phaseOf(a, now) == SeckillPhase.RUNNING ? 0 : 1)
                .thenComparing(SeckillActivity::getStartAt));
        List<SeckillActivityView> views = new ArrayList<>(activities.size());
        for (SeckillActivity activity : activities) {
            views.add(toView(activity, now));
        }
        return views;
    }

    /** 单个活动的用户视图（不存在抛 NOT_FOUND）。 */
    public SeckillActivityView viewOf(long id) {
        return toView(require(id), now());
    }

    /** 活动 DB 侧剩余库存（Redis 快照见预热服务）。 */
    public int availableStock(long activityId) {
        SeckillStock stock = stockMapper.selectById(activityId);
        return stock == null ? 0 : stock.getAvailable();
    }

    // 当前时间（测试钩子：匿名子类固定时间，把阶段推导测成确定性的）。
    LocalDateTime now() {
        return LocalDateTime.now();
    }

    private SeckillActivityView toView(SeckillActivity activity, LocalDateTime now) {
        return new SeckillActivityView(activity.getId(), activity.getTitle(), activity.getSkuId(),
                activity.getSeckillPrice(), activity.getTotalStock(), activity.getPerUserLimit(),
                activity.getStartAt(), activity.getEndAt(),
                phaseOf(activity, now).name(), availableStock(activity.getId()));
    }

    private static SeckillPhase phaseOf(SeckillActivity activity, LocalDateTime now) {
        return SeckillPhase.of(activity.getStartAt(), activity.getEndAt(), now);
    }

    private static void fail(String message) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 活动的用户视图：DB 余量 + 由时间窗推导的阶段。
     */
    public record SeckillActivityView(long id, String title, long skuId, BigDecimal seckillPrice,
                                      int totalStock, int perUserLimit,
                                      LocalDateTime startAt, LocalDateTime endAt,
                                      String phase, int availableStock) {
    }
}
