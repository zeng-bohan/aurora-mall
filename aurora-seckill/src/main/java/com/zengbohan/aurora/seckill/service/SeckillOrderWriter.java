package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillOrder;
import com.zengbohan.aurora.seckill.mapper.SeckillOrderMapper;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 落单写入。单独成 Bean 是为了拿到 {@code @Transactional} 代理（同类自调用不走代理）：
 * 预扣必须在事务之外先完成，不能让 DB 连接被 Redis 往返占住。
 */
@Service
public class SeckillOrderWriter {

    static final String STATUS_CREATED = "CREATED";

    private final SeckillOrderMapper orderMapper;
    private final SeckillStockMapper stockMapper;

    public SeckillOrderWriter(SeckillOrderMapper orderMapper, SeckillStockMapper stockMapper) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
    }

    /**
     * 事务内：写订单 + 扣 DB 库存（DB 是最终事实）。任一失败整体回滚，由调用方补偿 Redis 预扣。
     *
     * @throws org.springframework.dao.DuplicateKeyException 该用户在此活动下已有订单（唯一键兜底）
     * @throws BusinessException                             DB 库存已尽（{@link ErrorCode#SECKILL_SOLD_OUT}）
     */
    @Transactional
    public long place(SeckillActivity activity, long userId) {
        SeckillOrder order = new SeckillOrder();
        order.setActivityId(activity.getId());
        order.setUserId(userId);
        order.setSkuId(activity.getSkuId());
        order.setPrice(activity.getSeckillPrice());
        order.setStatus(STATUS_CREATED);
        orderMapper.insert(order);

        if (stockMapper.deduct(activity.getId()) != 1) {
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT, "DB 库存已尽");
        }
        return order.getId();
    }
}
