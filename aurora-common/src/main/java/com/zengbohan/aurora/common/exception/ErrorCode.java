package com.zengbohan.aurora.common.exception;

/**
 * 业务错误码。分段规则：0 成功，1xxxx 系统，2xxxx 库存，3xxxx 秒杀，
 * 4xxxx HTTP 语义（鉴权/禁止/不存在/冲突）。
 */
public enum ErrorCode {
    SUCCESS(0, "success"),
    SYSTEM_ERROR(10000, "系统繁忙，请稍后重试"),
    PARAM_ERROR(10001, "请求参数不合法"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权访问"),
    NOT_FOUND(40400, "资源不存在"),
    DUPLICATE_REQUEST(40900, "请勿重复提交"),
    // 与网关限流过滤器写回的业务码一致：客户端在网关被拦、在服务侧被拦看到同一个码
    RATE_LIMITED(42900, "请求过于频繁，请稍后再试"),
    INVENTORY_INSUFFICIENT(20001, "库存不足"),
    SECKILL_NOT_STARTED(30001, "秒杀尚未开始"),
    SECKILL_ENDED(30002, "秒杀已结束"),
    SECKILL_SOLD_OUT(30003, "已售罄"),
    SECKILL_ALREADY_BOUGHT(30004, "您已参与过该秒杀"),
    SECKILL_NOT_READY(30005, "秒杀活动尚未就绪");

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
