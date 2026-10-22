package com.zengbohan.aurora.common.mq;

/**
 * 交易域与支付域 MQ topic/tag 的唯一事实源。
 */
public final class TradeTopics {

    public static final String TOPIC_TRADE = "aurora-trade";
    public static final String TAG_ORDER_CLOSE_TIMEOUT = "tag-order-close-timeout";

    public static final String TOPIC_PAYMENT = "aurora-payment";
    public static final String TAG_PAYMENT_PAID = "tag-payment-paid";

    private TradeTopics() {
    }
}
