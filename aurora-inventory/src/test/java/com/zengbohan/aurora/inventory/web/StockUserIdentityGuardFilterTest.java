package com.zengbohan.aurora.inventory.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

// /stocks/** 携带 X-User-Id 一律 403（外部审查一.1 的服务侧纵深）。
class StockUserIdentityGuardFilterTest {

    private StockUserIdentityGuardFilter filter;

    @BeforeEach
    void setUp() {
        filter = new StockUserIdentityGuardFilter();
    }

    @Test
    void userAttributedStockRequestIsRejectedWith403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/stocks/1/rollback");
        request.addHeader("X-User-Id", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not be reached");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void feignCallWithoutUserHeaderPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/stocks/1/rollback");
        request.addHeader("X-Internal-Secret", "internal");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter.doFilter(request, response, (req, res) -> reached[0] = true);

        assertThat(reached[0]).isTrue();
    }

    @Test
    void nonStockPathsAreNotFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/stocks/1"))).isFalse();
    }

    // 管理端入口（/admin/stocks/{skuId}）刻意落在这条守卫之外：它服务的是"运营开库存"
    // 这个浏览器场景，而本守卫的判据恰恰是"请求带用户身份就拒绝"。
    // 代价是那条路径没有第二把锁，所以它的授权完全依赖 @RequireAdmin——
    // 见 AdminStockControllerTest。这条断言把边界钉住，免得日后有人以为这里也守得住。
    @Test
    void adminStockPathIsDeliberatelyOutsideThisGuard() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("PUT", "/admin/stocks/1"))).isTrue();
    }
}
