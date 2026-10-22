package com.zengbohan.aurora.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// 根扫描会带上 aurora-common 的 web 缝（advice + trace filter + admin 角色守卫）。
// @ConfigurationPropertiesScan：注册本模块的 @ConfigurationProperties（限流阈值），
// 配合 @RefreshScope 支持配置中心下发即时生效（与网关同一手法）。
// @EnableScheduling：受理结果对账任务（SeckillReconcileJob）。
@SpringBootApplication(scanBasePackages = "com.zengbohan.aurora")
@ConfigurationPropertiesScan
@MapperScan("com.zengbohan.aurora.seckill.mapper")
@EnableFeignClients(basePackages = "com.zengbohan.aurora.seckill.client")
@EnableScheduling
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
