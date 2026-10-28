package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.dto.CurrentUser;
import com.ecommerce.orderservice.dto.OrderDtos.CheckoutRequest;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.dto.OrderDtos.OrderSummary;
import com.ecommerce.orderservice.dto.OrderDtos.PageResponse;
import com.ecommerce.orderservice.service.CheckoutService;
import com.ecommerce.orderservice.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer: checkout, my orders, cancel, "I received it". A login is needed (BR1). Only your own orders are visible. */
@RestController
@RequestMapping("/api/v1/order/orders")
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;

    public OrderController(CheckoutService checkoutService, OrderService orderService) {
        this.checkoutService = checkoutService;
        this.orderService = orderService;
    }

    /** Cash on delivery. Answers 201 with the order, including the order number and the cash to pay (total). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetail checkout(@AuthenticationPrincipal CurrentUser customer,
                                @Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(customer, request);
    }

    /** My orders, newest first. */
    @GetMapping
    public PageResponse<OrderSummary> myOrders(@AuthenticationPrincipal CurrentUser customer,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return orderService.myOrders(customer.id(), page, size);
    }

    @GetMapping("/{orderNo}")
    public OrderDetail myOrder(@AuthenticationPrincipal CurrentUser customer, @PathVariable String orderNo) {
        return orderService.myOrder(customer.id(), orderNo);
    }

    /** Only before the order is shipped. */
    @PostMapping("/{orderNo}/cancel")
    public OrderDetail cancel(@AuthenticationPrincipal CurrentUser customer, @PathVariable String orderNo) {
        return orderService.cancelByCustomer(customer.id(), orderNo);
    }

    /** "I received it": saves the time, changes no status. */
    @PostMapping("/{orderNo}/received")
    public OrderDetail received(@AuthenticationPrincipal CurrentUser customer, @PathVariable String orderNo) {
        return orderService.markReceived(customer.id(), orderNo);
    }
}
