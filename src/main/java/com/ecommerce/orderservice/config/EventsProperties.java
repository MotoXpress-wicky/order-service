package com.ecommerce.orderservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** RabbitMQ names under "events:" in application.yaml. */
@Validated
@ConfigurationProperties(prefix = "events")
public record EventsProperties(@NotBlank String exchange, @NotBlank String paymentQueue) {
}
