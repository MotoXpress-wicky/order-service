package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.TestOrders;
import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.listener.InvalidEventException;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.repository.ProcessedMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentStatusServiceTest {

    @Autowired
    PaymentStatusService service;
    @Autowired
    OrderRepository orders;
    @Autowired
    ProcessedMessageRepository processed;

    @AfterEach
    void clean() {
        orders.deleteAll();
        processed.deleteAll();
    }

    private Order saved(PaymentStatus status) {
        Order order = TestOrders.order("MX-10001", 7);
        order.setPaymentStatus(status);
        return orders.save(order);
    }

    private PaymentStatus statusNow() {
        return orders.findByOrderNo("MX-10001").orElseThrow().getPaymentStatus();
    }

    @Test
    void theWholeHappyPathPendingCollectedPaid() {
        saved(PaymentStatus.PENDING);

        service.apply(UUID.randomUUID(), "payment.collected", "MX-10001");
        assertThat(statusNow()).isEqualTo(PaymentStatus.COLLECTED);

        service.apply(UUID.randomUUID(), "payment.succeeded", "MX-10001");
        assertThat(statusNow()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void cancelledMovesPendingToCancelled() {
        saved(PaymentStatus.PENDING);

        service.apply(UUID.randomUUID(), "payment.cancelled", "MX-10001");

        assertThat(statusNow()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void theSameMessageTwiceIsHandledOnce() {
        saved(PaymentStatus.PENDING);
        UUID id = UUID.randomUUID();

        service.apply(id, "payment.collected", "MX-10001");
        // pretend something changed the order in between, to prove the second delivery does nothing at all
        Order order = orders.findByOrderNo("MX-10001").orElseThrow();
        order.setPaymentStatus(PaymentStatus.PENDING);
        orders.save(order);
        service.apply(id, "payment.collected", "MX-10001");

        assertThat(statusNow()).isEqualTo(PaymentStatus.PENDING);
        assertThat(processed.count()).isEqualTo(1);
    }

    @Test
    void aMoveThatTheDiagramDoesNotAllowIsIgnoredButRemembered() {
        saved(PaymentStatus.CANCELLED);
        UUID id = UUID.randomUUID();

        service.apply(id, "payment.collected", "MX-10001");

        assertThat(statusNow()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(processed.existsById(id)).isTrue();
    }

    @Test
    void paidCannotComeBeforeCollected() {
        saved(PaymentStatus.PENDING);

        service.apply(UUID.randomUUID(), "payment.succeeded", "MX-10001");

        assertThat(statusNow()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void anUnknownOrderGoesToTheDeadLetterQueue() {
        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), "payment.collected", "MX-99999"))
                .isInstanceOf(InvalidEventException.class);
        assertThat(processed.count()).as("nothing remembered, so a person can fix it and replay").isZero();
    }

    @Test
    void badEventsGoToTheDeadLetterQueue() {
        saved(PaymentStatus.PENDING);

        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), "payment.collected", null))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), "payment.collected", "  "))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), "payment.something", "MX-10001"))
                .isInstanceOf(InvalidEventException.class);
    }
}
