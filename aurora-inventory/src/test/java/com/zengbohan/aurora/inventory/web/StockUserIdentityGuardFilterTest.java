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

    /**
     * 矩阵参数绕过（实测漏洞的回归测试）。
     * <p>
     * 判据原本读 {@code request.getRequestURI()}，它保留 {@code ;} 参数，于是
     * {@code /stocks;x=1/76} 不以 {@code /stocks/} 开头 → 守卫放行；而 Spring MVC
     * 匹配时会剥掉 {@code ;x=1}，正常路由到 {@code /stocks/{skuId}}。两者视图不一致，
     * 普通登录用户借此可写任意 SKU 的库存。
     */
    @Test
    void matrixPathParametersCannotSmugglePastThePrefixCheck() {
        for (String uri : new String[]{
                "/stocks;x=1/76",
                "/stocks/76;x=1",
                "/stocks;x=1",
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest("PUT", uri);
            assertThat(filter.shouldNotFilter(request))
                    .as("%s 必须仍受守卫约束（应走身份校验）", uri)
                    .isFalse();
        }
    }

    /** 绕过变体必须真的被拒（而不只是"被过滤"）。 */
    @Test
    void matrixPathParameterRequestWithUserIdentityIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/stocks;x=1/76");
        request.addHeader("X-User-Id", "153");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("绕过了守卫：请求到达了控制器");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    /** Feign 调用（无用户身份）走矩阵参数形态也照常放行，别误伤内部调用。 */
    @Test
    void feignCallStillPassesRegardlessOfMatrixParameters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/stocks;x=1/76/rollback");
        request.addHeader("X-Internal-Secret", "internal");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter.doFilter(request, response, (req, res) -> reached[0] = true);

        assertThat(reached[0]).isTrue();
    }
}
