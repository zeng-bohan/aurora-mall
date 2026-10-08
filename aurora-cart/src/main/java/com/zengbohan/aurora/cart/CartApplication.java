package com.zengbohan.aurora.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

// 根扫描会带上 aurora-common 的 web 缝（advice + trace filter + internal secret）。
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@EnableFeignClients(basePackages = "com.zengbohan.aurora.cart.client")
public class CartApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartApplication.class, args);
    }
}
