package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.dto.CurrentUser;
import com.ecommerce.orderservice.dto.OrderDtos.CheckoutRequest;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer: checkout, my orders, cancel, "I received it". A login is needed (BR1). */
@RestController
@RequestMapping("/api/v1/order/orders")
public class OrderController {

    private final CheckoutService checkoutService;

    public OrderController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /** Cash on delivery. Answers 201 with the order, including the order number and the cash to pay (total). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetail checkout(@AuthenticationPrincipal CurrentUser customer,
                                @Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(customer, request);
    }
}
