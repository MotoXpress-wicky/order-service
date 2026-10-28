package com.ecommerce.orderservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything under "shipments:" in application.yaml. */
@Validated
@ConfigurationProperties(prefix = "shipments")
public record ShipmentProperties(@NotNull @Valid PickupReceipt pickupReceipt) {

    public record PickupReceipt(@Min(1) long maxSizeBytes) {
    }
}
