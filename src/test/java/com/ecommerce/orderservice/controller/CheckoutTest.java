package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.TestTokens;
import com.ecommerce.orderservice.client.ProductClient;
import com.ecommerce.orderservice.client.ProductClient.ReservedLine;
import com.ecommerce.orderservice.client.ProductClient.WantedLine;
import com.ecommerce.orderservice.entity.Enums.PaymentStatus;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout from the browser's point of view. product-service and RabbitMQ are faked.
 * Not @Transactional: the order must really commit, because the event is sent only after the commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckoutTest {

    private static final String URL = "/api/v1/order/orders";
    private static final String ADDRESS = """
            "address":{"phone":"077 123 4567","addressLine":"12 Galle Road","city":"Colombo","district":"Colombo"}""";

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

    @AfterEach
    void clean() {
        orders.deleteAll();
    }

    private ReservedLine sprocket(int quantity) {
        return new ReservedLine(1L, "123HG", "Sprocket", quantity, new BigDecimal("4500.00"),
                new BigDecimal("4500.00").multiply(BigDecimal.valueOf(quantity)));
    }

    private ResultActions checkout(String lines) throws Exception {
        return mockMvc.perform(post(URL).cookie(TestTokens.userCookie(42))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":" + lines + "," + ADDRESS + "}"));
    }

    @Test
    void placesAnOrderWithRealPricesAndTheDeliveryFee() throws Exception {
        when(productClient.reserve(anyString(), any())).thenReturn(List.of(sprocket(2)));

        checkout("[{\"productId\":1,\"quantity\":2}]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value(matchesPattern("MX-\\d{5,}")))
                .andExpect(jsonPath("$.shipmentStatus").value("NOT_SHIPPED"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.paymentMethod").value("COD"))
                .andExpect(jsonPath("$.itemsTotal").value(9000.00))
                .andExpect(jsonPath("$.deliveryFee").value(500.00))
                .andExpect(jsonPath("$.total").value(9500.00))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.items[0].partNumber").value("123HG"))
                .andExpect(jsonPath("$.customer.name").value("Nimal Perera"))
                .andExpect(jsonPath("$.customer.city").value("Colombo"));

        // reading the items is lazy, so it needs an open transaction
        tx.executeWithoutResult(status -> {
            Order saved = orders.findAll().get(0);
            assertThat(saved.getUserId()).isEqualTo(42L);
            assertThat(saved.getShipmentStatus()).isEqualTo(ShipmentStatus.NOT_SHIPPED);
            assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(saved.getItems()).hasSize(1);
        });
    }

    @Test
    void aPriceSentByTheBrowserIsIgnored() throws Exception {
        when(productClient.reserve(anyString(), any())).thenReturn(List.of(sprocket(1)));

        checkout("[{\"productId\":1,\"quantity\":1,\"unitPrice\":1.00}]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemsTotal").value(4500.00));
    }

    @Test
    void theSamePartTwiceInTheCartIsAskedAsOneLine() throws Exception {
        when(productClient.reserve(anyString(), any())).thenReturn(List.of(sprocket(5)));

        checkout("[{\"productId\":1,\"quantity\":2},{\"productId\":1,\"quantity\":3}]").andExpect(status().isCreated());

        ArgumentCaptor<List<WantedLine>> lines = ArgumentCaptor.captor();
        verify(productClient).reserve(anyString(), lines.capture());
        assertThat(lines.getValue()).containsExactly(new WantedLine(1L, 5));
    }

    @Test
    void publishesOrderPlacedAfterTheCommitWithTheAgreedBody() throws Exception {
        when(productClient.reserve(anyString(), any())).thenReturn(List.of(sprocket(2)));

        checkout("[{\"productId\":1,\"quantity\":2}]").andExpect(status().isCreated());

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq("app.events"), eq("order.placed"), body.capture(), any(MessagePostProcessor.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) body.getValue();

        assertThat(event).containsEntry("userId", "42")
                .containsEntry("email", "nimal@example.com")
                .containsEntry("name", "Nimal Perera")
                .containsEntry("paymentMethod", "COD")
                .containsEntry("itemsTotal", "9000.00")
                .containsEntry("deliveryFee", "500.00")
                .containsEntry("total", "9500.00")
                .containsEntry("currency", "LKR");
        assertThat(event.get("orderNo")).isEqualTo(event.get("reference"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) event.get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).containsEntry("partNumber", "123HG").containsEntry("quantity", 2)
                .containsEntry("unitPrice", "4500.00").containsEntry("lineTotal", "9000.00");
    }

    @Test
    void notEnoughStockIs409AndNothingIsSavedOrPublished() throws Exception {
        when(productClient.reserve(anyString(), any())).thenThrow(
                ApiException.conflict(ErrorCode.OUT_OF_STOCK, "Not enough stock for this order.",
                        List.of("123HG Sprocket: wanted 5, only 1 left")));

        checkout("[{\"productId\":1,\"quantity\":5}]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.details[0]").value("123HG Sprocket: wanted 5, only 1 left"));

        assertThat(orders.count()).isZero();
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void whenProductServiceIsDownTheAnswerIs503() throws Exception {
        when(productClient.reserve(anyString(), any())).thenThrow(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PRODUCT_SERVICE_UNAVAILABLE, "down", List.of()));

        checkout("[{\"productId\":1,\"quantity\":1}]")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
        assertThat(orders.count()).isZero();
    }

    @Test
    void ifSavingFailsTheStockIsGivenBack() throws Exception {
        // a line without a part number breaks the NOT NULL rule of the database
        ReservedLine broken = new ReservedLine(1L, null, "Sprocket", 1, new BigDecimal("10.00"), new BigDecimal("10.00"));
        when(productClient.reserve(anyString(), any())).thenReturn(List.of(broken));

        // the database refuses the broken line; the shared handler answers 409 DATA_CONFLICT
        checkout("[{\"productId\":1,\"quantity\":1}]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        ArgumentCaptor<String> orderNo = ArgumentCaptor.forClass(String.class);
        verify(productClient).release(orderNo.capture());
        assertThat(orderNo.getValue()).startsWith("MX-");
        assertThat(orders.count()).isZero();
    }

    @Test
    void emptyCartIsRefused() throws Exception {
        checkout("[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void zeroQuantityIsRefused() throws Exception {
        checkout("[{\"productId\":1,\"quantity\":0}]").andExpect(status().isBadRequest());
    }

    @Test
    void tooManyPiecesOfOnePartIsRefused() throws Exception {
        checkout("[{\"productId\":1,\"quantity\":60},{\"productId\":1,\"quantity\":60}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(productClient, never()).reserve(anyString(), any());
    }

    @Test
    void aBadPhoneNumberIsRefused() throws Exception {
        mockMvc.perform(post(URL).cookie(TestTokens.userCookie(42)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":1,\"quantity\":1}],"
                                + "\"address\":{\"phone\":\"call me\",\"addressLine\":\"x\",\"city\":\"y\",\"district\":\"z\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("address.phone: must be a phone number"));
    }

    @Test
    void aVisitorMustLogInFirst() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":1,\"quantity\":1}]," + ADDRESS + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_MISSING"));
        verify(productClient, never()).reserve(anyString(), any());
    }

    @Test
    void orderNumbersGoUp() throws Exception {
        when(productClient.reserve(anyString(), any())).thenReturn(List.of(sprocket(1)));

        checkout("[{\"productId\":1,\"quantity\":1}]").andExpect(status().isCreated());
        checkout("[{\"productId\":1,\"quantity\":1}]").andExpect(status().isCreated());

        List<String> numbers = orders.findAll().stream().map(Order::getOrderNo).sorted().toList();
        assertThat(numbers).hasSize(2).doesNotHaveDuplicates();
    }
}
