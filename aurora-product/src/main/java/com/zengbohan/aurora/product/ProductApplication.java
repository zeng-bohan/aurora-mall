package com.zengbohan.aurora.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Root scan picks up aurora-common's web seam (advice + trace filter).
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@MapperScan("com.zengbohan.aurora.product.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
