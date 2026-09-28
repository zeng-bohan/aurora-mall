package com.zengbohan.aurora.cart.service;

import com.zengbohan.aurora.cart.client.ProductClient;
import com.zengbohan.aurora.cart.dto.CartItem;
import com.zengbohan.aurora.cart.dto.CartItemRequest;
import com.zengbohan.aurora.cart.dto.ProductSnapshot;
import com.zengbohan.aurora.cart.store.InMemoryCartStore;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CartServiceTest {

    private InMemoryCartStore store;
    private ProductClient productClient;
    private CartService service;

    private static final ProductSnapshot MUG =
            new ProductSnapshot(1L, "Aurora Mug", new BigDecimal("29.90"), 100, 1);

    @BeforeEach
    void setUp() {
        store = new InMemoryCartStore();
        productClient = mock(ProductClient.class);
        service = new CartService(store, productClient);
        when(productClient.detail(anyLong())).thenReturn(Result.ok(MUG));
        when(productClient.batch(anyList()))
                .thenReturn(Result.ok(List.of(MUG)));
    }

    @Test
    void repeatedAddsAccumulateQuantity() {
        service.add(7L, new CartItemRequest(1L, 2));
        service.add(7L, new CartItemRequest(1L, 3));

        assertThat(store.entries(7L)).containsEntry(1L, 5L);
    }

    @Test
    void addUnknownSkuThrowsParamError() {
        when(productClient.detail(999L)).thenReturn(Result.fail(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service.add(7L, new CartItemRequest(999L, 1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.PARAM_ERROR.getCode());
        assertThat(store.entries(7L)).isEmpty();
    }

    @Test
    void productServiceDownThrowsSystemErrorWithoutWritingCart() {
        when(productClient.detail(anyLong())).thenThrow(new RuntimeException("connect refused"));

        assertThatThrownBy(() -> service.add(7L, new CartItemRequest(1L, 1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(store.entries(7L)).isEmpty();
    }

    @Test
    void setQuantityOverwrites() {
        service.add(7L, new CartItemRequest(1L, 2));
        service.setQuantity(7L, new CartItemRequest(1L, 9));

        assertThat(store.entries(7L)).containsEntry(1L, 9L);
    }

    @Test
    void viewJoinsProductSnapshots() {
        service.add(7L, new CartItemRequest(1L, 4));

        List<CartItem> items = service.view(7L);

        assertThat(items).hasSize(1);
        CartItem item = items.get(0);
        assertThat(item.skuId()).isEqualTo(1L);
        assertThat(item.quantity()).isEqualTo(4);
        assertThat(item.title()).isEqualTo("Aurora Mug");
        assertThat(item.price()).isEqualByComparingTo("29.90");
    }

    @Test
    void viewSkipsLinesWhoseProductDisappeared() {
        service.add(7L, new CartItemRequest(1L, 4));
        service.add(7L, new CartItemRequest(555L, 1));
        // batch returns only the surviving product
        when(productClient.detail(555L)).thenReturn(Result.ok(new ProductSnapshot(
                555L, "Gone", BigDecimal.ONE, 0, 1)));
        when(productClient.batch(anyList())).thenReturn(Result.ok(List.of(MUG)));

        List<CartItem> items = service.view(7L);

        assertThat(items).extracting(CartItem::skuId).containsExactly(1L);
    }

    @Test
    void viewOfEmptyCartDoesNotCallProductService() {
        assertThat(service.view(7L)).isEmpty();
    }

    @Test
    void removeAndClear() {
        service.add(7L, new CartItemRequest(1L, 4));
        service.remove(7L, 1L);
        assertThat(store.entries(7L)).isEmpty();

        service.add(7L, new CartItemRequest(1L, 4));
        service.clear(7L);
        assertThat(store.entries(7L)).isEmpty();
    }
}
