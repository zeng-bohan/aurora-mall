package com.zengbohan.aurora.seckill.mq;

/**
 * 秒杀域 MQ topic/tag 的唯一事实源：生产侧与消费侧 {@code @RocketMQMessageListener}
 * 都从这里取——注解要求编译期常量，字面量只在本文件出现一次。
 */
public final class SeckillTopics {

    public static final String TOPIC_SECKILL = "aurora-seckill";
    public static final String TAG_SECKILL_ORDER = "tag-seckill-order";

    private SeckillTopics() {
    }
}
