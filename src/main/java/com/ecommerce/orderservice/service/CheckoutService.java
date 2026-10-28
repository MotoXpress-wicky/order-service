package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.client.ProductClient;
import com.ecommerce.orderservice.client.ProductClient.ReservedLine;
import com.ecommerce.orderservice.client.ProductClient.WantedLine;
import com.ecommerce.orderservice.config.ShopProperties;
import com.ecommerce.orderservice.dto.CurrentUser;
import com.ecommerce.orderservice.dto.OrderDtos.CheckoutRequest;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.OrderItem;
import com.ecommerce.orderservice.event.EventPublisher;
import com.ecommerce.orderservice.event.OrderEvents;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.exception.ErrorCode;
import com.ecommerce.orderservice.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places a cash-on-delivery order (docs/mvp/06-dynamic/checkout.md, flow 1):
 * 1) check the lines  2) make the order number  3) reserve stock and get the real prices from product-service
 * 4) add the delivery fee  5) save the order  6) publish order.placed (after the commit).
 *
 * If saving fails after the stock was taken, the stock is given back. Prices never come from the browser.
 */
@Slf4j
@Service
public class CheckoutService {

    private final ProductClient productClient;
    private final OrderRepository orders;
    private final OrderNumberGenerator numbers;
    private final OrderMapper mapper;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final ShopProperties shop;

    public CheckoutService(ProductClient productClient, OrderRepository orders, OrderNumberGenerator numbers,
                           OrderMapper mapper, EventPublisher events, TransactionTemplate tx, ShopProperties shop) {
        this.productClient = productClient;
        this.orders = orders;
        this.numbers = numbers;
        this.mapper = mapper;
        this.events = events;
        this.tx = tx;
        this.shop = shop;
    }

    public OrderDetail checkout(CurrentUser customer, CheckoutRequest request) {
        List<WantedLine> wanted = mergeAndCheck(request);

        String orderNo = tx.execute(status -> numbers.next());
        List<ReservedLine> reserved = productClient.reserve(orderNo, wanted);

        try {
            return tx.execute(status -> {
                Order order = build(orderNo, customer, request, reserved);
                orders.save(order);
                events.publish(OrderEvents.PLACED, OrderEvents.placed(order));
                log.info("Placed order {} for user {}", orderNo, customer.id());
                return mapper.toDetail(order);
            });
        } catch (RuntimeException e) {
            log.error("Saving order {} failed. Giving the stock back.", orderNo, e);
            releaseQuietly(orderNo);
            throw e;
        }
    }

    // ---------- helpers ----------

    /** The same part twice becomes one line. Also checks the size limits from application.yaml. */
    private List<WantedLine> mergeAndCheck(CheckoutRequest request) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        request.lines().forEach(line -> merged.merge(line.productId(), line.quantity(), Integer::sum));

        if (merged.size() > shop.maxLinesPerOrder()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "An order can have at most " + shop.maxLinesPerOrder() + " different parts.");
        }
        merged.forEach((productId, quantity) -> {
            if (quantity > shop.maxQuantityPerLine()) {
                throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                        "You can order at most " + shop.maxQuantityPerLine() + " pieces of one part.");
            }
        });
        return merged.entrySet().stream().map(e -> new WantedLine(e.getKey(), e.getValue())).toList();
    }

    private Order build(String orderNo, CurrentUser customer, CheckoutRequest request, List<ReservedLine> reserved) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(customer.id());
        order.setCustomerName(customer.name() == null || customer.name().isBlank() ? customer.email() : customer.name());
        order.setCustomerEmail(customer.email());
        order.setPhone(request.address().phone().trim());
        order.setAddressLine(request.address().addressLine().trim());
        order.setCity(request.address().city().trim());
        order.setDistrict(request.address().district().trim());
        order.setCurrency(shop.currency());

        BigDecimal itemsTotal = BigDecimal.ZERO;
        for (ReservedLine line : reserved) {
            order.addItem(new OrderItem(line.productId(), line.partNumber(), line.name(), line.unitPrice(),
                    line.quantity(), line.lineTotal()));
            itemsTotal = itemsTotal.add(line.lineTotal());
        }
        order.setItemsTotal(itemsTotal);
        order.setDeliveryFee(shop.deliveryFee());
        order.setTotal(itemsTotal.add(shop.deliveryFee()));
        return order;
    }

    private void releaseQuietly(String orderNo) {
        try {
            productClient.release(orderNo);
        } catch (RuntimeException e) {
            log.error("Could not give back the stock of order {}. An admin must correct it.", orderNo, e);
        }
    }
}
