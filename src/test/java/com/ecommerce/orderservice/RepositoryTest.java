package com.ecommerce.orderservice;

import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.OrderItem;
import com.ecommerce.orderservice.entity.Shipment;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.repository.ShipmentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway file V1__create_tables.sql (on H2 in PostgreSQL mode) and lets Hibernate
 * check that the entities match the tables. A typo in the SQL or in an entity fails here.
 */
@DataJpaTest
class RepositoryTest {

    @Autowired
    OrderRepository orders;
    @Autowired
    ShipmentRepository shipments;
    @Autowired
    EntityManager em;

    private Order order(String orderNo, long userId) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setCustomerName("Nimal Perera");
        order.setCustomerEmail("nimal@example.com");
        order.setPhone("0771234567");
        order.setAddressLine("12 Galle Road");
        order.setCity("Colombo");
        order.setDistrict("Colombo");
        order.setItemsTotal(new BigDecimal("9000.00"));
        order.setDeliveryFee(new BigDecimal("500.00"));
        order.setTotal(new BigDecimal("9500.00"));
        order.setCurrency("LKR");
        order.addItem(new OrderItem(1L, "123HG", "Sprocket", new BigDecimal("4500.00"), 2, new BigDecimal("9000.00")));
        return order;
    }

    @Test
    void savesAnOrderWithItsItems() {
        Long id = orders.saveAndFlush(order("MX-10001", 7)).getId();
        em.clear();

        Order loaded = orders.findById(id).orElseThrow();
        assertThat(loaded.getItems()).hasSize(1);
        assertThat(loaded.getItems().get(0).getPartNumber()).isEqualTo("123HG");
        assertThat(loaded.getShipmentStatus()).isEqualTo(ShipmentStatus.NOT_SHIPPED);
        assertThat(loaded.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(loaded.getShipment()).isNull();
    }

    @Test
    void orderNumberIsUnique() {
        orders.saveAndFlush(order("MX-10001", 7));

        assertThatThrownBy(() -> orders.saveAndFlush(order("MX-10001", 8)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listsOnlyMyOrdersNewestFirst() {
        Order older = order("MX-10001", 7);
        older.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        orders.save(older);
        orders.save(order("MX-10002", 7));
        orders.save(order("MX-10003", 8));
        orders.flush();

        var page = orders.findByUserIdOrderByCreatedAtDescIdDesc(7L, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Order::getOrderNo).containsExactly("MX-10002", "MX-10001");
    }

    @Test
    void aCourierCannotUseATrackingNumberTwiceButAnotherCourierCan() {
        Order first = order("MX-10001", 7);
        first.addShipment(new Shipment("Courier A", "WB55310", null, 1L));
        orders.saveAndFlush(first);

        assertThat(shipments.existsByCourierIgnoreCaseAndTrackingNumber("courier a", "WB55310")).isTrue();
        assertThat(shipments.existsByCourierIgnoreCaseAndTrackingNumber("Courier B", "WB55310")).isFalse();

        Order second = order("MX-10002", 7);
        second.addShipment(new Shipment("Courier A", "WB55310", null, 1L));
        assertThatThrownBy(() -> orders.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void countsOverdueCash() {
        Order overdue = order("MX-10001", 7);
        overdue.setPaymentStatus(PaymentStatus.COLLECTED);
        Shipment old = new Shipment("Courier A", "A1", null, 1L);
        old.setDeliveredAt(Instant.now().minus(20, ChronoUnit.DAYS));
        overdue.addShipment(old);
        orders.save(overdue);

        Order recent = order("MX-10002", 7);
        recent.setPaymentStatus(PaymentStatus.COLLECTED);
        Shipment fresh = new Shipment("Courier A", "A2", null, 1L);
        fresh.setDeliveredAt(Instant.now().minus(2, ChronoUnit.DAYS));
        recent.addShipment(fresh);
        orders.save(recent);
        orders.flush();

        Instant limit = Instant.now().minus(14, ChronoUnit.DAYS);
        assertThat(orders.countOverdue(limit)).isEqualTo(1);
        assertThat(orders.sumOverdue(limit)).isEqualByComparingTo("9500.00");
        assertThat(orders.countByPaymentStatus(PaymentStatus.COLLECTED)).isEqualTo(2);
        assertThat(orders.sumTotalByPaymentStatus(PaymentStatus.COLLECTED)).isEqualByComparingTo("19000.00");
    }

    @Test
    void theOrderNumberSequenceStartsAt10001AndGoesUp() {
        Number first = (Number) em.createNativeQuery("select nextval('order_no_seq')").getSingleResult();
        Number second = (Number) em.createNativeQuery("select nextval('order_no_seq')").getSingleResult();

        assertThat(first.longValue()).isEqualTo(10001);
        assertThat(second.longValue()).isEqualTo(10002);
    }
}
