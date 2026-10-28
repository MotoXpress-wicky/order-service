package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.ProcessedMessage;
import com.ecommerce.orderservice.event.OrderEvents;
import com.ecommerce.orderservice.listener.InvalidEventException;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.repository.ProcessedMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the payment_status of an order up to date. The order never decides it: only payment events do (M3).
 *
 *   payment.collected  -> COLLECTED   (allowed from PENDING)
 *   payment.succeeded  -> PAID        (allowed from COLLECTED)
 *   payment.cancelled  -> CANCELLED   (allowed from PENDING)
 *
 * RabbitMQ can deliver a message twice, so the messageId is written to processed_messages in the SAME transaction
 * as the change. A second delivery finds it and does nothing.
 * A move that the state diagram does not allow (for example an old message arriving late) is ignored and logged.
 */
@Slf4j
@Service
public class PaymentStatusService {

    private static final Map<String, PaymentStatus> TARGET = Map.of(
            OrderEvents.PAYMENT_COLLECTED, PaymentStatus.COLLECTED,
            OrderEvents.PAYMENT_SUCCEEDED, PaymentStatus.PAID,
            OrderEvents.PAYMENT_CANCELLED, PaymentStatus.CANCELLED);

    /** From which statuses each target may be reached (docs/mvp/09-state-diagrams.md, diagram 2). */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_FROM = Map.of(
            PaymentStatus.COLLECTED, EnumSet.of(PaymentStatus.PENDING),
            PaymentStatus.PAID, EnumSet.of(PaymentStatus.COLLECTED),
            PaymentStatus.CANCELLED, EnumSet.of(PaymentStatus.PENDING));

    private final OrderRepository orders;
    private final ProcessedMessageRepository processed;

    public PaymentStatusService(OrderRepository orders, ProcessedMessageRepository processed) {
        this.orders = orders;
        this.processed = processed;
    }

    /** @param reference the order number (payment-service calls it "reference") */
    @Transactional
    public void apply(UUID messageId, String eventName, String reference) {
        PaymentStatus target = TARGET.get(eventName);
        if (target == null) {
            throw new InvalidEventException("Event '" + eventName + "' is not a payment event this service handles");
        }
        if (reference == null || reference.isBlank()) {
            throw new InvalidEventException("Event '" + eventName + "' has no reference");
        }
        if (processed.existsById(messageId)) {
            log.info("Skipped '{}' (message {}): already handled", eventName, messageId);
            return;
        }

        Order order = orders.findByOrderNoForUpdate(reference.trim())
                .orElseThrow(() -> new InvalidEventException("Event '" + eventName + "' is for an unknown order"));

        if (order.getPaymentStatus() == target) {
            log.info("Order {} is already {}", order.getOrderNo(), target);
        } else if (ALLOWED_FROM.get(target).contains(order.getPaymentStatus())) {
            log.info("Order {} payment status {} -> {}", order.getOrderNo(), order.getPaymentStatus(), target);
            order.setPaymentStatus(target);
        } else {
            log.warn("Ignored '{}' for order {}: payment status {} cannot move to {}",
                    eventName, order.getOrderNo(), order.getPaymentStatus(), target);
        }

        // Same transaction as the change above: either both are saved or neither.
        processed.save(new ProcessedMessage(messageId));
    }
}
