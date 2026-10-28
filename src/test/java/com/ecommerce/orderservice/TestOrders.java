package com.ecommerce.orderservice;

import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.OrderItem;

import java.math.BigDecimal;

/** Builds orders for tests (not saved). */
public final class TestOrders {

    private TestOrders() {
    }

    public static Order order(String orderNo, long userId) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setCustomerName("Nimal Perera");
        order.setCustomerEmail("nimal@example.com");
        order.setPhone("0771234567");
        order.setAddressLine("12 Galle Road");
        order.setCity("Colombo");
        order.setDistrict("Colombo");
        order.setItemsTotal(new BigDecimal("9000.00"));
        order.setDeliveryFee(new BigDecimal("500.00"));
        order.setTotal(new BigDecimal("9500.00"));
        order.setCurrency("LKR");
        order.addItem(new OrderItem(1L, "123HG", "Sprocket", new BigDecimal("4500.00"), 2, new BigDecimal("9000.00")));
        return order;
    }
}
