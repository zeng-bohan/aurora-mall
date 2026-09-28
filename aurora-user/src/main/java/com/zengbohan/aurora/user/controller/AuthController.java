package com.zengbohan.aurora.user.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.user.dto.LoginRequest;
import com.zengbohan.aurora.user.dto.RefreshRequest;
import com.zengbohan.aurora.user.dto.RegisterRequest;
import com.zengbohan.aurora.user.dto.TokenResponse;
import com.zengbohan.aurora.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(userService.register(request));
    }

    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(userService.login(request));
    }

    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(userService.refresh(request));
    }
}
