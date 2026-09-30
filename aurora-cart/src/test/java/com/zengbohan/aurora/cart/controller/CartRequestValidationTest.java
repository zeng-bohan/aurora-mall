package com.zengbohan.aurora.cart.controller;

import com.zengbohan.aurora.common.web.GlobalExceptionHandler;
import com.zengbohan.aurora.cart.service.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * quantity 上限校验（T19）：@Max(999) 违例走 GlobalExceptionHandler 落
 * PARAM_ERROR（10001），且请求不会触达业务服务。
 */
@WebMvcTest(CartController.class)
@Import(GlobalExceptionHandler.class)
class CartRequestValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @Test
    void quantityOverLimitIsRejectedAsParamErrorBeforeService() throws Exception {
        mockMvc.perform(post("/carts/items")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":1,\"quantity\":1000}"))
                // 校验失败走统一信封：HTTP 200 + code 10001（本项目业务错误信封风格）
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));

        verify(cartService, never()).add(anyLong(), any());
    }

    @Test
    void quantityWithinLimitReachesService() throws Exception {
        mockMvc.perform(post("/carts/items")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":1,\"quantity\":3}"))
                .andExpect(status().isOk());

        verify(cartService).add(anyLong(), any());
    }
}
