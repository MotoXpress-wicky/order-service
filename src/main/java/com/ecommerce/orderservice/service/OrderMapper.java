package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.dto.OrderDtos.CustomerResponse;
import com.ecommerce.orderservice.dto.OrderDtos.ItemResponse;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.dto.OrderDtos.OrderSummary;
import com.ecommerce.orderservice.dto.OrderDtos.ShipmentResponse;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.Shipment;
import org.springframework.stereotype.Component;

/** Turns entities into response objects. Call it inside a transaction: it reads lazy fields. */
@Component
public class OrderMapper {

    public OrderDetail toDetail(Order o) {
        return new OrderDetail(o.getOrderNo(), o.getCreatedAt(), o.getShipmentStatus(), o.getPaymentStatus(),
                o.getPaymentMethod(),
                o.getItems().stream()
                        .map(i -> new ItemResponse(i.getProductId(), i.getPartNumber(), i.getProductName(),
                                i.getUnitPrice(), i.getQuantity(), i.getLineTotal()))
                        .toList(),
                o.getItemsTotal(), o.getDeliveryFee(), o.getTotal(), o.getCurrency(),
                new CustomerResponse(o.getUserId(), o.getCustomerName(), o.getCustomerEmail(), o.getPhone(),
                        o.getAddressLine(), o.getCity(), o.getDistrict()),
                toShipment(o.getShipment()), o.getCustomerConfirmedAt(), o.getCancelledBy(), o.getCancelledAt());
    }

    public OrderSummary toSummary(Order o) {
        Shipment shipment = o.getShipment();
        return new OrderSummary(o.getOrderNo(), o.getCreatedAt(), o.getShipmentStatus(), o.getPaymentStatus(),
                o.getTotal(), o.getCurrency(), o.getItems().stream().mapToInt(i -> i.getQuantity()).sum(),
                o.getCustomerName(), o.getCustomerEmail(), o.getCity(),
                shipment == null ? null : shipment.getCourier(),
                shipment == null ? null : shipment.getTrackingNumber(),
                o.getCustomerConfirmedAt());
    }

    public ShipmentResponse toShipment(Shipment s) {
        if (s == null) {
            return null;
        }
        return new ShipmentResponse(s.getId(), s.getCourier(), s.getTrackingNumber(), s.getStatus(), s.getShippedAt(),
                s.getDeliveredAt(), s.getReturnedAt(), s.getReturnReason() == null ? null : s.getReturnReason().name(),
                s.getPickupReceiptKey() != null);
    }
}
