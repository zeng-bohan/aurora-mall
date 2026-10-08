package com.zengbohan.aurora.gateway.auth;

import com.zengbohan.aurora.common.auth.JwtCodec;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
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
 * 网关侧 JWT 门禁：白名单路径直接放行，其余请求必须携带有效且未被撤销的
 * access token；下游服务拿到的是解析后的身份信息加内部密钥头，而不是 token。
 */
@Component
public class GatewayJwtFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_POST = List.of(
            "/api/user/register", "/api/user/login", "/api/user/refresh",
            // "第三方"支付回调：生产环境由渠道签名认证，而非用户 token
            //（mock 渠道：保持可达以便重放）
            "/api/payment/payments/mock-callback");
    private static final String BEARER = "Bearer ";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private final JwtCodec jwtCodec;
    private final ReactiveStringRedisTemplate redis;
    private final String internalSecret;
    private final ObjectMapper mapper;

    public GatewayJwtFilter(JwtCodec jwtCodec,
                            ReactiveStringRedisTemplate redis,
                            @Value("${aurora.internal.secret}") String internalSecret,
                            ObjectMapper mapper) {
        this.jwtCodec = jwtCodec;
        this.mapper = mapper;
        this.redis = redis;
        this.internalSecret = internalSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (isPublic(request)) {
            // 公开路径跳过鉴权，但仍注入内部密钥：
            // 服务端会拒绝任何未经网关的请求。
            // 同时清洗客户端自带的身份头——公开端点现在不读它们，但一旦读就是
            // 即插即用的身份伪造（X-Internal-Secret 用 set 覆盖，无此问题）
            return chain.filter(exchange.mutate()
                    .request(r -> r.headers(h -> {
                        h.remove(USER_ID_HEADER);
                        h.remove(USER_ROLE_HEADER);
                        h.set(INTERNAL_SECRET_HEADER, internalSecret);
                    }))
                    .build());
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
                    // 原样转发 Authorization：登出需要原始 token 的 jti，
                    // 下游的信任改由内部密钥头建立
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
        // health 保持开放，让冒烟缝可以经网关探活
        if (path.endsWith("/actuator/health")) {
            return true;
        }
        if (PUBLIC_POST.contains(path)) {
            return HttpMethod.POST.equals(request.getMethod());
        }
        // 商品读接口对游客开放，但管理端视图仍需 token + ADMIN 角色
        return HttpMethod.GET.equals(request.getMethod())
                && path.startsWith("/api/product/")
                && !path.contains("/admin");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Result.fail(ErrorCode.UNAUTHORIZED));
        } catch (Exception e) {
            // 序列化失败的兜底：硬编码码值必须与 ErrorCode.UNAUTHORIZED 保持一致
            body = ("{\"code\":" + ErrorCode.UNAUTHORIZED.getCode() + "}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
