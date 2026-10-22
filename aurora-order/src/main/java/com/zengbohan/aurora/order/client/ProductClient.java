package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "aurora-product")
public interface ProductClient {

    @GetMapping("/products/skus/{id}")
    Result<ProductSnapshot> snapshot(@PathVariable("id") long id);

    @GetMapping("/products/batch")
    Result<List<ProductSnapshot>> batch(@RequestParam("ids") List<Long> ids);
}
