package com.zengbohan.aurora.product.web;

import com.zengbohan.aurora.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ADMIN 角色拦截器：无角色/错误角色 403，ADMIN 放行；无注解端点不受影响。
class AdminRoleInterceptorTest {

    private MockMvc mockMvc;

    @RestController
    static class ProbeController {
        @RequireAdmin
        @GetMapping("/admin/probe")
        public Result<Void> adminOnly() {
            return Result.ok();
        }

        @GetMapping("/public/probe")
        public Result<Void> open() {
            return Result.ok();
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addInterceptors(new AdminRoleInterceptor())
                .build();
    }

    @Test
    void missingRoleIsRejectedWith403Envelope() throws Exception {
        mockMvc.perform(get("/admin/probe"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void wrongRoleIsRejectedWith403Envelope() throws Exception {
        mockMvc.perform(get("/admin/probe").header("X-User-Role", "USER")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void adminRolePassesThrough() throws Exception {
        mockMvc.perform(get("/admin/probe").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void endpointsWithoutAnnotationAreUntouched() throws Exception {
        mockMvc.perform(get("/public/probe"))
                .andExpect(status().isOk());
    }
}
