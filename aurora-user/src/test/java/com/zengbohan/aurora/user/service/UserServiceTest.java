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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserMapper userMapper;
    private UserService userService;
    private final JwtCodec jwtCodec = new JwtCodec("test-secret-0123456789abcdef-0123456789");

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userService = new UserService(userMapper, jwtCodec, 1800, 604800);
    }

    private User activeUser() {
        User user = new User();
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
    void disabledUserCannotLogin() {
        User user = activeUser();
        user.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> userService.login(new LoginRequest("bohan", "secret123")))
                .isInstanceOf(BusinessException.class);
    }
}
