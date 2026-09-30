package com.zengbohan.aurora.payment.mq;

import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * payment 侧 order-paid 事件的兜底补发：markPaid 与事件发出之间崩溃（且回调
 * 不再重试）会让支付单永久 PAID 而事件未发。扫描 PAID + 未发布 + 满 60s 的
 * 记录重发并打标——消费端 order/inventory 均幂等，重复投递安全。
 * （对称物是 order 的 TxMessageRetryJob。）
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
public class PaymentEventRetryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventRetryJob.class);

    private final PaymentOrderMapper paymentOrderMapper;
    private final PaymentService paymentService;

    public PaymentEventRetryJob(PaymentOrderMapper paymentOrderMapper,
                                PaymentService paymentService) {
        this.paymentOrderMapper = paymentOrderMapper;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${aurora.payment.event-retry-interval-ms:60000}",
            initialDelay = 90_000)
    public void republishUnpublished() {
        List<PaymentOrder> stale =
                paymentOrderMapper.findUnpublishedPaid(LocalDateTime.now().minusSeconds(60));
        for (PaymentOrder payment : stale) {
            try {
                log.warn("payment for order {} still unpublished, resending paid event", payment.getOrderId());
                // 复用业务侧构建路径：载荷形状与首发一致（消费端强类型绑定）
                paymentService.publishPaid(payment);
            } catch (RuntimeException e) {
                // 毒丸隔离：一条补发失败不能饿死本轮后续记录
                log.error("paid event republish failed for order {}", payment.getOrderId(), e);
            }
        }
    }
}
