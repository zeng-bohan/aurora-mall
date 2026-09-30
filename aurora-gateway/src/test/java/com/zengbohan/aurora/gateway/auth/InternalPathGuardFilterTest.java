package com.zengbohan.aurora.gateway.auth;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** /internal/** 经网关一律 404（不暴露存在性）；其他路径照常。 */
class InternalPathGuardFilterTest {

    private InternalPathGuardFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalPathGuardFilter();
    }

    private MockServerWebExchange exchangeOnRoute(String rawPath) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(rawPath).build());
        Route route = mock(Route.class);
        when(route.getId()).thenReturn("order");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private GatewayFilterChain chainThatMarks(AtomicBoolean reached) {
        return ex -> {
            reached.set(true);
            return Mono.empty();
        };
    }

    @Test
    void internalPathIsRejectedAs404() {
        AtomicBoolean reached = new AtomicBoolean();
        MockServerWebExchange exchange = exchangeOnRoute("/api/order/internal/orders/1");

        filter.filter(exchange, chainThatMarks(reached)).block();

        assertThat(reached).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void normalPathsPassThrough() {
        AtomicBoolean reached = new AtomicBoolean();
        MockServerWebExchange exchange = exchangeOnRoute("/api/order/orders");

        filter.filter(exchange, chainThatMarks(reached)).block();

        assertThat(reached).isTrue();
    }
}
