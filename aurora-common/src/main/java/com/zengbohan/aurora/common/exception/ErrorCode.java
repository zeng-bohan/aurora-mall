package com.zengbohan.aurora.common.exception;

/**
 * Business error codes. Segments: 0 success, 1xxxx system, 2xxxx inventory,
 * 4xxxx http-semantics (auth/forbidden/not-found/conflict).
 */
public enum ErrorCode {
    SUCCESS(0, "success"),
    SYSTEM_ERROR(10000, "系统繁忙，请稍后重试"),
    PARAM_ERROR(10001, "请求参数不合法"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权访问"),
    NOT_FOUND(40400, "资源不存在"),
    DUPLICATE_REQUEST(40900, "请勿重复提交"),
    INVENTORY_INSUFFICIENT(20001, "库存不足");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
