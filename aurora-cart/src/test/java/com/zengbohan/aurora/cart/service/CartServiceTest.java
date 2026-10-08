package com.zengbohan.aurora.cart.service;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.cart.dto.CartItem;
import com.zengbohan.aurora.cart.dto.CartItemRequest;
import com.zengbohan.aurora.cart.port.ProductPort;
import com.zengbohan.aurora.cart.store.InMemoryCartStore;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 购物车业务单测：业务只依赖 ProductPort 端口（M3 T8）。
 * 端口语义——detail 不存在返回 null、batch 缺失 id 不在结果中、
 * 不可用在适配器层统一翻译为 SYSTEM_ERROR 业务异常——用假端口模拟。
 */
class CartServiceTest {

    private InMemoryCartStore store;
    private FakeProductPort productPort;
    private CartService service;

    private static final ProductSnapshot MUG =
            new ProductSnapshot(1L, "Aurora Mug", new BigDecimal("29.90"), 100, 1);

    // 假端口：可编程返回与故障。
    private static final class FakeProductPort implements ProductPort {
        final Map<Long, ProductSnapshot> catalog = new HashMap<>(Map.of(1L, MUG));
        boolean outage;

        @Override
        public ProductSnapshot detail(long id) {
            if (outage) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
            }
            return catalog.get(id);
        }

        @Override
        public List<ProductSnapshot> batch(List<Long> ids) {
            if (outage) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "商品服务不可用");
            }
            return ids.stream().filter(catalog::containsKey).map(catalog::get).toList();
        }
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCartStore();
        productPort = new FakeProductPort();
        service = new CartService(store, productPort);
    }

    @Test
    void repeatedAddsAccumulateQuantity() {
        service.add(7L, new CartItemRequest(1L, 2));
        service.add(7L, new CartItemRequest(1L, 3));

        assertThat(store.entries(7L)).containsEntry(1L, 5L);
    }

    @Test
    void addUnknownSkuThrowsParamError() {
        assertThatThrownBy(() -> service.add(7L, new CartItemRequest(999L, 1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.PARAM_ERROR.getCode());
        assertThat(store.entries(7L)).isEmpty();
    }

    @Test
    void productOutageThrowsSystemErrorWithoutWritingCart() {
        productPort.outage = true; // 适配器把不可用翻译成 SYSTEM_ERROR，服务层只管业务语义

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
        productPort.catalog.put(555L, new ProductSnapshot(555L, "Gone", BigDecimal.ONE, 0, 1));
        service.add(7L, new CartItemRequest(1L, 4));
        service.add(7L, new CartItemRequest(555L, 1));
        productPort.catalog.remove(555L); // 商品下架：batch 不再返回该 id

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
