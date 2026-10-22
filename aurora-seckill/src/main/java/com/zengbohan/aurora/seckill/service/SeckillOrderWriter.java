package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.DedupStore;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillOrder;
import com.zengbohan.aurora.seckill.mapper.SeckillOrderMapper;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 落单写入。单独成 Bean 是为了拿到 {@code @Transactional} 代理（同类自调用不走代理）：
 * 预扣必须在事务之外先完成，不能让 DB 连接被 Redis 往返占住。
 * <p>
 * 消息去重与订单写入必须在同一个事务里：先插去重行、再写订单/扣 DB 库存。
 * 任一步失败整体回滚；MQ 重投递时去重行也已回滚，仍能重新处理。
 */
@Service
public class SeckillOrderWriter {

    static final String STATUS_CREATED = "CREATED";
    static final String DEDUP_BIZ_TYPE = "seckill-order";

    private final SeckillOrderMapper orderMapper;
    private final SeckillStockMapper stockMapper;
    private final DedupStore dedupStore;

    public SeckillOrderWriter(SeckillOrderMapper orderMapper, SeckillStockMapper stockMapper,
                              DedupStore dedupStore) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.dedupStore = dedupStore;
    }

    /** 消息落单结果：duplicate=true 表示这条 messageId 已经处理过，orderId 只在非重复时有意义。 */
    public record PlaceOutcome(boolean duplicate, long orderId) {
    }

    /**
     * 消息消费入口：去重行与订单、DB 库存扣减共享同一事务。
     * 去重行插入失败说明同一消息已成功落单（事务一起提交的），调用方只需回查订单。
     */
    @Transactional
    public PlaceOutcome placeOnce(String messageId, SeckillActivity activity, long userId) {
        if (!dedupStore.tryInsert(DEDUP_BIZ_TYPE, messageId)) {
            return new PlaceOutcome(true, 0L);
        }
        return new PlaceOutcome(false, insertOrder(activity, userId));
    }

    /**
     * 事务内：写订单 + 扣 DB 库存（DB 是最终事实）。任一失败整体回滚，由调用方补偿 Redis 预扣。
     *
     * @throws org.springframework.dao.DuplicateKeyException 该用户在此活动下已有订单（唯一键兜底）
     * @throws BusinessException                             DB 库存已尽（{@link ErrorCode#SECKILL_SOLD_OUT}）
     */
    @Transactional
    public long place(SeckillActivity activity, long userId) {
        return insertOrder(activity, userId);
    }

    private long insertOrder(SeckillActivity activity, long userId) {
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
