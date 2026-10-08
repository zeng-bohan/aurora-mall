package com.zengbohan.aurora.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 过滤器决策单测（不起 Redis）：规则缺失放行且不触碰 Redis、阈值内放行、
 * 超限 429、Redis 故障 fail-open。
 */
class GatewayRateLimitFilterTest {

    private RedisSlidingWindowRateLimiter limiter;
    private RateLimitProperties properties;
    private GatewayRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        limiter = mock(RedisSlidingWindowRateLimiter.class);
        properties = new RateLimitProperties();
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setLimit(10);
        rule.setWindowSeconds(1);
        properties.getRoutes().put("order", rule);
        filter = new GatewayRateLimitFilter(properties, limiter);
    }

    private MockServerWebExchange exchangeOnRoute(String routeId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/orders").build());
        Route route = mock(Route.class);
        when(route.getId()).thenReturn(routeId);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private GatewayFilterChain chainThatMarks(AtomicBoolean reached) {
        return exchange -> {
            reached.set(true);
            return Mono.empty();
        };
    }

    @Test
    void passesThroughWhenDisabledWithoutTouchingRedis() {
        properties.setEnabled(false);
        AtomicBoolean reached = new AtomicBoolean();
        MockServerWebExchange exchange = exchangeOnRoute("order");

        filter.filter(exchange, chainThatMarks(reached)).block();

        assertThat(reached).isTrue();
        verifyNoInteractions(limiter);
    }

    @Test
    void passesThroughRoutesWithoutRule() {
        AtomicBoolean reached = new AtomicBoolean();
        MockServerWebExchange exchange = exchangeOnRoute("user"); // 无规则路由

        filter.filter(exchange, chainThatMarks(reached)).block();

        assertThat(reached).isTrue();
        verifyNoInteractions(limiter);
    }

    @Test
    void allowsWhenUnderLimit() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(Mono.just(true));
        AtomicBoolean reached = new AtomicBoolean();
        MockServerWebExchange exchange = exchangeOnRoute("order");

        filter.filter(exchange, chainThatMarks(reached)).block();

        assertThat(reached).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rejectsWhenOverLimit() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(Mono.just(false));
        MockServerWebExchange exchange = exchangeOnRoute("order");

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("42900");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                .as("429 必须告知重试时机")
                .isEqualTo("1");
    }

    @Test
    void failsOpenWhenRedisErrors() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        AtomicBoolean reached = new AtomicBoolean();
        MockServerWebExchange exchange = exchangeOnRoute("order");

        filter.filter(exchange, chainThatMarks(reached)).block();

        assertThat(reached).as("限流器故障不应放大成全站不可用").isTrue();
    }
}
