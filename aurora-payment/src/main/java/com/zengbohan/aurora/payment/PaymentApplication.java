package com.zengbohan.aurora.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// Root scan picks up aurora-common's web seam (advice + trace + idempotency).
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@MapperScan("com.zengbohan.aurora.payment.mapper")
@EnableFeignClients(basePackages = "com.zengbohan.aurora.payment.client")
@EnableScheduling
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
