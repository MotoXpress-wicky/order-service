package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.ShopProperties;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** Makes a readable, unique order number like MX-10238. The number comes from a database sequence (never repeats). */
@Component
public class OrderNumberGenerator {

    private final EntityManager entityManager;
    private final String prefix;

    public OrderNumberGenerator(EntityManager entityManager, ShopProperties shop) {
        this.entityManager = entityManager;
        this.prefix = shop.orderNumberPrefix();
    }

    public String next() {
        Number number = (Number) entityManager.createNativeQuery("select nextval('order_no_seq')").getSingleResult();
        return prefix + number.longValue();
    }
}
