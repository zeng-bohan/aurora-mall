package com.zengbohan.aurora.product.web;

import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

// 读网关注入的 X-User-Role：@RequireAdmin 端点非 ADMIN 直接 403 信封。
@Component
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final byte[] REJECTION =
            "{\"code\":40300,\"message\":\"无权访问\",\"data\":null}".getBytes(StandardCharsets.UTF_8);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireAdmin required = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
        }
        if (required == null || "ADMIN".equals(request.getHeader("X-User-Role"))) {
            return true;
        }
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        // 与 InternalSecretFilter 同法：拒绝体为常量字节串，不走序列化
        response.getOutputStream().write(REJECTION);
        return false;
    }
}
