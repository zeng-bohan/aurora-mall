package com.zengbohan.aurora.gateway.auth;

import com.zengbohan.aurora.common.auth.JwtCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayJwtFilterTest {

    private static final String SECRET = "test-secret-0123456789abcdef-0123456789";
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final String INTERNAL_SECRET = "internal-dev-secret-0123456789";

    private ReactiveStringRedisTemplate redis;
    private GatewayJwtFilter filter;
    private JwtCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JwtCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        filter = new GatewayJwtFilter(codec, redis, INTERNAL_SECRET);
    }

    private String accessToken(String jti) {
        return codec.encode(new JwtCodec.Claims("1", "USER", jti,
                JwtCodec.TYP_ACCESS, NOW, NOW.plus(Duration.ofMinutes(30))));
    }

    private MockServerWebExchange exchange(String path, String token) {
        return exchange(HttpMethod.GET, path, token);
    }

    private MockServerWebExchange exchange(HttpMethod method, String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, path);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private int run(MockServerWebExchange exchange) {
        AtomicReference<org.springframework.web.server.ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        if (captured.get() != null) {
            return 200;
        }
        return exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED ? 401 : 500;
    }

    @Test
    void missingTokenIsUnauthorized() {
        assertThat(run(exchange("/api/cart", null))).isEqualTo(401);
    }

    @Test
    void expiredTokenIsUnauthorized() {
        String expired = codec.encode(new JwtCodec.Claims("1", "USER", "j",
                JwtCodec.TYP_ACCESS, NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(30))));
        assertThat(run(exchange("/api/cart", expired))).isEqualTo(401);
    }

    @Test
    void refreshTokenIsUnauthorized() {
        String refresh = codec.encode(new JwtCodec.Claims("1", "USER", "j",
                JwtCodec.TYP_REFRESH, NOW, NOW.plus(Duration.ofDays(7))));
        assertThat(run(exchange("/api/cart", refresh))).isEqualTo(401);
    }

    @Test
    void revokedTokenIsUnauthorized() {
        when(redis.hasKey(JwtCodec.BLACKLIST_KEY_PREFIX + "revoked-jti")).thenReturn(Mono.just(true));
        assertThat(run(exchange("/api/cart", accessToken("revoked-jti")))).isEqualTo(401);
    }

    @Test
    void validTokenPassesWithIdentityHeadersAndForwardedAuthorization() {
        AtomicReference<org.springframework.web.server.ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        String token = accessToken("jti-1");
        MockServerWebExchange exchange = exchange("/api/cart", token);
        filter.filter(exchange, chain).block();

        org.springframework.http.HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("1");
        assertThat(headers.getFirst("X-User-Role")).isEqualTo("USER");
        assertThat(headers.getFirst(GatewayJwtFilter.INTERNAL_SECRET_HEADER)).isEqualTo(INTERNAL_SECRET);
        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void publicGetProductPassesWithoutToken() {
        assertThat(run(exchange("/api/product/1", null))).isEqualTo(200);
    }

    @Test
    void publicRegisterPostPassesWithoutToken() {
        assertThat(run(exchange(HttpMethod.POST, "/api/user/register", null))).isEqualTo(200);
    }

    @Test
    void actuatorHealthPassesWithoutToken() {
        assertThat(run(exchange("/api/payment/actuator/health", null))).isEqualTo(200);
    }

    @Test
    void productGetIsPublicButProductPostIsNot() {
        MockServerHttpRequest post = MockServerHttpRequest.post("/api/product").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(post);
        assertThat(run(exchange)).isEqualTo(401);
    }
}
