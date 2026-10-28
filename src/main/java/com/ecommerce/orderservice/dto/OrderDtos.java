package com.ecommerce.orderservice.dto;

import com.ecommerce.orderservice.entity.Enums.CancelledBy;
import com.ecommerce.orderservice.entity.Enums.PaymentMethod;
import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentState;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request and response shapes of orders. */
public final class OrderDtos {

    private OrderDtos() {
    }

    // ---------- requests ----------

    /** POST /orders. Prices are NOT sent: they come from product-service. */
    public record CheckoutRequest(
            @NotEmpty @Size(max = 100) List<@Valid Line> lines,
            @NotNull @Valid Address address) {

        public record Line(@NotNull Long productId, @NotNull @Min(1) @Max(1000) Integer quantity) {
        }

        public record Address(
                @NotBlank @Size(max = 30) @Pattern(regexp = "^[0-9+()\\- ]{7,30}$", message = "must be a phone number") String phone,
                @NotBlank @Size(max = 255) String addressLine,
                @NotBlank @Size(max = 100) String city,
                @NotBlank @Size(max = 100) String district) {
        }
    }

    // ---------- responses ----------

    public record ItemResponse(Long productId, String partNumber, String name, BigDecimal unitPrice,
                               int quantity, BigDecimal lineTotal) {
    }

    public record CustomerResponse(Long userId, String name, String email, String phone, String addressLine,
                                   String city, String district) {
    }

    public record ShipmentResponse(Long id, String courier, String trackingNumber, ShipmentState status,
                                   Instant shippedAt, Instant deliveredAt, Instant returnedAt, String returnReason,
                                   boolean hasPickupReceipt) {
    }

    /** One whole order. The customer sees their own, the admin sees every order. */
    public record OrderDetail(
            String orderNo, Instant createdAt, ShipmentStatus shipmentStatus, PaymentStatus paymentStatus,
            PaymentMethod paymentMethod, List<ItemResponse> items, BigDecimal itemsTotal, BigDecimal deliveryFee,
            BigDecimal total, String currency, CustomerResponse customer, ShipmentResponse shipment,
            Instant customerConfirmedAt, CancelledBy cancelledBy, Instant cancelledAt) {
    }

    /** One line of an order list. */
    public record OrderSummary(
            String orderNo, Instant createdAt, ShipmentStatus shipmentStatus, PaymentStatus paymentStatus,
            BigDecimal total, String currency, int itemCount, String customerName, String customerEmail,
            String city, String courier, String trackingNumber, Instant customerConfirmedAt) {
    }

    /** One page of a list. */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}
