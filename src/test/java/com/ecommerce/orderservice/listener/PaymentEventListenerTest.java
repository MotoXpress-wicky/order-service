package com.ecommerce.orderservice.listener;

import com.ecommerce.orderservice.service.PaymentStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The listener passes the right values on, and the queue is bound to exactly the three payment events. */
@SpringBootTest
class PaymentEventListenerTest {

    @Autowired
    @Qualifier("paymentTopology")
    Declarables topology;

    @Test
    void passesTheReferenceAndMessageIdToTheService() {
        PaymentStatusService service = mock(PaymentStatusService.class);
        UUID id = UUID.randomUUID();

        new PaymentEventListener(service).onEvent(Map.of("reference", "MX-10001", "paymentId", "PAY-1"),
                "payment.collected", id.toString(), "trace-1");

        verify(service).apply(eq(id), eq("payment.collected"), eq("MX-10001"));
    }

    @Test
    void aMissingOrBrokenMessageIdIsRejected() {
        PaymentEventListener listener = new PaymentEventListener(mock(PaymentStatusService.class));

        assertThatThrownBy(() -> listener.onEvent(Map.of("reference", "MX-1"), "payment.collected", null, null))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> listener.onEvent(Map.of("reference", "MX-1"), "payment.collected", "nope", null))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void theQueueIsBoundToTheThreePaymentEventsAndHasADeadLetterQueue() {
        assertThat(topology.getDeclarablesByType(Binding.class))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder("payment.collected", "payment.succeeded", "payment.cancelled");
        assertThat(topology.getDeclarablesByType(Binding.class))
                .allMatch(b -> b.getExchange().equals("app.events") && b.getDestination().equals("order.payment-events"));

        var queues = topology.getDeclarablesByType(Queue.class);
        assertThat(queues).extracting(Queue::getName).containsExactlyInAnyOrder("order.payment-events", "order.payment-events.dlq");
        assertThat(queues).allMatch(Queue::isDurable);
        Queue main = queues.stream().filter(q -> q.getName().equals("order.payment-events")).findFirst().orElseThrow();
        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", "order.payment-events.dlq");
    }
}
