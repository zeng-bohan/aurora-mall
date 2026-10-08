package com.zengbohan.aurora.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 防止绕过网关：每个服务间请求都携带共享的 X-Internal-Secret 头
 * （网关在转发流量时注入，Feign 在出站调用时添加）。值不正确的请求
 * 在触达任何 Controller 之前就以 401 拒绝。
 *
 * 该 Bean 只在配置了 aurora.internal.secret 时存在（T5 把该值迁入
 * nacos 配置中心，并在启动时快速失败）。
 */
@Component
@ConditionalOnProperty(name = "aurora.internal.secret")
public class InternalSecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Secret";

    private static final byte[] REJECTION =
            "{\"code\":40100,\"message\":\"未通过网关鉴权\",\"data\":null}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final String expected;

    public InternalSecretFilter(@Value("${aurora.internal.secret}") String expected) {
        this.expected = expected;
    }

    // 容器/K8s 探针直连服务端口：健康检查与 Prometheus 抓取免内部密钥。
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/health") || uri.startsWith("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        // 常量时间比较：共享密钥不走 String.equals 的短路路径
        if (provided != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(REJECTION);
    }
}
