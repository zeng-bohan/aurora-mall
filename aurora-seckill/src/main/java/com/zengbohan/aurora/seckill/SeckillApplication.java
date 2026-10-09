package com.zengbohan.aurora.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 根扫描会带上 aurora-common 的 web 缝（advice + trace filter + admin 角色守卫）。
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@MapperScan("com.zengbohan.aurora.seckill.mapper")
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
