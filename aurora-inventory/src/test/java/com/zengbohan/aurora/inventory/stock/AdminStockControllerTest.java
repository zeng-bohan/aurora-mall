package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.web.AdminRoleInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端库存入口：非 ADMIN 在触达库存之前就被挡下，ADMIN 才真的改库存。
 * <p>
 * 这条路径不受 {@code StockUserIdentityGuardFilter} 约束（不在 /stocks/ 前缀下），
 * 所以角色闸门是它唯一的授权依据——这里必须钉住。
 */
class AdminStockControllerTest {

    private StockService stockService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stockService = mock(StockService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminStockController(stockService))
                .addInterceptors(new AdminRoleInterceptor())
                .build();
    }

    @Test
    void missingRoleIsRejectedBeforeTouchingStock() throws Exception {
        mockMvc.perform(put("/admin/stocks/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":50}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verifyNoInteractions(stockService);
    }

    @Test
    void nonAdminRoleIsRejected() throws Exception {
        mockMvc.perform(put("/admin/stocks/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":50}")
                        .header("X-User-Role", "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verifyNoInteractions(stockService);
    }

    @Test
    void adminSetsStock() throws Exception {
        mockMvc.perform(put("/admin/stocks/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":50}")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(stockService).setStock(9L, 50);
    }

    // 0 是合法的（临时关卖），不能被 @Min 误挡
    @Test
    void zeroIsAccepted() throws Exception {
        mockMvc.perform(put("/admin/stocks/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());

        verify(stockService).setStock(9L, 0);
    }
}
