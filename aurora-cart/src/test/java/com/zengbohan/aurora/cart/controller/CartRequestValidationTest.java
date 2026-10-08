package com.zengbohan.aurora.cart.controller;

import com.zengbohan.aurora.common.web.GlobalExceptionHandler;
import com.zengbohan.aurora.common.web.InternalSecretFilter;
import com.zengbohan.aurora.cart.service.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
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
 * <p>
 * 显式 test profile：context 测试一律走 test profile（与其它模块一致）。
 * 否则会落到 default profile 而装配 Loki appender——CI 无 Loki，其推送失败产生的
 * logback ERROR 状态会被 Spring Boot 判为日志配置错误并中断上下文启动。
 * 该 profile 同时会启用 InternalSecretFilter（属性存在即注册），因此请求按真实
 * 链路带上共享密钥头：生产里这一头由网关注入。
 */
@WebMvcTest(CartController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class CartRequestValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    // 用测试 profile 的占位密钥，避免测试与 application-test.yml 的字面值漂移
    @Value("${aurora.internal.secret}")
    private String internalSecret;

    @Test
    void quantityOverLimitIsRejectedAsParamErrorBeforeService() throws Exception {
        mockMvc.perform(post("/carts/items")
                        .header("X-User-Id", "1")
                        .header(InternalSecretFilter.HEADER, internalSecret)
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
                        .header(InternalSecretFilter.HEADER, internalSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":1,\"quantity\":3}"))
                .andExpect(status().isOk());

        verify(cartService).add(anyLong(), any());
    }
}
