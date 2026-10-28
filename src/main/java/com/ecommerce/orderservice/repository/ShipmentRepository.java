package com.ecommerce.orderservice.repository;

import com.ecommerce.orderservice.entity.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface ShipmentRepository extends JpaRepository<Shipment, Long>, JpaSpecificationExecutor<Shipment> {

    boolean existsByCourierIgnoreCaseAndTrackingNumber(String courier, String trackingNumber);

    List<Shipment> findByIdIn(Collection<Long> ids);
}
