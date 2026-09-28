package com.zengbohan.aurora.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Root scan picks up aurora-common's web seam (advice + trace filter).
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
