package com.zengbohan.aurora.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zengbohan.aurora.common.auth.JwtCodec;
import com.zengbohan.aurora.common.auth.JwtException;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.user.dto.LoginRequest;
import com.zengbohan.aurora.user.dto.RefreshRequest;
import com.zengbohan.aurora.user.dto.RegisterRequest;
import com.zengbohan.aurora.user.dto.TokenResponse;
import com.zengbohan.aurora.user.entity.User;
import com.zengbohan.aurora.user.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class UserService {

    static final String ROLE_USER = "USER";

    private static final String SESSION_INVALID_BEFORE_PREFIX = "aurora:jwt:session-invalid-before:";

    private final UserMapper userMapper;
    private final JwtCodec jwtCodec;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    /** 不存在用户时做一次等价 bcrypt 比对，抹平响应时间差。 */
    private static final String DUMMY_BCRYPT;

    static {
        DUMMY_BCRYPT = new BCryptPasswordEncoder().encode("aurora-dummy-password");
    }

    public UserService(UserMapper userMapper, JwtCodec jwtCodec, StringRedisTemplate redis,
                       @Value("${aurora.jwt.access-ttl-seconds:1800}") long accessTtlSeconds,
                       @Value("${aurora.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.userMapper = userMapper;
        this.jwtCodec = jwtCodec;
        this.redis = redis;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public Long register(RegisterRequest request) {
        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.username()));
        if (existing > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
        }
        User user = new User();
        user.setUsername(request.username());
        user.setPassword(encoder.encode(request.password()));
        user.setRole(ROLE_USER);
        user.setNickname(request.nickname());
        user.setStatus(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // two concurrent registrations pass the pre-check; the unique
            // index is the race authority
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
        }
        return user.getId();
    }

    public TokenResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.username()));
        if (user == null) {
            // 拉平与真实 bcrypt 校验的耗时，用户名不存在不再构成可枚举的时序侧信道
            encoder.matches(request.password(), DUMMY_BCRYPT);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名或密码错误");
        }
        if (user.getStatus() == 0 || !encoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名或密码错误");
        }
        return issueTokens(user);
    }

    public TokenResponse refresh(RefreshRequest request) {
        JwtCodec.Claims claims;
        try {
            claims = jwtCodec.decode(request.refreshToken());
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!JwtCodec.TYP_REFRESH.equals(claims.typ())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 已用过的 refresh（轮换后旧票立即作废）
        if (Boolean.TRUE.equals(redis.hasKey(JwtCodec.BLACKLIST_KEY_PREFIX + claims.jti()))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 登出后的会话级失效：iat 早于失效时间戳的一切旧 token 拒绝
        String invalidBefore = redis.opsForValue()
                .get(SESSION_INVALID_BEFORE_PREFIX + claims.subject());
        if (invalidBefore != null
                && claims.issuedAt().getEpochSecond() < Long.parseLong(invalidBefore)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(Long.valueOf(claims.subject()));
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 轮换：旧 refresh 拉黑（剩余寿命），一次一换
        long remaining = claims.expiresAt().getEpochSecond() - jwtCodec.now().getEpochSecond();
        if (remaining > 0) {
            redis.opsForValue().set(JwtCodec.BLACKLIST_KEY_PREFIX + claims.jti(), "1",
                    Duration.ofSeconds(remaining));
        }
        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        Instant now = Instant.now();
        String access = jwtCodec.encode(new JwtCodec.Claims(
                String.valueOf(user.getId()), user.getRole(), UUID.randomUUID().toString(),
                JwtCodec.TYP_ACCESS, now, now.plusSeconds(accessTtlSeconds)));
        String refresh = jwtCodec.encode(new JwtCodec.Claims(
                String.valueOf(user.getId()), user.getRole(), UUID.randomUUID().toString(),
                JwtCodec.TYP_REFRESH, now, now.plusSeconds(refreshTtlSeconds)));
        return new TokenResponse(access, refresh, accessTtlSeconds);
    }
}
