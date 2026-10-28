package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.dto.AdminDtos.OrderCounts;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.dto.OrderDtos.OrderSummary;
import com.ecommerce.orderservice.dto.OrderDtos.PageResponse;
import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.service.AdminOrderService;
import com.ecommerce.orderservice.service.OrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Admin: all orders. SecurityConfig lets only ROLE_ADMIN reach /admin/**. */
@RestController
@RequestMapping("/api/v1/order/admin/orders")
public class AdminOrderController {

    private final AdminOrderService adminService;
    private final OrderService orderService;

    public AdminOrderController(AdminOrderService adminService, OrderService orderService) {
        this.adminService = adminService;
        this.orderService = orderService;
    }

    /** Filters: shipmentStatus, paymentStatus, q (order no, name, email, tracking number), from, to, overdue, page, size. */
    @GetMapping
    public PageResponse<OrderSummary> list(@RequestParam(required = false) ShipmentStatus shipmentStatus,
                                           @RequestParam(required = false) PaymentStatus paymentStatus,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "false") boolean overdue,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return adminService.list(shipmentStatus, paymentStatus, q, from, to, overdue, page, size);
    }

    /** The dashboard numbers: to ship, with courier, cash with couriers, overdue cash. */
    @GetMapping("/summary")
    public OrderCounts summary() {
        return adminService.counts();
    }

    /** One order with the customer's address and phone. This is also the data of the packing slip. */
    @GetMapping("/{orderNo}")
    public OrderDetail get(@PathVariable String orderNo) {
        return adminService.get(orderNo);
    }

    @PostMapping("/{orderNo}/cancel")
    public OrderDetail cancel(@PathVariable String orderNo) {
        return orderService.cancelByAdmin(orderNo);
    }
}
