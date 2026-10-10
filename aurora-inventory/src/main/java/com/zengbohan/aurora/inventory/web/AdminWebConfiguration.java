package com.zengbohan.aurora.inventory.web;

import com.zengbohan.aurora.common.web.AdminRoleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 ADMIN 角色拦截器：{@code /admin/**} 全量过一遍 {@link com.zengbohan.aurora.common.web.RequireAdmin}。
 * <p>
 * 库存的 admin 面只有 {@link com.zengbohan.aurora.inventory.stock.AdminStockController}
 * 一处，但拦截器按路径注册而不是按控制器注册——日后再加管理端点时不会有人
 * 忘了补一处注册，最后变成"新端点谁都能调"。
 */
@Configuration
public class AdminWebConfiguration implements WebMvcConfigurer {

    private final AdminRoleInterceptor adminRoleInterceptor;

    public AdminWebConfiguration(AdminRoleInterceptor adminRoleInterceptor) {
        this.adminRoleInterceptor = adminRoleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminRoleInterceptor).addPathPatterns("/admin/**");
    }
}
