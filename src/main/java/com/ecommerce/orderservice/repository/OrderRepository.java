package com.ecommerce.orderservice.repository;

import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNo(String orderNo);

    /** Locks the order row until the end of the transaction, so a cancel and a new shipment cannot cross each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderNo = :orderNo")
    Optional<Order> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    Page<Order> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    long countByShipmentStatus(ShipmentStatus status);

    long countByPaymentStatus(PaymentStatus status);

    @Query("select coalesce(sum(o.total), 0) from Order o where o.paymentStatus = :status")
    BigDecimal sumTotalByPaymentStatus(@Param("status") PaymentStatus status);

    /** Delivered parcels whose cash the courier has not paid yet, delivered before the given time. */
    @Query("select count(o) from Order o join o.shipments s "
            + "where o.paymentStatus = com.ecommerce.orderservice.entity.Enums$PaymentStatus.COLLECTED and s.deliveredAt < :before")
    long countOverdue(@Param("before") Instant before);

    @Query("select coalesce(sum(o.total), 0) from Order o join o.shipments s "
            + "where o.paymentStatus = com.ecommerce.orderservice.entity.Enums$PaymentStatus.COLLECTED and s.deliveredAt < :before")
    BigDecimal sumOverdue(@Param("before") Instant before);
}
