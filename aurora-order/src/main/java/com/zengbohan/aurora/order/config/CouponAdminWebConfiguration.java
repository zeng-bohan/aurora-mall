package com.zengbohan.aurora.order.config;

import com.zengbohan.aurora.common.web.AdminRoleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 ADMIN 角色拦截器（common 提供）。order 模块此前没有 /admin/** 端点，
 * 券是第一个——拦截器只对 /admin/** 生效，不影响既有用户端路径。
 */
@Configuration
public class CouponAdminWebConfiguration implements WebMvcConfigurer {

    private final AdminRoleInterceptor adminRoleInterceptor;

    public CouponAdminWebConfiguration(AdminRoleInterceptor adminRoleInterceptor) {
        this.adminRoleInterceptor = adminRoleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminRoleInterceptor).addPathPatterns("/admin/**");
    }
}
