package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.client.ProductClient;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.dto.OrderDtos.OrderSummary;
import com.ecommerce.orderservice.dto.OrderDtos.PageResponse;
import com.ecommerce.orderservice.entity.Enums.CancelledBy;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.event.EventPublisher;
import com.ecommerce.orderservice.event.OrderEvents;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.exception.ErrorCode;
import com.ecommerce.orderservice.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a customer can do with orders: list, read, cancel, "I received it". Also the cancel that an admin uses.
 * A customer only ever sees their own orders. Someone else's order looks like it does not exist (404).
 */
@Slf4j
@Service
public class OrderService {

    private static final Set<ShipmentStatus> CAN_CONFIRM_RECEIPT = EnumSet.of(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED);

    private final OrderRepository orders;
    private final ProductClient productClient;
    private final OrderMapper mapper;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Paging paging;

    public OrderService(OrderRepository orders, ProductClient productClient, OrderMapper mapper,
                        EventPublisher events, TransactionTemplate tx, Paging paging) {
        this.orders = orders;
        this.productClient = productClient;
        this.mapper = mapper;
        this.events = events;
        this.tx = tx;
        this.paging = paging;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> myOrders(Long userId, Integer page, Integer size) {
        var result = orders.findByUserIdOrderByCreatedAtDescIdDesc(userId, paging.of(page, size, Sort.unsorted()));
        return paging.map(result, mapper::toSummary);
    }

    @Transactional(readOnly = true)
    public OrderDetail myOrder(Long userId, String orderNo) {
        return mapper.toDetail(findOwned(orderNo, userId));
    }

    /** A customer cancels their own order. */
    public OrderDetail cancelByCustomer(Long userId, String orderNo) {
        return cancel(orderNo, userId, CancelledBy.CUSTOMER);
    }

    /** An admin cancels any order (ownerUserId = null means "not checked"). */
    public OrderDetail cancelByAdmin(String orderNo) {
        return cancel(orderNo, null, CancelledBy.ADMIN);
    }

    /**
     * Cancel only before shipping (BR9). Steps, all in one transaction that holds the order row lock:
     * check the status -> give the stock back at product-service -> mark CANCELLED -> publish order.cancelled.
     * If product-service is down the cancel fails and NOTHING changes. The customer tries again (docs/mvp/06-dynamic/checkout.md).
     * Giving stock back twice is harmless (product-service ignores the second call).
     */
    private OrderDetail cancel(String orderNo, Long ownerUserId, CancelledBy by) {
        return tx.execute(status -> {
            Order order = orders.findByOrderNoForUpdate(orderNo)
                    .filter(found -> ownerUserId == null || found.getUserId().equals(ownerUserId))
                    .orElseThrow(() -> ApiException.notFound("Order not found."));

            if (order.getShipmentStatus() != ShipmentStatus.NOT_SHIPPED) {
                throw ApiException.conflict(ErrorCode.INVALID_ORDER_STATUS,
                        "Only an order that is not shipped yet can be cancelled. This one is " + order.getShipmentStatus() + ".");
            }

            productClient.release(orderNo);

            order.setShipmentStatus(ShipmentStatus.CANCELLED);
            order.setCancelledBy(by);
            order.setCancelledAt(Instant.now());
            orders.save(order);

            events.publish(OrderEvents.CANCELLED, OrderEvents.cancelled(order));
            log.info("Order {} was cancelled by {}", orderNo, by);
            return mapper.toDetail(order);
        });
    }

    /** "I received it" is evidence only (M21): it saves the time and changes no status. */
    @Transactional
    public OrderDetail markReceived(Long userId, String orderNo) {
        Order order = findOwned(orderNo, userId);

        if (!CAN_CONFIRM_RECEIPT.contains(order.getShipmentStatus())) {
            throw ApiException.conflict(ErrorCode.INVALID_ORDER_STATUS,
                    "You can say you received an order only after it was sent. This one is " + order.getShipmentStatus() + ".");
        }
        if (order.getCustomerConfirmedAt() == null) {
            order.setCustomerConfirmedAt(Instant.now());
        }
        return mapper.toDetail(order);
    }

    private Order findOwned(String orderNo, Long userId) {
        return orders.findByOrderNo(orderNo)
                .filter(order -> order.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Order not found."));
    }
}
