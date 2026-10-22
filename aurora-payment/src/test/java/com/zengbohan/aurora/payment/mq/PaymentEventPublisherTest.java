package com.zengbohan.aurora.payment.mq;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 支付事件不能静默丢弃：没有 MQ 模板时必须抛出，让 publishPaid 不落
 * event_published 标记，补发 job 才会继续重试。
 */
class PaymentEventPublisherTest {

    @Test
    void missingTemplateThrowsInsteadOfPretendingToSend() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RocketMQTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        PaymentEventPublisher publisher = new PaymentEventPublisher(provider);

        assertThatThrownBy(() -> publisher.sendOrderPaid("1001", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void missingSendStatusThrows() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        when(template.syncSend(anyString(), any(org.springframework.messaging.Message.class)))
                .thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<RocketMQTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        PaymentEventPublisher publisher = new PaymentEventPublisher(provider);

        assertThatThrownBy(() -> publisher.sendOrderPaid("1001", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no send status");
    }
}
