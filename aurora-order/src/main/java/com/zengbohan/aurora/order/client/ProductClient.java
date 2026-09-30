package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


/** Product price/status lookup for order pricing. */
@FeignClient(name = "aurora-product")
public interface ProductClient {


    @GetMapping("/products/{id}")
    Result<ProductSnapshot> detail(@PathVariable("id") long id);
}
