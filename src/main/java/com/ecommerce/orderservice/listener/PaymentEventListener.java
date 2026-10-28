package com.ecommerce.orderservice.listener;

import com.ecommerce.orderservice.config.filter.RequestIdFilter;
import com.ecommerce.orderservice.service.PaymentStatusService;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Reads the queue order.payment-events. Every message updates the payment status of one order. */
@Component
public class PaymentEventListener {

    private final PaymentStatusService service;

    public PaymentEventListener(PaymentStatusService service) {
        this.service = service;
    }

    @RabbitListener(queues = "${events.payment-queue}")
    public void onEvent(Map<String, Object> body,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String eventName,
                        @Header(name = "messageId", required = false) String messageId,
                        @Header(name = "traceId", required = false) String traceId) {

        if (traceId != null) {
            MDC.put(RequestIdFilter.MDC_KEY, traceId);
        }
        try {
            // The event body is not logged. We only need the reference (the order number).
            Object reference = body == null ? null : body.get("reference");
            service.apply(parse(eventName, messageId), eventName, reference == null ? null : String.valueOf(reference));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    private UUID parse(String eventName, String messageId) {
        try {
            return UUID.fromString(String.valueOf(messageId));
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Event '" + eventName + "' has no valid messageId header");
        }
    }
}
