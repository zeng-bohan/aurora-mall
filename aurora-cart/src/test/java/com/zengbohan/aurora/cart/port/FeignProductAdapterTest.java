package com.zengbohan.aurora.cart.port;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.cart.client.ProductClient;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Feign 适配器：HTTP 信封语义 → 端口语义的翻译表。
 * 成功/业务缺失/系统错误信封/连不上，四路各有明确映射。
 */
class FeignProductAdapterTest {

    private ProductClient productClient;
    private FeignProductAdapter adapter;

    private static final ProductSnapshot MUG =
            new ProductSnapshot(1L, "Aurora Mug", new BigDecimal("29.90"), 100, 1);

    @BeforeEach
    void setUp() {
        productClient = mock(ProductClient.class);
        adapter = new FeignProductAdapter(productClient);
    }

    @Test
    void successEnvelopeUnwrapsToData() {
        when(productClient.detail(1L)).thenReturn(Result.ok(MUG));

        assertThat(adapter.detail(1L)).isEqualTo(MUG);
    }

    @Test
    void notFoundEnvelopeTranslatesToNull() {
        when(productClient.detail(999L)).thenReturn(Result.fail(ErrorCode.NOT_FOUND));

        assertThat(adapter.detail(999L)).isNull();
    }

    @Test
    void systemErrorEnvelopeTranslatesToOutage() {
        when(productClient.detail(1L)).thenReturn(Result.fail(ErrorCode.SYSTEM_ERROR));

        assertThatThrownBy(() -> adapter.detail(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.SYSTEM_ERROR.getCode());
    }

    @Test
    void connectionFailureTranslatesToOutage() {
        when(productClient.detail(anyLong())).thenThrow(new RuntimeException("connect refused"));

        assertThatThrownBy(() -> adapter.detail(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.SYSTEM_ERROR.getCode());
    }

    @Test
    void batchFailureEnvelopeTranslatesToOutage() {
        when(productClient.batch(List.of(1L))).thenReturn(null);

        assertThatThrownBy(() -> adapter.batch(List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.SYSTEM_ERROR.getCode());
    }
}
