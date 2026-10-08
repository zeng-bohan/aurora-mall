package com.zengbohan.aurora.common.result;

import com.zengbohan.aurora.common.exception.ErrorCode;

/**
 * 统一的 API 响应信封。业务错误走 HTTP 200 + 非零 code；
 * 基础设施故障经 GlobalExceptionHandler 返回 HTTP 5xx。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
