package com.zengbohan.aurora.seckill.client;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "aurora-product")
public interface ProductClient {

    @GetMapping("/products/skus/{id}")
    Result<ProductSnapshot> snapshot(@PathVariable("id") long id);
}
