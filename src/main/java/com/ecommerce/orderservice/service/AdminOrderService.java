package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.ShopProperties;
import com.ecommerce.orderservice.dto.AdminDtos.OrderCounts;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.dto.OrderDtos.OrderSummary;
import com.ecommerce.orderservice.dto.OrderDtos.PageResponse;
import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.repository.OrderRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Admin: the order list with both status columns, the dashboard counts, one order (also the packing slip data). */
@Service
@Transactional(readOnly = true)
public class AdminOrderService {

    private final OrderRepository orders;
    private final OrderMapper mapper;
    private final Paging paging;
    private final ShopProperties shop;

    public AdminOrderService(OrderRepository orders, OrderMapper mapper, Paging paging, ShopProperties shop) {
        this.orders = orders;
        this.mapper = mapper;
        this.paging = paging;
        this.shop = shop;
    }

    /**
     * @param overdue true = only delivered parcels whose cash the courier has not paid after the configured days
     * @param from    first day (inclusive, UTC)
     * @param to      last day (inclusive, UTC)
     */
    public PageResponse<OrderSummary> list(ShipmentStatus shipmentStatus, PaymentStatus paymentStatus, String q,
                                           LocalDate from, LocalDate to, boolean overdue, Integer page, Integer size) {
        Instant createdFrom = from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant createdBefore = to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant deliveredBefore = overdue ? overdueLimit() : null;

        var result = orders.findAll(
                OrderSpecs.filter(shipmentStatus, paymentStatus, q, createdFrom, createdBefore, deliveredBefore),
                paging.of(page, size, Sort.unsorted()));
        return paging.map(result, mapper::toSummary);
    }

    public OrderCounts counts() {
        Instant limit = overdueLimit();
        return new OrderCounts(
                orders.countByShipmentStatus(ShipmentStatus.NOT_SHIPPED),
                orders.countByShipmentStatus(ShipmentStatus.IN_TRANSIT),
                orders.countByPaymentStatus(PaymentStatus.COLLECTED),
                orders.sumTotalByPaymentStatus(PaymentStatus.COLLECTED),
                orders.countOverdue(limit),
                orders.sumOverdue(limit),
                shop.overdueCashDays(),
                shop.currency());
    }

    public OrderDetail get(String orderNo) {
        return orders.findByOrderNo(orderNo).map(mapper::toDetail)
                .orElseThrow(() -> ApiException.notFound("Order not found."));
    }

    private Instant overdueLimit() {
        return Instant.now().minus(shop.overdueCashDays(), ChronoUnit.DAYS);
    }
}
