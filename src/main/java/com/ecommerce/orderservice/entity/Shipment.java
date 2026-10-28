package com.ecommerce.orderservice.entity;

import com.ecommerce.orderservice.entity.Enums.ReturnReason;
import com.ecommerce.orderservice.entity.Enums.ShipmentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The parcel handed to a courier company (M5). */
@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false, length = 50)
    private String courier;

    @Column(name = "tracking_number", nullable = false, length = 100)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShipmentState status = ShipmentState.IN_TRANSIT;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_reason", length = 20)
    private ReturnReason returnReason;

    /** Key of the optional pickup receipt photo in the private bucket shipment-documents. */
    @Column(name = "pickup_receipt_key")
    private String pickupReceiptKey;

    /** The admin user id. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "shipped_at", nullable = false)
    private Instant shippedAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    public Shipment(String courier, String trackingNumber, String pickupReceiptKey, Long createdBy) {
        this.courier = courier;
        this.trackingNumber = trackingNumber;
        this.pickupReceiptKey = pickupReceiptKey;
        this.createdBy = createdBy;
    }
}
