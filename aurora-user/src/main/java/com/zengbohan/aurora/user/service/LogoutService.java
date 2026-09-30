package com.zengbohan.aurora.user.service;

import com.zengbohan.aurora.common.auth.JwtCodec;
import com.zengbohan.aurora.common.auth.JwtException;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Writes revoked access tokens into the shared redis blacklist the gateway
 * checks on every authenticated request (ADR-0006).
 */
@Service
public class LogoutService {

    private static final String BEARER = "Bearer ";

    private static final String SESSION_INVALID_BEFORE_PREFIX = "aurora:jwt:session-invalid-before:";

    private final JwtCodec jwtCodec;
    private final StringRedisTemplate redis;
    private final long refreshTtlSeconds;

    @Autowired
    public LogoutService(JwtCodec jwtCodec, StringRedisTemplate redis,
                         @Value("${aurora.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.jwtCodec = jwtCodec;
        this.redis = redis;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public void logout(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        JwtCodec.Claims claims;
        try {
            claims = jwtCodec.decode(authorization.substring(BEARER.length()));
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!JwtCodec.TYP_ACCESS.equals(claims.typ())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        long remaining = claims.expiresAt().getEpochSecond() - jwtCodec.now().getEpochSecond();
        if (remaining > 0) {
            redis.opsForValue().set(JwtCodec.BLACKLIST_KEY_PREFIX + claims.jti(), "1",
                    Duration.ofSeconds(remaining));
        }
        // 会话级失效：一次登出作废该用户全部既有会话（refresh 不查黑名单但查这个时间戳）
        redis.opsForValue().set(SESSION_INVALID_BEFORE_PREFIX + claims.subject(),
                String.valueOf(jwtCodec.now().getEpochSecond()),
                Duration.ofSeconds(refreshTtlSeconds));
    }
}
