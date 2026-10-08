package com.zengbohan.aurora.cart.client;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "aurora-product")
public interface ProductClient {

    // 业务未命中（未知 id）以 HTTP 200 + code 40400 返回。
    @GetMapping("/products/{id}")
    Result<ProductSnapshot> detail(@PathVariable("id") long id);

    @GetMapping("/products/batch")
    Result<List<ProductSnapshot>> batch(@RequestParam("ids") List<Long> ids);
}
