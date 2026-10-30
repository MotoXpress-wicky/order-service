package com.ecommerce.orderservice.event;

import com.ecommerce.orderservice.entity.OutboxEvent;
import com.ecommerce.orderservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The outbox (M46): an event is saved with the change, sent after the commit, and sent later if RabbitMQ is down. */
@SpringBootTest
class OutboxTest {

    @Autowired
    EventPublisher publisher;
    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    RabbitTemplate rabbit;

    @BeforeEach
    void emptyTheOutbox() {
        outbox.deleteAll();
    }

    private void send(String name, Map<String, Object> body) {
        tx.executeWithoutResult(status -> publisher.publish(name, body));
    }

    private void brokerDown() {
        reset(rabbit);
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }

    private void brokerUp() {
        reset(rabbit);
        doNothing().when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }

    private OutboxEvent only() {
        List<OutboxEvent> all = outbox.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    @DisplayName("the event is saved with the change, and sent only AFTER the commit")
    void savedWithTheChangeSentAfterTheCommit() {
        tx.executeWithoutResult(status -> {
            publisher.publish("order.placed", Map.of("orderNo", "MX-1"));

            assertThat(outbox.count()).isEqualTo(1);                      // saved inside the transaction
            verify(rabbit, never()).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
        });

        verify(rabbit).convertAndSend(eq("app.events"), eq("order.placed"), eq(Map.of("orderNo", "MX-1")), any(MessagePostProcessor.class));
        assertThat(only().getSentAt()).isNotNull();
        assertThat(only().getAttempts()).isZero();
    }

    @Test
    @DisplayName("a rolled back change leaves no event and sends nothing")
    void rolledBackSendsNothing() {
        tx.executeWithoutResult(status -> {
            publisher.publish("order.placed", Map.of("orderNo", "MX-1"));
            status.setRollbackOnly();
        });

        assertThat(outbox.count()).isZero();
        verify(rabbit, never()).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("without a transaction the event is saved and sent at once")
    void withoutATransaction() {
        publisher.publish("order.placed", Map.of("orderNo", "MX-1"));

        verify(rabbit).convertAndSend(eq("app.events"), eq("order.placed"), any(Object.class), any(MessagePostProcessor.class));
        assertThat(only().getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("the messageId header is the id of the outbox row, and the trace id travels too")
    void messageIdIsTheRowId() {
        send("order.placed", Map.of("orderNo", "MX-1"));

        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), post.capture());
        Message message = post.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));

        assertThat(message.getMessageProperties().<String>getHeader("messageId")).isEqualTo(only().getId().toString());
    }

