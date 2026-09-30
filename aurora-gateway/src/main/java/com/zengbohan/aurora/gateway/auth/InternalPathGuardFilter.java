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
 * 一律拒绝；/actuator/prometheus 等指标端点同样不对网关暴露（Prometheus 从
 * 宿主直连服务端口抓取）。没有这道守卫，StripPrefix 会把 /api/order/internal/
 * orders/{id} 还原成服务侧 /internal/orders/{id}，内部密钥过滤器又因网关注入
 * 而放行——任何登录用户都能查任意订单（外部审查一.1 实测）。
 * <p>
 * 穿越防御：URI.getPath() 已解码，点段（../ 与 %2e%2e）会原样透传，由下游
 * Tomcat 归一化后命中内部路径——因此解码路径里出现 ".." 段直接拒绝，不做
 * 归一化后匹配（归一化实现只要有一个角落漏掉就是绕过）。
 */
@Component
public class InternalPathGuardFilter implements GlobalFilter, Ordered {

    private static final byte[] BODY =
            "{\"code\":40400,\"message\":\"资源不存在\",\"data\":null}".getBytes(StandardCharsets.UTF_8);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        // 路由已匹配、StripPrefix 之后的下游路径 = 原始路径去掉 /api/{service}
        String raw = exchange.getRequest().getURI().getRawPath();  // 未解码
        String decoded = exchange.getRequest().getURI().getPath(); // 已解码
        String downstream = downstreamOf(decoded);
        // 点段穿越：字面 ../、编码 %2e%2e（raw/解码后任一形态出现都拒）——
        // 透传给下游会被归一化成内部路径
        if (hasDotSegment(downstream) || hasDotSegment(raw)
                || raw.toLowerCase().contains("%2e")
                || decoded.toLowerCase().contains("%2e")) {
            return reject(exchange);
        }
        if (downstream.startsWith("/internal/") || downstream.equals("/internal")
                || downstream.startsWith("/actuator/")) {
            // 对外伪装成 404：不暴露内部端点/指标端点的存在性
            return reject(exchange);
        }
        return chain.filter(exchange);
    }

    /** 原始路径去掉 /api/{service} 前缀 = StripPrefix 后的下游路径。 */
    private static String downstreamOf(String decodedPath) {
        String[] segments = decodedPath.split("/", 4); // "", api, service, rest
        return segments.length == 4 ? "/" + segments[3] : decodedPath;
    }

    private static boolean hasDotSegment(String path) {
        for (String seg : path.split("/")) {
            if (seg.equals("..") || seg.equals(".")) {
                return true;
            }
        }
        return false;
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(BODY)));
    }

    @Override
    public int getOrder() {
        return -300; // 早于鉴权(-100)与限流(-200)：内部路径最先封死
    }
}
