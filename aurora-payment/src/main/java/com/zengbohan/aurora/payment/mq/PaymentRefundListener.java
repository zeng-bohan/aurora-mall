package com.zengbohan.aurora.payment.mq;

import com.zengbohan.aurora.common.mq.TradeTopics;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 迟到支付闭环的最后一环（外部审查一.2）：order 侧 markPaid 输给关单后发出的
 * 退款信号——payment 收到即把已 PAID 的支付单转为 REFUNDED。
 * <p>
 * 全链路语义：钱收了、单关了 → 库存已回滚（关单路径）→ 这里把钱退掉，
 * 三方对账收敛。幂等：守卫转移外重复消费只影响日志。
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = TradeTopics.TOPIC_TRADE,
        consumerGroup = "aurora-payment-refund-consumer",
        selectorExpression = TradeTopics.TAG_PAYMENT_REFUND)
public class PaymentRefundListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundListener.class);

    private final PaymentOrderMapper paymentOrderMapper;

    public PaymentRefundListener(PaymentOrderMapper paymentOrderMapper) {
        this.paymentOrderMapper = paymentOrderMapper;
    }

    @Override
    public void onMessage(String orderId) {
        long id = Long.parseLong(orderId);
        // PAID → REFUNDED（钱已收、单已关）；PAYING → REFUNDED 兜底覆盖
        int moved = paymentOrderMapper.markPaidRefunded(id);
        if (moved == 0) {
            moved = paymentOrderMapper.markRefunded(id);
        }
        if (moved > 0) {
            log.warn("refund signal applied: order {} payment -> REFUNDED", id);
        } else {
            log.info("refund signal for order {} had no refundable state (already refunded?)", id);
        }
    }
}
