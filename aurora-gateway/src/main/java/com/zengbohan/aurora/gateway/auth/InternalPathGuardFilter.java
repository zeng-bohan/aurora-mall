package com.zengbohan.aurora.gateway.auth;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 内部路径守卫：/internal/** 端点只供服务间 Feign 调用，网关路由的用户流量
 * 一律拒绝。没有这道守卫，StripPrefix 会把 /api/order/internal/orders/{id}
 * 还原成服务侧 /internal/orders/{id}，内部密钥过滤器又因网关注入而放行——
 * 任何登录用户都能查任意订单（外部审查一.1 实测）。
 */
@Component
public class InternalPathGuardFilter implements GlobalFilter, Ordered {

    private static final byte[] BODY =
            "{\"code\":40300,\"message\":\"资源不存在\",\"data\":null}".getBytes(StandardCharsets.UTF_8);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        // 路由已匹配、StripPrefix 之后的下游路径 = 原始路径去掉 /api/{service}
        String raw = exchange.getRequest().getURI().getPath();
        String[] segments = raw.split("/", 4); // "", api, service, rest
        String downstream = segments.length == 4 ? "/" + segments[3] : raw;
        if (downstream.startsWith("/internal/") || downstream.equals("/internal")) {
            // 对外伪装成 404：不暴露内部端点的存在性
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.NOT_FOUND);
            response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");
            return response.writeWith(Mono.just(response.bufferFactory().wrap(BODY)));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -300; // 早于鉴权(-100)与限流(-200)：内部路径最先封死
    }
}
