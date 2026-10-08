package com.zengbohan.aurora.user.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2 验收项：下游服务读取网关注入的身份头。
 * 这里验证的是接收侧（服务端口，而非网关）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityHeaderHandoffTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void downstreamReadsUserIdRoleAndSecret() throws Exception {
        mockMvc.perform(get("/me")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "ADMIN")
                        .header("X-Internal-Secret", "test-only-internal-secret-not-real"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("7"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void requestWithoutInternalSecretIsRejectedEvenWithIdentityHeaders() throws Exception {
        mockMvc.perform(get("/me")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
