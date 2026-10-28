package com.ecommerce.orderservice.event;

import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.OrderItem;
import com.ecommerce.orderservice.entity.Shipment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The names and bodies of the events, exactly as in docs/mvp/07-events.md.
 * Money is a string with 2 decimals; times are ISO-8601 in UTC; every order event has
 * userId, email and name (for notification-service) and "reference" (for payment-service, which never says "order").
 */
public final class OrderEvents {

    // events we publish
    public static final String PLACED = "order.placed";
    public static final String SHIPPED = "order.shipped";
    public static final String DELIVERED = "order.delivered";
    public static final String RETURNED = "order.returned";
    public static final String CANCELLED = "order.cancelled";

    // events we listen to
    public static final String PAYMENT_COLLECTED = "payment.collected";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_CANCELLED = "payment.cancelled";

    private OrderEvents() {
    }

    public static Map<String, Object> placed(Order order) {
        Map<String, Object> body = common(order);
        body.put("paymentMethod", order.getPaymentMethod().name());
        body.put("itemsTotal", money(order.getItemsTotal()));
        body.put("deliveryFee", money(order.getDeliveryFee()));
        body.put("total", money(order.getTotal()));
        body.put("currency", order.getCurrency());

        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("partNumber", item.getPartNumber());
            line.put("name", item.getProductName());
            line.put("quantity", item.getQuantity());
            line.put("unitPrice", money(item.getUnitPrice()));
            line.put("lineTotal", money(item.getLineTotal()));
            lines.add(line);
        }
        body.put("lines", lines);
        return body;
    }

    public static Map<String, Object> shipped(Order order, Shipment shipment) {
        Map<String, Object> body = withCourier(order, shipment);
        body.put("shippedAt", time(shipment.getShippedAt()));
        return body;
    }

    public static Map<String, Object> delivered(Order order, Shipment shipment) {
        Map<String, Object> body = withCourier(order, shipment);
        body.put("deliveredAt", time(shipment.getDeliveredAt()));
        return body;
    }

    public static Map<String, Object> returned(Order order, Shipment shipment) {
        Map<String, Object> body = withCourier(order, shipment);
        body.put("reason", shipment.getReturnReason().name());
        body.put("returnedAt", time(shipment.getReturnedAt()));
        return body;
    }

    public static Map<String, Object> cancelled(Order order) {
        Map<String, Object> body = common(order);
        body.put("cancelledBy", order.getCancelledBy().name());
        body.put("cancelledAt", time(order.getCancelledAt()));
        return body;
    }

    // ---------- helpers ----------

    private static Map<String, Object> common(Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNo", order.getOrderNo());
        body.put("reference", order.getOrderNo());
        body.put("userId", String.valueOf(order.getUserId()));
        body.put("email", order.getCustomerEmail());
        body.put("name", order.getCustomerName());
        return body;
    }

    private static Map<String, Object> withCourier(Order order, Shipment shipment) {
        Map<String, Object> body = common(order);
        body.put("courier", shipment.getCourier());
        body.put("trackingNumber", shipment.getTrackingNumber());
        return body;
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String time(Instant instant) {
        return instant.toString();
    }
}
