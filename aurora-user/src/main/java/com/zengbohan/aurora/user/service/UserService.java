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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class UserService {

    static final String ROLE_USER = "USER";

    private final UserMapper userMapper;
    private final JwtCodec jwtCodec;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public UserService(UserMapper userMapper, JwtCodec jwtCodec,
                       @Value("${aurora.jwt.access-ttl-seconds:1800}") long accessTtlSeconds,
                       @Value("${aurora.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.userMapper = userMapper;
        this.jwtCodec = jwtCodec;
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
        userMapper.insert(user);
        return user.getId();
    }

    public TokenResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.username()));
        if (user == null || user.getStatus() == 0 || !encoder.matches(request.password(), user.getPassword())) {
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
        User user = userMapper.selectById(Long.valueOf(claims.subject()));
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
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
