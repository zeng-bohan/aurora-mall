package com.zengbohan.aurora.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// Root scan picks up aurora-common's web seam (advice + trace + idempotency).
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@MapperScan("com.zengbohan.aurora.order.mapper")
@EnableFeignClients(basePackages = "com.zengbohan.aurora.order.client")
@EnableScheduling
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