    @Test
    @DisplayName("RabbitMQ down: no exception, the event waits, and it is sent later with the SAME id")
    void brokerDownThenUp() {
        brokerDown();

        assertThatCode(() -> send("order.placed", Map.of("orderNo", "MX-1"))).doesNotThrowAnyException();
        OutboxEvent waiting = only();
        assertThat(waiting.getSentAt()).isNull();
        assertThat(waiting.getAttempts()).isEqualTo(1);
        assertThat(waiting.getLastError()).isNotBlank();
        assertThat(outbox.countBySentAtIsNull()).isEqualTo(1);

        brokerUp();
        assertThat(relay.flush()).isEqualTo(1);

        OutboxEvent sent = only();
        assertThat(sent.getId()).isEqualTo(waiting.getId());
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.getLastError()).isNull();
        verify(rabbit, times(1)).convertAndSend(eq("app.events"), eq("order.placed"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("waiting events are sent in the order they were created")
    void sentInOrder() throws Exception {
        brokerDown();
        send("order.placed", Map.of("n", 1));
        Thread.sleep(5);
        send("order.shipped", Map.of("n", 2));
        Thread.sleep(5);
        send("order.delivered", Map.of("n", 3));

        brokerUp();
        assertThat(relay.flush()).isEqualTo(3);

        InOrder order = inOrder(rabbit);
        order.verify(rabbit).convertAndSend(eq("app.events"), eq("order.placed"), any(Object.class), any(MessagePostProcessor.class));
        order.verify(rabbit).convertAndSend(eq("app.events"), eq("order.shipped"), any(Object.class), any(MessagePostProcessor.class));
        order.verify(rabbit).convertAndSend(eq("app.events"), eq("order.delivered"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("a new event never overtakes an older one that is still waiting")
    void noOvertaking() throws Exception {
        brokerDown();
        send("order.placed", Map.of("n", 1));
        Thread.sleep(5);

        brokerUp();                                   // RabbitMQ is back, but "placed" has not been retried yet
        send("order.shipped", Map.of("n", 2));        // this commit's flush sends BOTH, oldest first

        InOrder order = inOrder(rabbit);
        order.verify(rabbit).convertAndSend(eq("app.events"), eq("order.placed"), any(Object.class), any(MessagePostProcessor.class));
        order.verify(rabbit).convertAndSend(eq("app.events"), eq("order.shipped"), any(Object.class), any(MessagePostProcessor.class));
        assertThat(outbox.countBySentAtIsNull()).isZero();
    }

    @Test
    @DisplayName("when RabbitMQ fails, the round stops at once: later events are not tried and keep their place")
    void stopsAtTheFirstFailure() throws Exception {
        brokerDown();
        send("order.placed", Map.of("n", 1));
        Thread.sleep(5);
        send("order.shipped", Map.of("n", 2));
        reset(rabbit);
        doThrow(new AmqpConnectException(new RuntimeException("down")))
                .when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));

        assertThat(relay.flush()).isZero();

        verify(rabbit, times(1)).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
        assertThat(outbox.countBySentAtIsNull()).isEqualTo(2);
    }

    @Test
    @DisplayName("the body is the same after it went through the database (strings, numbers, lists, maps)")
    void bodySurvivesTheDatabase() {
        Map<String, Object> body = Map.of(
                "orderNo", "MX-1", "total", "9500.00", "count", 2,
                "lines", List.of(Map.of("partNumber", "BP-1", "quantity", 2, "unitPrice", "4500.00")));

        send("order.placed", body);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(any(String.class), any(String.class), sent.capture(), any(MessagePostProcessor.class));
        assertThat(sent.getValue()).isEqualTo(body);
    }

    @Test
    @DisplayName("a stored event with broken JSON is skipped and does not block the good ones behind it")
    void brokenRowDoesNotBlock() {
        outbox.save(new OutboxEvent(UUID.randomUUID(), "order.placed", "this is not json", null));
        send("order.shipped", Map.of("n", 2));

        verify(rabbit).convertAndSend(eq("app.events"), eq("order.shipped"), any(Object.class), any(MessagePostProcessor.class));
        verify(rabbit, never()).convertAndSend(eq("app.events"), eq("order.placed"), any(Object.class), any(MessagePostProcessor.class));
        List<OutboxEvent> left = outbox.findAll().stream().filter(e -> e.getSentAt() == null).toList();
        assertThat(left).singleElement().satisfies(e -> {
            assertThat(e.getAttempts()).isEqualTo(1);
            assertThat(e.getLastError()).contains("invalid JSON");
        });
    }

    @Test
    @DisplayName("cleanup deletes only sent events that are older than the keep time")
    void cleanup() {
        brokerDown();
        send("order.placed", Map.of("n", 1));                       // stays waiting
        brokerUp();
        send("order.shipped", Map.of("n", 2));                      // sent now: this also sends "placed"
        brokerDown();
        send("order.delivered", Map.of("n", 3));                    // waiting
        UUID old = outbox.findAll().stream().filter(e -> e.getEventName().equals("order.placed")).findFirst().orElseThrow().getId();
        // pretend it was sent 30 days ago
        jdbc.update("update outbox_events set sent_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(30L * 24 * 3600)), old);

        relay.deleteOld();

        assertThat(outbox.findAll()).extracting(OutboxEvent::getEventName)
                .containsExactlyInAnyOrder("order.shipped", "order.delivered");
    }
}
