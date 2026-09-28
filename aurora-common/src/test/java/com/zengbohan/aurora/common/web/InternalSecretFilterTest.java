package com.zengbohan.aurora.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"aurora.internal.secret=test-internal-secret-0123456789"})
@AutoConfigureMockMvc
class InternalSecretFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingSecretIsRejectedWith401Envelope() throws Exception {
        mockMvc.perform(get("/probe/ok"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void wrongSecretIsRejectedWith401Envelope() throws Exception {
        mockMvc.perform(get("/probe/ok").header(InternalSecretFilter.HEADER, "wrong-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void correctSecretPassesThrough() throws Exception {
        mockMvc.perform(get("/probe/ok").header(InternalSecretFilter.HEADER, "test-internal-secret-0123456789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
