package com.zengbohan.aurora.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 网关全局限流过滤器（ADR-0008：接入手写 aurora-ratelimit 的分布式形态）。
 * <p>
 * 按路由 id 取 nacos 下发的规则；Redis ZSET 共享配额（多实例合并计数）。
 * 排在鉴权（-100）之前：过载保护最先卸载流量，登录/注册等公开路径同样受保护。
 * <p>
 * 降级语义：Redis 不可用时放行并记 WARN——限流器故障不能放大成全站不可用
 * （fail-open），此时各服务自身的过载保护仍然在。
 */
@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitFilter.class);

    /** 业务码：限流（与统一 Result 约定一致，前端按 code 处理）。 */
    static final String TOO_MANY_REQUESTS_BODY =
            "{\"code\":42900,\"message\":\"请求过于频繁，请稍后再试\"}";

    private final RateLimitProperties properties;
    private final RedisSlidingWindowRateLimiter limiter;

    public GatewayRateLimitFilter(RateLimitProperties properties,
                                  RedisSlidingWindowRateLimiter limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        org.springframework.cloud.gateway.route.Route route =
                exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        RateLimitProperties.Rule rule = properties.getRoutes().get(route.getId());
        if (rule == null) {
            return chain.filter(exchange); // 没有规则的路由不限流
        }
        // 每请求校验：@RefreshScope 重建属性后构造器不会重跑，规则校验必须在
        // 请求路径上——否则刷新出 limit<=0 的规则会让整条路由恒 429
        rule.validate(route.getId());
        String key = "aurora:rl:" + route.getId();
        return limiter.tryAcquire(key, rule.getLimit(), Duration.ofSeconds(rule.getWindowSeconds()))
                .flatMap(allowed -> allowed
                        ? chain.filter(exchange)
                        : reject(exchange))
                // fail-open：Redis 故障放行，不把限流器故障放大成全站不可用
                .onErrorResume(e -> {
                    log.warn("rate limiter degraded (redis unavailable), allowing request for route {}: {}",
                            route.getId(), e.toString());
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = TOO_MANY_REQUESTS_BODY.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -200; // 鉴权(-100)之前：最先卸载过载流量
    }
}
