package com.zengbohan.aurora.common.mq;

/**
 * 交易域 MQ topic/tag 的唯一事实源：生产侧与消费侧 @RocketMQMessageListener
 * 都从这里静态导入——注解要求编译期常量，字面量只在本文件出现一次。
 */
public final class TradeTopics {

    public static final String TOPIC_TRADE = "aurora-trade";
    public static final String TAG_STOCK_RESERVED = "tag-stock-reserved";
    public static final String TAG_ORDER_CLOSE_TIMEOUT = "tag-order-close-timeout";
    public static final String TAG_ORDER_PAID = "tag-order-paid";

    private TradeTopics() {
    }
}
