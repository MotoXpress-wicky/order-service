package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.TestOrders;
import com.ecommerce.orderservice.TestTokens;
import com.ecommerce.orderservice.client.ProductClient;
import com.ecommerce.orderservice.entity.Enums.CancelledBy;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.Shipment;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.exception.ErrorCode;
import com.ecommerce.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** My orders, one order, cancel, "I received it". Not @Transactional: events are sent after a real commit. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerOrdersTest {

    private static final String BASE = "/api/v1/order/orders";

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

    private Order save(String orderNo, long userId) {
        return orders.save(TestOrders.order(orderNo, userId));
    }

    private Order saveInTransit(String orderNo, long userId) {
        Order order = TestOrders.order(orderNo, userId);
        order.setShipmentStatus(ShipmentStatus.IN_TRANSIT);
        order.addShipment(new Shipment("Courier A", "WB" + orderNo.hashCode(), null, 1L));
        return orders.save(order);
    }

    // ---------- list and read ----------

    @Test
    void listShowsOnlyMyOrdersNewestFirst() throws Exception {
        Order older = TestOrders.order("MX-10001", 7);
        older.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        orders.save(older);
        save("MX-10002", 7);
        save("MX-10003", 8);

        mockMvc.perform(get(BASE).cookie(TestTokens.userCookie(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].orderNo").value("MX-10002"))
                .andExpect(jsonPath("$.content[1].orderNo").value("MX-10001"))
                .andExpect(jsonPath("$.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.content[0].total").value(9500.00));
    }

    @Test
    void listIsPaged() throws Exception {
        for (int i = 1; i <= 3; i++) {
            save("MX-1000" + i, 7);
        }

        mockMvc.perform(get(BASE + "?size=2&page=1").cookie(TestTokens.userCookie(7)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void oneOrderShowsItemsTotalsAndTheShipment() throws Exception {
        saveInTransit("MX-10001", 7);

        mockMvc.perform(get(BASE + "/MX-10001").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("MX-10001"))
                .andExpect(jsonPath("$.items[0].partNumber").value("123HG"))
                .andExpect(jsonPath("$.total").value(9500.00))
                .andExpect(jsonPath("$.shipment.courier").value("Courier A"))
                .andExpect(jsonPath("$.shipment.status").value("IN_TRANSIT"));
    }

    @Test
    void someoneElsesOrderLooksLikeItDoesNotExist() throws Exception {
        save("MX-10001", 7);

        mockMvc.perform(get(BASE + "/MX-10001").cookie(TestTokens.userCookie(8)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get(BASE + "/MX-99999").cookie(TestTokens.userCookie(7))).andExpect(status().isNotFound());
    }

    @Test
    void aVisitorMustLogIn() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/MX-10001")).andExpect(status().isUnauthorized());
    }

    // ---------- cancel ----------

    @Test
    void customerCancelsBeforeShippingStockGoesBackAndTheEventIsPublished() throws Exception {
        save("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("CUSTOMER"));

        verify(productClient).release("MX-10001");
        Order saved = orders.findByOrderNo("MX-10001").orElseThrow();
        assertThat(saved.getShipmentStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(saved.getCancelledBy()).isEqualTo(CancelledBy.CUSTOMER);
        assertThat(saved.getCancelledAt()).isNotNull();

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq("app.events"), eq("order.cancelled"), body.capture(), any(MessagePostProcessor.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) body.getValue();
        assertThat(event).containsEntry("orderNo", "MX-10001").containsEntry("reference", "MX-10001")
                .containsEntry("userId", "7").containsEntry("cancelledBy", "CUSTOMER");
        assertThat(event.get("cancelledAt")).isNotNull();
    }

    @Test
    void youCannotCancelSomeoneElsesOrder() throws Exception {
        save("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.userCookie(8)))
                .andExpect(status().isNotFound());

        verify(productClient, never()).release(anyString());
        assertThat(orders.findByOrderNo("MX-10001").orElseThrow().getShipmentStatus()).isEqualTo(ShipmentStatus.NOT_SHIPPED);
    }

    @Test
    void aShippedOrderCannotBeCancelled() throws Exception {
        saveInTransit("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));

        verify(productClient, never()).release(anyString());
    }

    @Test
    void cancellingTwiceIsRefusedTheSecondTime() throws Exception {
        save("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.userCookie(7))).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void ifProductServiceIsDownTheCancelFailsAndNothingChanges() throws Exception {
        save("MX-10001", 7);
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PRODUCT_SERVICE_UNAVAILABLE, "down", List.of()))
                .when(productClient).release("MX-10001");

        mockMvc.perform(post(BASE + "/MX-10001/cancel").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));

        Order unchanged = orders.findByOrderNo("MX-10001").orElseThrow();
        assertThat(unchanged.getShipmentStatus()).isEqualTo(ShipmentStatus.NOT_SHIPPED);
        assertThat(unchanged.getCancelledAt()).isNull();
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }

    // ---------- "I received it" ----------

    @Test
    void receivedSavesTheTimeAndChangesNoStatus() throws Exception {
        saveInTransit("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/received").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.shipmentStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    void pressingReceivedAgainKeepsTheFirstTime() throws Exception {
        saveInTransit("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/received").cookie(TestTokens.userCookie(7))).andExpect(status().isOk());
        Instant first = orders.findByOrderNo("MX-10001").orElseThrow().getCustomerConfirmedAt();
        mockMvc.perform(post(BASE + "/MX-10001/received").cookie(TestTokens.userCookie(7))).andExpect(status().isOk());

        assertThat(orders.findByOrderNo("MX-10001").orElseThrow().getCustomerConfirmedAt()).isEqualTo(first);
    }

    @Test
    void receivedIsRefusedForAnOrderThatWasNotSent() throws Exception {
        save("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/received").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void receivedOnSomeoneElsesOrderIs404() throws Exception {
        saveInTransit("MX-10001", 7);

        mockMvc.perform(post(BASE + "/MX-10001/received").cookie(TestTokens.userCookie(8)))
                .andExpect(status().isNotFound());
    }
}
