package com.zengbohan.aurora.user.service;

import com.zengbohan.aurora.common.auth.JwtCodec;
import com.zengbohan.aurora.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogoutServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef-0123456789";
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private JwtCodec codec;
    private LogoutService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codec = new JwtCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new LogoutService(codec, redis, 604800);
    }

    private String accessToken(long ttlSeconds) {
        return codec.encode(
                new JwtCodec.Claims("1", "USER", "jti-1", JwtCodec.TYP_ACCESS,
                        NOW, NOW.plusSeconds(ttlSeconds)));
    }

    @Test
    void logoutBlacklistsJtiForRemainingTtl() {
        String token = accessToken(1800);

        service.logout("Bearer " + token);

        verify(valueOps).set(JwtCodec.BLACKLIST_KEY_PREFIX + "jti-1", "1", Duration.ofSeconds(1800));
    }

    @Test
    void logoutAlsoInvalidatesAllExistingSessions() {
        String token = accessToken(1800);

        service.logout("Bearer " + token);

        // 会话级失效时间戳，TTL = refresh 寿命
        verify(valueOps).set("aurora:jwt:session-invalid-before:1",
                String.valueOf(NOW.getEpochSecond()), Duration.ofSeconds(604800));
    }

    @Test
    void logoutWithoutHeaderThrows() {
        assertThatThrownBy(() -> service.logout(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void logoutWithRefreshTokenThrows() {
        String refresh = codec.encode(
                new JwtCodec.Claims("1", "USER", "jti-2", JwtCodec.TYP_REFRESH,
                        NOW, NOW.plusSeconds(604800)));

        assertThatThrownBy(() -> service.logout("Bearer " + refresh))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredTokenLogoutIsNoopWithoutRedisWrite() {
        String alreadyExpired = codec.encode(
                new JwtCodec.Claims("1", "USER", "jti-3", JwtCodec.TYP_ACCESS,
                        NOW.minusSeconds(60), NOW.minusSeconds(30)));

        assertThatThrownBy(() -> service.logout("Bearer " + alreadyExpired))
                .isInstanceOf(BusinessException.class);
    }
}
