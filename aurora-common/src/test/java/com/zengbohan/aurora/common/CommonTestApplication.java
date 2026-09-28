package com.zengbohan.aurora.common;

import com.zengbohan.aurora.common.web.TraceIdFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test-only bootstrap that component-scans aurora-common so the web seam
 * (controller advice + filter) can be exercised with MockMvc.
 */
@SpringBootApplication
public class CommonTestApplication {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }
}
