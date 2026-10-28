package com.ecommerce.orderservice.entity;

/** The small fixed lists of the order domain, in one place. */
public final class Enums {

    private Enums() {
    }

    /** Cash on delivery is the only method in the MVP (M2). */
    public enum PaymentMethod {
        COD
    }

    /** Where the parcel is (the state diagram in docs/mvp/09-state-diagrams.md). */
    public enum ShipmentStatus {
        NOT_SHIPPED,
        IN_TRANSIT,
        DELIVERED,
        RETURNED,
        CANCELLED
    }

    /** Where the money is. A copy: only payment events change it (M3). */
    public enum PaymentStatus {
        PENDING,
        COLLECTED,
        PAID,
        CANCELLED
    }

    public enum CancelledBy {
        CUSTOMER,
        ADMIN
    }

    /** The status of one shipment. */
    public enum ShipmentState {
        IN_TRANSIT,
        DELIVERED,
        RETURNED
    }

    public enum ReturnReason {
        REFUSED,
        NOT_REACHABLE,
        WRONG_ADDRESS,
        OTHER
    }
}
