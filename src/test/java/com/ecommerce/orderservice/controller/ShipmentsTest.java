package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.TestOrders;
import com.ecommerce.orderservice.TestTokens;
import com.ecommerce.orderservice.client.ProductClient;
import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ReturnReason;
import com.ecommerce.orderservice.entity.Enums.ShipmentState;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.Shipment;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.exception.ErrorCode;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.service.storage.ObjectStorage;
import com.ecommerce.orderservice.service.storage.ObjectStorage.StoredFile;
import com.ecommerce.orderservice.service.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Not @Transactional: the order and its event must really commit. product-service, RabbitMQ and the file storage are faked. */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentsTest {

    private static final String BASE = "/api/v1/order/admin/shipments";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PDF = "%PDF-1.7 fake".getBytes();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orders;
    @Autowired
    TransactionTemplate tx;

    @MockitoBean
    ProductClient productClient;
    @MockitoBean
    RabbitTemplate rabbit;
    @MockitoBean
    ObjectStorage storage;

    @AfterEach
    void clean() {
        orders.deleteAll();
    }

    private Order notShipped(String orderNo) {
        return orders.save(TestOrders.order(orderNo, 7));
    }

    private Order inTransit(String orderNo, String courier, String tracking) {
        Order order = TestOrders.order(orderNo, 7);
        order.setShipmentStatus(ShipmentStatus.IN_TRANSIT);
        order.addShipment(new Shipment(courier, tracking, null, 1L));
        return orders.save(order);
    }

    private ResultActions create(String orderNo, String courier, String tracking, MockMultipartFile... files) throws Exception {
        var request = multipart(BASE).param("orderNo", orderNo).param("courier", courier)
                .param("trackingNumber", tracking).cookie(TestTokens.adminCookie());
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return mockMvc.perform(request);
    }

    private Long shipmentId(String orderNo) {
        return tx.execute(s -> orders.findByOrderNo(orderNo).orElseThrow().getShipment().getId());
    }

    private Map<String, Object> publishedBody(String eventName, int times) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(rabbit, times(times)).convertAndSend(eq("app.events"), eq(eventName), body.capture(), any(MessagePostProcessor.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) body.getValue();
        return event;
    }

    // ---------- create ----------

    @Test
    void adminHandsAParcelToTheCourier() throws Exception {
        notShipped("MX-10001");

        create("MX-10001", "Courier A", "WB55310")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipmentStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.shipment.courier").value("Courier A"))
                .andExpect(jsonPath("$.shipment.trackingNumber").value("WB55310"))
                .andExpect(jsonPath("$.shipment.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.shipment.hasPickupReceipt").value(false));

        Map<String, Object> event = publishedBody("order.shipped", 1);
        assertThat(event).containsEntry("orderNo", "MX-10001").containsEntry("reference", "MX-10001")
                .containsEntry("courier", "Courier A").containsEntry("trackingNumber", "WB55310")
                .containsEntry("userId", "7").containsEntry("email", "nimal@example.com");
        assertThat(event.get("shippedAt")).isNotNull();
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void courierNameIsStoredAsConfigured() throws Exception {
        notShipped("MX-10001");

        create("MX-10001", "courier a", "WB1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipment.courier").value("Courier A"));
    }

    @Test
    void anOptionalPickupReceiptPhotoIsStoredInThePrivateBucket() throws Exception {
        notShipped("MX-10001");

        create("MX-10001", "Courier A", "WB55310", new MockMultipartFile("pickupReceipt", "slip.jpg", "image/jpeg", JPEG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipment.hasPickupReceipt").value(true));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), eq(JPEG), eq("image/jpeg"));
        assertThat(key.getValue()).matches("MX-10001/pickup-[0-9a-f-]{36}\\.jpg");
    }

    @Test
    void aPdfIsAcceptedAsAPickupReceipt() throws Exception {
        notShipped("MX-10001");

        create("MX-10001", "Courier A", "WB1", new MockMultipartFile("pickupReceipt", "slip.pdf", "application/pdf", PDF))
                .andExpect(status().isCreated());
        verify(storage).put(anyString(), eq(PDF), eq("application/pdf"));
    }

    @Test
    void aTextFileCalledJpgIsRefusedAndNothingChanges() throws Exception {
        notShipped("MX-10001");

        create("MX-10001", "Courier A", "WB1",
                new MockMultipartFile("pickupReceipt", "slip.jpg", "image/jpeg", "not an image".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));

        verify(storage, never()).put(anyString(), any(), anyString());
        assertThat(orders.findByOrderNo("MX-10001").orElseThrow().getShipmentStatus()).isEqualTo(ShipmentStatus.NOT_SHIPPED);
    }

    @Test
    void anUnknownCourierIsRefused() throws Exception {
        notShipped("MX-10001");

        create("MX-10001", "Pigeon Post", "WB1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Courier A, Courier B")));
    }

    @Test
    void aBadTrackingNumberIsRefused() throws Exception {
        notShipped("MX-10001");

        create("MX-10001", "Courier A", "WB 55310; drop table").andExpect(status().isBadRequest());
        create("MX-10001", "Courier A", "  ").andExpect(status().isBadRequest());
    }

    @Test
    void aMissingFieldIs400() throws Exception {
        mockMvc.perform(multipart(BASE).param("orderNo", "MX-10001").cookie(TestTokens.adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theSameCourierCannotUseATrackingNumberTwice() throws Exception {
        inTransit("MX-10001", "Courier A", "WB55310");
        notShipped("MX-10002");

        create("MX-10002", "Courier A", "WB55310")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACKING_NUMBER_TAKEN"));
        create("MX-10002", "Courier B", "WB55310").andExpect(status().isCreated());
    }

    @Test
    void aTakenTrackingNumberStoresNoFile() throws Exception {
        inTransit("MX-10001", "Courier A", "WB55310");
        notShipped("MX-10002");

        create("MX-10002", "Courier A", "WB55310", new MockMultipartFile("pickupReceipt", "s.jpg", "image/jpeg", JPEG))
                .andExpect(status().isConflict());
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void onlyANotShippedOrderCanGetAShipment() throws Exception {
        Order cancelled = TestOrders.order("MX-10001", 7);
        cancelled.setShipmentStatus(ShipmentStatus.CANCELLED);
        orders.save(cancelled);
        inTransit("MX-10002", "Courier A", "WB1");

        create("MX-10001", "Courier A", "WB2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
        create("MX-10002", "Courier A", "WB3").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
        create("MX-99999", "Courier A", "WB4").andExpect(status().isNotFound());
    }

    @Test
    void whenTheFileStorageIsDownNothingIsSaved() throws Exception {
        notShipped("MX-10001");
        doThrow(new StorageException("down", null)).when(storage).put(anyString(), any(), anyString());

        create("MX-10001", "Courier A", "WB1", new MockMultipartFile("pickupReceipt", "s.jpg", "image/jpeg", JPEG))
                .andExpect(status().isServiceUnavailable());

        assertThat(orders.findByOrderNo("MX-10001").orElseThrow().getShipmentStatus()).isEqualTo(ShipmentStatus.NOT_SHIPPED);
    }

    @Test
    void normalUsersCannotCreateShipments() throws Exception {
        notShipped("MX-10001");

        mockMvc.perform(multipart(BASE).param("orderNo", "MX-10001").param("courier", "Courier A")
                        .param("trackingNumber", "WB1").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isForbidden());
    }

    // ---------- delivered ----------

    @Test
    void adminMarksManyParcelsDeliveredAtOnce() throws Exception {
        inTransit("MX-10001", "Courier A", "A1");
        inTransit("MX-10002", "Courier A", "A2");
        String ids = shipmentId("MX-10001") + "," + shipmentId("MX-10002");

        mockMvc.perform(post(BASE + "/delivered").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shipmentIds\":[" + ids + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done", hasSize(2)))
                .andExpect(jsonPath("$.failed", hasSize(0)));

        for (String orderNo : List.of("MX-10001", "MX-10002")) {
            tx.executeWithoutResult(s -> {
                Order order = orders.findByOrderNo(orderNo).orElseThrow();
                assertThat(order.getShipmentStatus()).isEqualTo(ShipmentStatus.DELIVERED);
                assertThat(order.getPaymentStatus()).as("only payment events change this").isEqualTo(PaymentStatus.PENDING);
                assertThat(order.getShipment().getStatus()).isEqualTo(ShipmentState.DELIVERED);
                assertThat(order.getShipment().getDeliveredAt()).isNotNull();
            });
        }
        verify(rabbit, times(2)).convertAndSend(eq("app.events"), eq("order.delivered"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void ifOneShipmentIsNotOnTheRoadNothingIsChanged() throws Exception {
        inTransit("MX-10001", "Courier A", "A1");
        Order done = inTransit("MX-10002", "Courier A", "A2");
        tx.executeWithoutResult(s -> orders.findByOrderNo("MX-10002").orElseThrow().getShipment().setStatus(ShipmentState.DELIVERED));
        String ids = shipmentId("MX-10001") + "," + shipmentId(done.getOrderNo());

        mockMvc.perform(post(BASE + "/delivered").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shipmentIds\":[" + ids + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"))
                .andExpect(jsonPath("$.details", hasSize(1)));

        assertThat(orders.findByOrderNo("MX-10001").orElseThrow().getShipmentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void anUnknownShipmentIdIsRefused() throws Exception {
        mockMvc.perform(post(BASE + "/delivered").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shipmentIds\":[999999]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("Shipment 999999 does not exist."));
    }

    @Test
    void anEmptyListIsRefused() throws Exception {
        mockMvc.perform(post(BASE + "/delivered").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shipmentIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- returned ----------

    @Test
    void returnedGivesTheStockBackAndSavesTheReason() throws Exception {
        inTransit("MX-10001", "Courier A", "A1");
        Long id = shipmentId("MX-10001");

        mockMvc.perform(post(BASE + "/returned").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shipmentIds\":[" + id + "],\"reason\":\"REFUSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done", hasSize(1)));

        verify(productClient).release("MX-10001");
        tx.executeWithoutResult(s -> {
            Order order = orders.findByOrderNo("MX-10001").orElseThrow();
            assertThat(order.getShipmentStatus()).isEqualTo(ShipmentStatus.RETURNED);
            assertThat(order.getShipment().getStatus()).isEqualTo(ShipmentState.RETURNED);
            assertThat(order.getShipment().getReturnReason()).isEqualTo(ReturnReason.REFUSED);
        });
        Map<String, Object> event = publishedBody("order.returned", 1);
        assertThat(event).containsEntry("reason", "REFUSED").containsEntry("courier", "Courier A")
                .containsEntry("trackingNumber", "A1");
        assertThat(event.get("returnedAt")).isNotNull();
    }

    @Test
    void ifProductServiceIsDownThatParcelFailsAndTheOthersAreDone() throws Exception {
        inTransit("MX-10001", "Courier A", "A1");
        inTransit("MX-10002", "Courier A", "A2");
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PRODUCT_SERVICE_UNAVAILABLE, "down", List.of()))
                .when(productClient).release("MX-10001");
        String ids = shipmentId("MX-10001") + "," + shipmentId("MX-10002");

        mockMvc.perform(post(BASE + "/returned").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shipmentIds\":[" + ids + "],\"reason\":\"NOT_REACHABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done", hasSize(1)))
                .andExpect(jsonPath("$.done[0].orderNo").value("MX-10002"))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].orderNo").value("MX-10001"))
                .andExpect(jsonPath("$.failed[0].code").value("PRODUCT_SERVICE_UNAVAILABLE"));

        assertThat(orders.findByOrderNo("MX-10001").orElseThrow().getShipmentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(orders.findByOrderNo("MX-10002").orElseThrow().getShipmentStatus()).isEqualTo(ShipmentStatus.RETURNED);
    }

    @Test
    void returnedNeedsAReason() throws Exception {
        mockMvc.perform(post(BASE + "/returned").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shipmentIds\":[1]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/returned").cookie(TestTokens.adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"shipmentIds\":[1],\"reason\":\"BORED\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- list and receipt ----------

    @Test
    void listFiltersByCourierAndStatus() throws Exception {
        inTransit("MX-10001", "Courier A", "A1");
        inTransit("MX-10002", "Courier B", "B1");
        tx.executeWithoutResult(s -> orders.findByOrderNo("MX-10002").orElseThrow().getShipment().setStatus(ShipmentState.DELIVERED));

        mockMvc.perform(get(BASE).cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(BASE + "?courier=courier a").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderNo").value("MX-10001"))
                .andExpect(jsonPath("$.content[0].customerName").value("Nimal Perera"))
                .andExpect(jsonPath("$.content[0].paymentStatus").value("PENDING"));
        mockMvc.perform(get(BASE + "?status=DELIVERED").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[0].orderNo").value("MX-10002"));
    }

    @Test
    void adminDownloadsThePickupReceipt() throws Exception {
        Order order = TestOrders.order("MX-10001", 7);
        order.setShipmentStatus(ShipmentStatus.IN_TRANSIT);
        order.addShipment(new Shipment("Courier A", "A1", "MX-10001/pickup-x.jpg", 1L));
        orders.save(order);
        when(storage.get("MX-10001/pickup-x.jpg")).thenReturn(Optional.of(new StoredFile(JPEG, "image/jpeg")));

        mockMvc.perform(get(BASE + "/" + shipmentId("MX-10001") + "/pickup-receipt").cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", matchesPattern(".*no-store.*")))
                .andExpect(content().bytes(JPEG));
    }

    @Test
    void aShipmentWithoutAReceiptIs404AndNormalUsersAreRefused() throws Exception {
        inTransit("MX-10001", "Courier A", "A1");
        Long id = shipmentId("MX-10001");

        mockMvc.perform(get(BASE + "/" + id + "/pickup-receipt").cookie(TestTokens.adminCookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + id + "/pickup-receipt").cookie(TestTokens.userCookie(7)))
                .andExpect(status().isForbidden());
    }
}
