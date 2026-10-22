package com.zengbohan.aurora.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class AddressDtos {

    private AddressDtos() {
    }

    public record AddressRequest(
            @NotBlank @Size(max = 32) String receiverName,
            @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone,
            @NotBlank @Size(max = 32) String province,
            @NotBlank @Size(max = 32) String city,
            @NotBlank @Size(max = 32) String district,
            @NotBlank @Size(max = 200) String detail,
            @Size(max = 12) String postalCode,
            Boolean isDefault) {
    }

    public record AddressView(long id, long userId, String receiverName, String phone,
                              String province, String city, String district, String detail,
                              String postalCode, boolean isDefault,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record UserView(long id, String username, String nickname, String role,
                           int status, LocalDateTime createdAt) {
    }
}
