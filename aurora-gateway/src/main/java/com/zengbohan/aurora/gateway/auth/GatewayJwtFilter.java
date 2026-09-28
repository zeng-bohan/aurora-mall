package com.zengbohan.aurora.gateway.auth;

import com.zengbohan.aurora.common.auth.JwtCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gateway-side JWT gate (ADR-0006): whitelisted paths pass through, everything
 * else must carry a valid, non-revoked access token; downstream services get
 * the parsed identity plus the internal-secret header instead of the token.
 */
@Component
public class GatewayJwtFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_POST = List.of(
            "/api/user/register", "/api/user/login", "/api/user/refresh");
    private static final String BEARER = "Bearer ";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private final JwtCodec jwtCodec;
    private final ReactiveStringRedisTemplate redis;
    private final String internalSecret;
    private final ObjectMapper mapper = new ObjectMapper();

    public GatewayJwtFilter(JwtCodec jwtCodec,
                            ReactiveStringRedisTemplate redis,
                            @Value("${aurora.internal.secret}") String internalSecret) {
        this.jwtCodec = jwtCodec;
        this.redis = redis;
        this.internalSecret = internalSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (isPublic(request)) {
            return chain.filter(exchange);
        }
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return unauthorized(exchange);
        }
        JwtCodec.Claims claims;
        try {
            claims = jwtCodec.decode(authorization.substring(BEARER.length()));
        } catch (RuntimeException e) {
            return unauthorized(exchange);
        }
        if (!JwtCodec.TYP_ACCESS.equals(claims.typ())) {
            return unauthorized(exchange);
        }
        return redis.hasKey(JwtCodec.BLACKLIST_KEY_PREFIX + claims.jti())
                .defaultIfEmpty(false)
                .flatMap(revoked -> {
                    if (Boolean.TRUE.equals(revoked)) {
                        return unauthorized(exchange);
                    }
                    // Authorization is forwarded untouched: logout needs the raw
                    // token's jti, and downstream trust is established by the
                    // internal-secret header instead
                    return chain.filter(exchange.mutate()
                            .request(r -> r.headers(h -> {
                                h.set(USER_ID_HEADER, claims.subject());
                                h.set(USER_ROLE_HEADER, claims.role());
                                h.set(INTERNAL_SECRET_HEADER, internalSecret);
                            }))
                            .build());
                });
    }

    private boolean isPublic(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        if (path.endsWith("/actuator/health")) {
            return true;
        }
        if (PUBLIC_POST.contains(path)) {
            return HttpMethod.POST.equals(request.getMethod());
        }
        return HttpMethod.GET.equals(request.getMethod()) && path.startsWith("/api/product/");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(
                    com.zengbohan.aurora.common.result.Result.fail(
                            com.zengbohan.aurora.common.exception.ErrorCode.UNAUTHORIZED));
        } catch (Exception e) {
            body = "{\"code\":40100}".getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
