package com.zengbohan.aurora.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅测试用的启动类，对 aurora-common 做组件扫描，
 * 让 web 缝（ControllerAdvice + Filter）可以用 MockMvc 验证。
 */
@SpringBootApplication
public class CommonTestApplication {
}
