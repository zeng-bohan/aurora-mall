package com.zengbohan.aurora.payment.mq;

import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 补发 job：PAID 未发布的记录被重发并打标；毒丸不饿死后续；无待补发不动作。 */
class PaymentEventRetryJobTest {

    private PaymentOrderMapper mapper;
    private PaymentService paymentService;
    private PaymentEventRetryJob job;

    @BeforeEach
    void setUp() {
        mapper = mock(PaymentOrderMapper.class);
        paymentService = mock(PaymentService.class);
        job = new PaymentEventRetryJob(mapper, paymentService);
    }

    private PaymentOrder paid(long orderId) {
        PaymentOrder payment = new PaymentOrder();
        payment.setOrderId(orderId);
        payment.setStatus(PaymentOrder.STATUS_PAID);
        return payment;
    }

    @Test
    void unpublishedPaidRecordIsRepublishedThroughServicePath() {
        when(mapper.findUnpublishedPaid(any(LocalDateTime.class))).thenReturn(List.of(paid(1001L)));

        job.republishUnpublished();

        // 打标发生在 service.publishPaid 内部（真实实现），job 的契约是按扫描集逐条调它
        verify(paymentService).publishPaid(any(PaymentOrder.class));
    }

    @Test
    void poisonRecordDoesNotStarveTheRest() {
        PaymentOrder poison = paid(1001L);
        PaymentOrder healthy = paid(1002L);
        when(mapper.findUnpublishedPaid(any(LocalDateTime.class))).thenReturn(List.of(poison, healthy));
        doThrow(new RuntimeException("mq down")).when(paymentService).publishPaid(poison);

        job.republishUnpublished();

        verify(paymentService).publishPaid(healthy);
        verify(mapper, never()).markEventPublished(1002L);
    }

    @Test
    void nothingToRepublishMeansNoCalls() {
        when(mapper.findUnpublishedPaid(any(LocalDateTime.class))).thenReturn(List.of());

        job.republishUnpublished();

        verify(paymentService, never()).publishPaid(any());
    }
}
