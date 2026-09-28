package com.zengbohan.aurora.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap that component-scans aurora-common so the web seam
 * (controller advice + filter) can be exercised with MockMvc.
 */
@SpringBootApplication
public class CommonTestApplication {
}
