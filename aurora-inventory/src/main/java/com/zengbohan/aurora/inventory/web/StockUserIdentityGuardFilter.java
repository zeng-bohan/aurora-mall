package com.zengbohan.aurora.inventory.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 库存写路径的用户身份守卫（外部审查一.1 的服务侧纵深）：/stocks/** 只供
 * order 服务经 Feign 调用（带内部密钥、不带用户身份）。网关会为转发流量注入
 * X-User-Id——凡携带它的请求一律 403，任何登录用户都无法再借网关改库存。
 */
@Component
@ConditionalOnProperty(name = "aurora.internal.secret")
public class StockUserIdentityGuardFilter extends OncePerRequestFilter {

    private static final byte[] REJECTION =
            "{\"code\":40300,\"message\":\"无权访问\",\"data\":null}".getBytes(StandardCharsets.UTF_8);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getHeader("X-User-Id") != null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(REJECTION);
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/stocks/");
    }
}
