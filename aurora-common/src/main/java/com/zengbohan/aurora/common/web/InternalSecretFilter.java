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
 * Defense against bypassing the gateway: every service-to-service request
 * carries the shared X-Internal-Secret header (the gateway injects it on
 * forwarded traffic, Feign adds it on outgoing calls). Requests without the
 * correct value are rejected at 401 before reaching any controller.
 *
 * The bean only exists when aurora.internal.secret is configured (T5 moves
 * the value into the nacos config center with fail-fast startup).
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

    /** 容器/K8s 探针直连服务端口的健康检查免内部密钥（其余路径一律校验）。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
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
