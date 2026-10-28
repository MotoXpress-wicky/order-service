package com.ecommerce.orderservice.config;

import com.ecommerce.orderservice.event.OrderEvents;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * RabbitMQ setup. Rules from docs/mvp/07-events.md:
 * - the exchange is declared by publisher and listener (same name, type topic, durable);
 * - the queue and its bindings are declared only by the listener. order-service listens to payment events.
 *
 * A message that fails every retry goes to "<queue>.dlq" (dead-letter queue) for a person to check.
 */
@Configuration
public class RabbitConfig {

    /** Event bodies are JSON. This converter also makes @RabbitListener methods accept a Map. */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    /** After the last retry: do not put the message back on the queue (that would loop forever). */
    @Bean
    public MessageRecoverer messageRecoverer() {
        return new RejectAndDontRequeueRecoverer();
    }

    @Bean
    public TopicExchange appEvents(EventsProperties events) {
        return new TopicExchange(events.exchange(), true, false);
    }

    /** Queue order.payment-events: the payment events that change the payment status of an order. */
    @Bean
    public Declarables paymentTopology(EventsProperties events, TopicExchange appEvents) {
        String deadLetterQueueName = events.paymentQueue() + ".dlq";

        Queue queue = QueueBuilder.durable(events.paymentQueue())
                // "" = the default exchange, which delivers by queue name.
                .deadLetterExchange("")
                .deadLetterRoutingKey(deadLetterQueueName)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(deadLetterQueueName).build();

        List<Declarable> all = new ArrayList<>(List.of(queue, deadLetterQueue));
        for (String event : List.of(OrderEvents.PAYMENT_COLLECTED, OrderEvents.PAYMENT_SUCCEEDED, OrderEvents.PAYMENT_CANCELLED)) {
            Binding binding = BindingBuilder.bind(queue).to(appEvents).with(event);
            all.add(binding);
        }
        return new Declarables(all);
    }
}
