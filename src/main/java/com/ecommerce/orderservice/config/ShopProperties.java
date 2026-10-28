package com.ecommerce.orderservice.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

/** The business settings under "shop:" in application.yaml. Wrong values stop the service at startup. */
@Validated
@ConfigurationProperties(prefix = "shop")
public record ShopProperties(
        @NotBlank String currency,
        @NotBlank String orderNumberPrefix,
        @NotNull @DecimalMin("0.00") BigDecimal deliveryFee,
        @NotEmpty List<@NotBlank String> couriers,
        @Min(1) int overdueCashDays,
        @Min(1) int maxLinesPerOrder,
        @Min(1) int maxQuantityPerLine,
        @Min(1) int defaultPageSize,
        @Min(1) int maxPageSize
) {
}
