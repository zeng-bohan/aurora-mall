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

    /** Business misses (unknown id) come back as HTTP 200 with code 40400. */
    @GetMapping("/products/{id}")
    Result<ProductSnapshot> detail(@PathVariable("id") long id);

    @GetMapping("/products/batch")
    Result<List<ProductSnapshot>> batch(@RequestParam("ids") List<Long> ids);
}
