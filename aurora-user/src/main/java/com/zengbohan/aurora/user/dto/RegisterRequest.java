package com.zengbohan.aurora.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 4, max = 32) String username,
        @NotBlank @Size(min = 6, max = 64) String password,
        @Size(max = 64) String nickname) {
}
