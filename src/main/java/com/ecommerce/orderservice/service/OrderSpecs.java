package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.Shipment;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The filters of the admin order list. Every filter is optional (null = not used). */
public final class OrderSpecs {

    private OrderSpecs() {
    }

    /**
     * @param text          part of the order number, customer name, email or tracking number
     * @param createdFrom   inclusive
     * @param createdBefore exclusive
     * @param deliveredBefore only orders whose cash was collected and whose parcel was delivered before this time (overdue cash)
     */
    public static Specification<Order> filter(ShipmentStatus shipmentStatus, PaymentStatus paymentStatus, String text,
                                              Instant createdFrom, Instant createdBefore, Instant deliveredBefore) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            Join<Order, Shipment> shipment = root.join("shipments", JoinType.LEFT);

            if (shipmentStatus != null) {
                all.add(cb.equal(root.get("shipmentStatus"), shipmentStatus));
            }
            if (paymentStatus != null) {
                all.add(cb.equal(root.get("paymentStatus"), paymentStatus));
            }
            if (createdFrom != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }
            if (createdBefore != null) {
                all.add(cb.lessThan(root.get("createdAt"), createdBefore));
            }
            if (deliveredBefore != null) {
                all.add(cb.equal(root.get("paymentStatus"), PaymentStatus.COLLECTED));
                all.add(cb.lessThan(shipment.get("deliveredAt"), deliveredBefore));
            }
            if (text != null && !text.isBlank()) {
                String pattern = "%" + escape(text.trim().toLowerCase()) + "%";
                all.add(cb.or(
                        cb.like(cb.lower(root.get("orderNo")), pattern, '\\'),
                        cb.like(cb.lower(root.get("customerName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("customerEmail")), pattern, '\\'),
                        cb.like(cb.lower(shipment.get("trackingNumber")), pattern, '\\')));
            }

            boolean isCountQuery = Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
            if (!isCountQuery) {
                query.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
    }

    /** % and _ are wildcards in LIKE. A person typing them must search for the plain characters. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
