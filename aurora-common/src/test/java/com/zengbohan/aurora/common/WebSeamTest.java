package com.zengbohan.aurora.common;

import com.zengbohan.aurora.common.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebSeamTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void okResponseCarriesEnvelope() throws Exception {
        mockMvc.perform(get("/probe/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value("hello"));
    }

    @Test
    void businessErrorMapsToErrorCode() throws Exception {
        mockMvc.perform(get("/probe/business-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(20001))
                .andExpect(jsonPath("$.message").value("商品 sku-1 库存不足"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unexpectedErrorMapsToSystemErrorWith500() throws Exception {
        mockMvc.perform(get("/probe/system-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"));
    }

    @Test
    void traceIdGeneratedWhenHeaderAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/trace-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andReturn();
        String headerId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
        String bodyId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data");
        assertThat(headerId).isNotBlank().isEqualTo(bodyId);
    }

    @Test
    void traceIdFromInboundHeaderIsPreservedAndEchoed() throws Exception {
        mockMvc.perform(get("/probe/trace-id").header(TraceIdFilter.TRACE_ID_HEADER, "trace-abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("trace-abc-123"))
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "trace-abc-123"));
    }
}
