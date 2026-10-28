package com.ecommerce.orderservice.dto;

import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ReturnReason;
import com.ecommerce.orderservice.entity.Enums.ShipmentState;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Request and response shapes of shipments. */
public final class ShipmentDtos {

    private ShipmentDtos() {
    }

    /** POST /admin/shipments/delivered: shipments the courier delivered. */
    public record DeliveredRequest(@NotEmpty @Size(max = 100) Set<@NotNull Long> shipmentIds) {
    }

    /** POST /admin/shipments/returned: parcels the courier brought back, with the reason. */
    public record ReturnedRequest(@NotEmpty @Size(max = 100) Set<@NotNull Long> shipmentIds,
                                  @NotNull ReturnReason reason) {
    }

    /**
     * The answer to "delivered" and "returned". Every shipment is handled on its own:
     * the ones that worked are in done, the ones that did not are in failed with the reason.
     */
    public record BulkResult(List<Done> done, List<Failure> failed) {

        public record Done(Long shipmentId, String orderNo) {
        }

        public record Failure(Long shipmentId, String orderNo, String code, String message) {
        }
    }

    /** One line of the shipment list. */
    public record ShipmentSummary(
            Long id, String orderNo, String customerName, String courier, String trackingNumber,
            ShipmentState status, Instant shippedAt, Instant deliveredAt, Instant returnedAt, String returnReason,
            boolean hasPickupReceipt, BigDecimal total, String currency, PaymentStatus paymentStatus) {
    }
}
