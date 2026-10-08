package com.zengbohan.aurora.common.result;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 服务间调用（Feign / HTTP 信封）的结果解包。
 * <p>
 * 只收敛与业务语义无关的两类失败——调用抛异常、响应缺失——统一翻译为
 * {@code SYSTEM_ERROR("XX服务不可用")}；业务码（NOT_FOUND、库存不足等）
 * 仍留在各调用点判断，避免把某个服务的业务语义塞进公共工具。
 */
public final class RemoteCall {

    private static final Logger log = LoggerFactory.getLogger(RemoteCall.class);

    private RemoteCall() {
    }

    /**
     * 发起一次远程调用，返回原始信封。
     *
     * @param serviceLabel 服务名（用于异常文案，"商品" → "商品服务不可用"）
     * @param context      日志上下文（如 "sku 12"、"order 1001"），失败时随异常一起记录
     */
    public static <T> Result<T> invoke(String serviceLabel, String context, Supplier<Result<T>> call) {
        Result<T> result;
        try {
            result = call.get();
        } catch (RuntimeException e) {
            log.warn("{} service call failed ({})", serviceLabel, context, e);
            throw unavailable(serviceLabel);
        }
        if (result == null) {
            throw unavailable(serviceLabel);
        }
        return result;
    }

    // 要求调用成功且带数据；非 SUCCESS 或 data 为空一律视为服务不可用
    public static <T> T data(Result<T> result, String serviceLabel) {
        if (result.code() != ErrorCode.SUCCESS.getCode() || result.data() == null) {
            throw unavailable(serviceLabel);
        }
        return result.data();
    }

    // 只要求调用成功（Result<Void> 等不关心数据的场景）
    public static void requireSuccess(Result<?> result, String serviceLabel) {
        if (result.code() != ErrorCode.SUCCESS.getCode()) {
            throw unavailable(serviceLabel);
        }
    }

    private static BusinessException unavailable(String serviceLabel) {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, serviceLabel + "服务不可用");
    }
}
