package com.zengbohan.aurora.seckill.mq;

/**
 * 异步落单消息：只带三个 id，不带价格/库存——消费者自己去读活动。
 * 这样请求路径上完全不碰 DB，峰值流量也就不会顺着消息压到 DB 上。
 * <p>
 * {@code messageId} 是发送时生成的唯一值，同时作为 broker 的消息 key 与消费端的去重键：
 * 同一条消息的重投递共享它（会被 DB_DEDUP 挡住），而用户失败后重新抢购是一次新的发送
 * （新的 messageId，不会被去重挡住）。
 */
public record SeckillOrderEvent(String messageId, long activityId, long userId) {
}
