package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "aurora-user", contextId = "userAddressClient")
public interface AddressClient {

    record AddressView(long id, long userId, String receiverName, String phone,
                       String province, String city, String district, String detail,
                       String postalCode, boolean isDefault) {
    }

    @GetMapping("/internal/users/{userId}/addresses/{addressId}")
    Result<AddressView> address(@PathVariable("userId") long userId,
                                @PathVariable("addressId") long addressId);

    @GetMapping("/internal/users/{userId}/addresses/default")
    Result<AddressView> defaultAddress(@PathVariable("userId") long userId);
}
