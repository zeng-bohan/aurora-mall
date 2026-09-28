package com.zengbohan.aurora.common;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.common.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {

    @GetMapping("/probe/ok")
    public Result<String> ok() {
        return Result.ok("hello");
    }

    @GetMapping("/probe/business-error")
    public Result<Void> businessError() {
        throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT, "商品 sku-1 库存不足");
    }

    @GetMapping("/probe/system-error")
    public Result<Void> systemError() {
        throw new IllegalStateException("boom");
    }

    @GetMapping("/probe/trace-id")
    public Result<String> traceId() {
        return Result.ok(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY));
    }
}
