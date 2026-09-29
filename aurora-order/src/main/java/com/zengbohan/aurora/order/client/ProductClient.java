package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;

/** Product price/status lookup for order pricing. */
@FeignClient(name = "aurora-product")
public interface ProductClient {

    record ProductInfo(Long id, String title, BigDecimal price, Integer stock, Integer status) {
    }

    @GetMapping("/products/{id}")
    Result<ProductInfo> detail(@PathVariable("id") long id);
}
