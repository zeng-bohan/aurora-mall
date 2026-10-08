package com.zengbohan.aurora.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// 根扫描会带上 aurora-common 的 web 缝（advice + trace filter）。
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@EnableScheduling
@MapperScan("com.zengbohan.aurora.product.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
