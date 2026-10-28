package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.TestOrders;
import com.ecommerce.orderservice.TestTokens;
import com.ecommerce.orderservice.client.ProductClient;
import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentState;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.Shipment;
import com.ecommerce.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrdersTest {

    private static final String BASE = "/api/v1/order/admin/orders";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orders;

    @MockitoBean
    ProductClient productClient;
    @MockitoBean
    RabbitTemplate rabbit;

    @AfterEach
    void clean() {
        orders.deleteAll();
    }

    private Order shipped(String orderNo, String tracking, PaymentStatus payment, int deliveredDaysAgo) {
        Order order = TestOrders.order(orderNo, 7);
        order.setShipmentStatus(ShipmentStatus.DELIVERED);
        order.setPaymentStatus(payment);
        Shipment shipment = new Shipment("Courier A", tracking, null, 1L);
        shipment.setStatus(ShipmentState.DELIVERED);
        shipment.setDeliveredAt(Instant.now().minus(deliveredDaysAgo, ChronoUnit.DAYS));
        order.addShipment(shipment);
        return orders.save(order);
    }

    // ---------- who may use the admin URLs ----------

    @Test
    void visitorGets401AndNormalUserGets403() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE).cookie(TestTokens.userCookie(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ---------- list ----------

    @Test
    void adminSeesEveryCustomersOrdersWithBothStatusColumns() throws Exception {
        orders.save(TestOrders.order("MX-10001", 7));
        orders.save(TestOrders.order("MX-10002", 8));

        mockMvc.perform(get(BASE).cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].shipmentStatus").value("NOT_SHIPPED"))
                .andExpect(jsonPath("$.content[0].paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].customerName").value("Nimal Perera"));
    }

    @Test
    void filtersByShipmentAndPaymentStatus() throws Exception {
        orders.save(TestOrders.order("MX-10001", 7));
        shipped("MX-10002", "A1", PaymentStatus.COLLECTED, 1);
        shipped("MX-10003", "A2", PaymentStatus.PAID, 1);

        mockMvc.perform(get(BASE + "?shipmentStatus=DELIVERED&paymentStatus=COLLECTED").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].orderNo").value("MX-10002"));
        mockMvc.perform(get(BASE + "?shipmentStatus=NOT_SHIPPED").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].orderNo", contains("MX-10001")));
    }

    @Test
    void searchesByOrderNumberNameEmailAndTrackingNumber() throws Exception {
        orders.save(TestOrders.order("MX-10001", 7));
        shipped("MX-10002", "WB55310", PaymentStatus.COLLECTED, 1);

        mockMvc.perform(get(BASE + "?q=10001").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].orderNo", contains("MX-10001")));
        mockMvc.perform(get(BASE + "?q=wb55310").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].orderNo", contains("MX-10002")));
        mockMvc.perform(get(BASE + "?q=NIMAL@").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(BASE + "?q=%25").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void overdueShowsOnlyCollectedCashOlderThanTheLimit() throws Exception {
        shipped("MX-10001", "A1", PaymentStatus.COLLECTED, 20);   // overdue
        shipped("MX-10002", "A2", PaymentStatus.COLLECTED, 3);    // not yet
        shipped("MX-10003", "A3", PaymentStatus.PAID, 30);        // already paid

        mockMvc.perform(get(BASE + "?overdue=true").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].orderNo", contains("MX-10001")));
    }

    @Test
    void filtersByDate() throws Exception {
        Order old = TestOrders.order("MX-10001", 7);
        old.setCreatedAt(Instant.parse("2026-01-15T10:00:00Z"));
        orders.save(old);
        orders.save(TestOrders.order("MX-10002", 7));

        mockMvc.perform(get(BASE + "?from=2026-01-15&to=2026-01-15").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].orderNo", contains("MX-10001")));
        mockMvc.perform(get(BASE + "?from=2026-01-16&to=2026-01-31").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aBadStatusInTheFilterIs400() throws Exception {
        mockMvc.perform(get(BASE + "?shipmentStatus=BOGUS").cookie(TestTokens.adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------- dashboard ----------

    @Test
    void dashboardCounts() throws Exception {
        orders.save(TestOrders.order("MX-10001", 7));                  // to ship
        Order onTheRoad = TestOrders.order("MX-10002", 7);
        onTheRoad.setShipmentStatus(ShipmentStatus.IN_TRANSIT);
        onTheRoad.addShipment(new Shipment("Courier A", "T1", null, 1L));
        orders.save(onTheRoad);
        shipped("MX-10003", "A3", PaymentStatus.COLLECTED, 20);        // cash with courier, overdue
        shipped("MX-10004", "A4", PaymentStatus.COLLECTED, 2);         // cash with courier
        shipped("MX-10005", "A5", PaymentStatus.PAID, 40);             // paid

        mockMvc.perform(get(BASE + "/summary").cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toShip").value(1))
                .andExpect(jsonPath("$.withCourier").value(1))
                .andExpect(jsonPath("$.cashWithCouriersCount").value(2))
                .andExpect(jsonPath("$.cashWithCouriersAmount").value(19000.00))
                .andExpect(jsonPath("$.overdueCount").value(1))
                .andExpect(jsonPath("$.overdueAmount").value(9500.00))
                .andExpect(jsonPath("$.overdueDays").value(14))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    // ---------- one order, cancel ----------

    @Test
    void adminReadsOneOrderWithAddressAndPhone() throws Exception {
        orders.save(TestOrders.order("MX-10001", 7));

        mockMvc.perform(get(BASE + "/MX-10001").cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.phone").value("0771234567"))
                .andExpect(jsonPath("$.customer.addressLine").value("12 Galle Road"))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mockMvc.perform(get(BASE + "/MX-99999").cookie(TestTokens.adminCookie())).andExpect(status().isNotFound());
    }

    @Test
    void adminCancelsAnyOrderBeforeShipping() throws Exception {
        orders.save(TestOrders.order("MX-10001", 7));

        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("ADMIN"));

        verify(productClient).release("MX-10001");
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq("app.events"), eq("order.cancelled"), body.capture(), any(MessagePostProcessor.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) body.getValue();
        assertThat(event).containsEntry("cancelledBy", "ADMIN").containsEntry("userId", "7");
    }

    @Test
    void adminCannotCancelAShippedOrder() throws Exception {
        shipped("MX-10001", "A1", PaymentStatus.COLLECTED, 1);

        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.adminCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }
}
