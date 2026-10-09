package com.zengbohan.aurora.seckill.web;

import com.zengbohan.aurora.common.web.AdminRoleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 注册 ADMIN 角色拦截器（common 提供）：admin 路径全量过一遍 @RequireAdmin 检查。
@Configuration
public class SeckillAdminWebConfiguration implements WebMvcConfigurer {

    private final AdminRoleInterceptor adminRoleInterceptor;

    public SeckillAdminWebConfiguration(AdminRoleInterceptor adminRoleInterceptor) {
        this.adminRoleInterceptor = adminRoleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminRoleInterceptor).addPathPatterns("/admin/**");
    }
}
