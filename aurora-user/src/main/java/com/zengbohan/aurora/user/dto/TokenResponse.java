package com.zengbohan.aurora.user.dto;

public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
}
