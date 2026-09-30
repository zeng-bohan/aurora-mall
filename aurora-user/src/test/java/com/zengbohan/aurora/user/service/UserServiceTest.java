package com.zengbohan.aurora.user.service;

import com.zengbohan.aurora.common.auth.JwtCodec;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.user.dto.LoginRequest;
import com.zengbohan.aurora.user.dto.RegisterRequest;
import com.zengbohan.aurora.user.dto.TokenResponse;
import com.zengbohan.aurora.user.entity.User;
import com.zengbohan.aurora.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> redisValues;
    private UserService userService;
    private final JwtCodec jwtCodec = new JwtCodec("test-secret-0123456789abcdef-0123456789");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        redisValues = mock(ValueOperations.class);
        // 缺省：无黑名单、无会话失效标记（具体用例自行覆盖 stub）
        when(redis.hasKey(any())).thenReturn(false);
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(any())).thenReturn(null);
        userService = new UserService(userMapper, jwtCodec, redis, 1800, 604800);
    }

    private User activeUser() {
        User user = new User();
        // 实体无 setter：insert 时由 MP 自增回填，测试里用反射钉住 id
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        user.setUsername("bohan");
        user.setPassword(new BCryptPasswordEncoder().encode("secret123"));
        user.setRole(UserService.ROLE_USER);
        user.setStatus(1);
        return user;
    }

    @Test
    void registerInsertsBcryptedUser() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        userService.register(new RegisterRequest("bohan", "secret123", "博涵"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("bohan");
        assertThat(saved.getPassword()).isNotEqualTo("secret123");
        assertThat(saved.getRole()).isEqualTo("USER");
    }

    @Test
    void registerLosesUniqueIndexRaceGracefully() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_users_username"));

        assertThatThrownBy(() -> userService.register(new RegisterRequest("bohan", "secret123", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> userService.register(new RegisterRequest("bohan", "secret123", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void loginWithWrongPasswordThrows() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());

        assertThatThrownBy(() -> userService.login(new LoginRequest("bohan", "wrong-pass")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void loginIssuesAccessAndRefreshTokens() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());

        TokenResponse tokens = userService.login(new LoginRequest("bohan", "secret123"));

        JwtCodec.Claims access = jwtCodec.decode(tokens.accessToken());
        JwtCodec.Claims refresh = jwtCodec.decode(tokens.refreshToken());
        assertThat(access.typ()).isEqualTo(JwtCodec.TYP_ACCESS);
        assertThat(access.subject()).isEqualTo(refresh.subject());
        assertThat(refresh.typ()).isEqualTo(JwtCodec.TYP_REFRESH);
        assertThat(access.expiresAt()).isAfter(refresh.issuedAt());
    }

    @Test
    void refreshWithAccessTokenIsRejected() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        TokenResponse tokens = userService.login(new LoginRequest("bohan", "secret123"));

        assertThatThrownBy(() -> userService.refresh(new com.zengbohan.aurora.user.dto.RefreshRequest(tokens.accessToken())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rotationInvalidatesTheUsedRefreshToken() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(userMapper.selectById(any())).thenReturn(activeUser());
        TokenResponse tokens = userService.login(new LoginRequest("bohan", "secret123"));

        userService.refresh(new com.zengbohan.aurora.user.dto.RefreshRequest(tokens.refreshToken()));

        // 旧 refresh 的 jti 被拉黑（一次一换）
        String usedJti = jwtCodec.decode(tokens.refreshToken()).jti();
        org.mockito.Mockito.verify(redisValues).set(
                eq(JwtCodec.BLACKLIST_KEY_PREFIX + usedJti), eq("1"), any());
    }

    @Test
    void reusedRefreshTokenIsRejectedViaBlacklist() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(userMapper.selectById(any())).thenReturn(activeUser());
        TokenResponse tokens = userService.login(new LoginRequest("bohan", "secret123"));

        userService.refresh(new com.zengbohan.aurora.user.dto.RefreshRequest(tokens.refreshToken()));

        // 第二次用同一张旧票：命中黑名单
        when(redis.hasKey(JwtCodec.BLACKLIST_KEY_PREFIX
                + jwtCodec.decode(tokens.refreshToken()).jti())).thenReturn(true);
        assertThatThrownBy(() -> userService.refresh(
                new com.zengbohan.aurora.user.dto.RefreshRequest(tokens.refreshToken())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refreshAfterLogoutIsRejectedBySessionInvalidation() {
        when(userMapper.selectById(any())).thenReturn(activeUser());
        // 登出写下的失效时间戳晚于旧票的 iat
        String oldRefresh = jwtCodec.encode(new JwtCodec.Claims(
                "1", "USER", "old-jti", JwtCodec.TYP_REFRESH,
                java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(604800)));
        when(redisValues.get("aurora:jwt:session-invalid-before:1"))
                .thenReturn(String.valueOf(java.time.Instant.now().getEpochSecond()));

        assertThatThrownBy(() -> userService.refresh(
                new com.zengbohan.aurora.user.dto.RefreshRequest(oldRefresh)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void freshLoginAfterLogoutCanRefresh() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(userMapper.selectById(any())).thenReturn(activeUser());
        // 失效时间戳已存在（此前登出过），但新票 iat 更晚
        when(redisValues.get("aurora:jwt:session-invalid-before:1"))
                .thenReturn(String.valueOf(java.time.Instant.now().minusSeconds(300).getEpochSecond()));
        when(redisValues.get(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key.startsWith("aurora:jwt:session-invalid-before:")
                    ? String.valueOf(java.time.Instant.now().minusSeconds(300).getEpochSecond())
                    : null;
        });

        TokenResponse tokens = userService.login(new LoginRequest("bohan", "secret123"));

        assertThatCode(() -> userService.refresh(
                new com.zengbohan.aurora.user.dto.RefreshRequest(tokens.refreshToken())))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownUserLoginFailsWithSameMessage() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> userService.login(new LoginRequest("nobody", "whatever")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.PARAM_ERROR.getCode())
                .hasMessage("用户名或密码错误");
    }

    @Test
    void disabledUserCannotLogin() {
        User user = activeUser();
        user.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> userService.login(new LoginRequest("bohan", "secret123")))
                .isInstanceOf(BusinessException.class);
    }
}
