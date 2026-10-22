package com.zengbohan.aurora.user.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.user.dto.AddressDtos.AddressRequest;
import com.zengbohan.aurora.user.dto.AddressDtos.AddressView;
import com.zengbohan.aurora.user.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public Result<List<AddressView>> list(@RequestHeader("X-User-Id") long userId) {
        return Result.ok(addressService.list(userId));
    }

    @PostMapping
    public Result<Long> create(@RequestHeader("X-User-Id") long userId,
                               @Valid @RequestBody AddressRequest request) {
        return Result.ok(addressService.create(userId, request));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@RequestHeader("X-User-Id") long userId,
                               @PathVariable long id,
                               @Valid @RequestBody AddressRequest request) {
        addressService.update(userId, id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Id") long userId, @PathVariable long id) {
        addressService.delete(userId, id);
        return Result.ok();
    }

    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@RequestHeader("X-User-Id") long userId, @PathVariable long id) {
        addressService.setDefault(userId, id);
        return Result.ok();
    }
}
