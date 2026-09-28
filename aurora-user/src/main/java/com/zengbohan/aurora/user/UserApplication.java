package com.zengbohan.aurora.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Root scan picks up aurora-common's web seam (advice + trace filter).
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@MapperScan("com.zengbohan.aurora.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
