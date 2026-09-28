package com.zengbohan.aurora.cart.controller;

import com.zengbohan.aurora.cart.dto.CartItem;
import com.zengbohan.aurora.cart.dto.CartItemRequest;
import com.zengbohan.aurora.cart.service.CartService;
import com.zengbohan.aurora.common.result.Result;
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
@RequestMapping("/carts")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Result<List<CartItem>> view(@RequestHeader("X-User-Id") long userId) {
        return Result.ok(cartService.view(userId));
    }

    @PostMapping("/items")
    public Result<Void> add(@RequestHeader("X-User-Id") long userId,
                            @Valid @RequestBody CartItemRequest request) {
        cartService.add(userId, request);
        return Result.ok();
    }

    @PutMapping("/items")
    public Result<Void> setQuantity(@RequestHeader("X-User-Id") long userId,
                                    @Valid @RequestBody CartItemRequest request) {
        cartService.setQuantity(userId, request);
        return Result.ok();
    }

    @DeleteMapping("/items/{skuId}")
    public Result<Void> remove(@RequestHeader("X-User-Id") long userId,
                               @PathVariable long skuId) {
        cartService.remove(userId, skuId);
        return Result.ok();
    }

    @DeleteMapping
    public Result<Void> clear(@RequestHeader("X-User-Id") long userId) {
        cartService.clear(userId);
        return Result.ok();
    }
}
