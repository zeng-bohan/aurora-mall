package com.zengbohan.aurora.user.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.user.dto.AddressDtos.AddressView;
import com.zengbohan.aurora.user.dto.AddressDtos.UserView;
import com.zengbohan.aurora.user.service.AddressService;
import com.zengbohan.aurora.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/users")
public class UserInternalController {

    private final UserService userService;
    private final AddressService addressService;

    public UserInternalController(UserService userService, AddressService addressService) {
        this.userService = userService;
        this.addressService = addressService;
    }

    @GetMapping("/{id}")
    public Result<UserView> user(@PathVariable long id) {
        return Result.ok(userService.view(id));
    }

    @GetMapping("/list")
    public Result<List<UserView>> users() {
        return Result.ok(userService.listUsers());
    }

    @GetMapping("/{userId}/addresses/{addressId}")
    public Result<AddressView> address(@PathVariable long userId, @PathVariable long addressId) {
        return Result.ok(addressService.owned(userId, addressId));
    }

    @GetMapping("/{userId}/addresses/default")
    public Result<AddressView> defaultAddress(@PathVariable long userId) {
        return Result.ok(addressService.defaultFor(userId));
    }
}
