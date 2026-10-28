package com.ecommerce.orderservice.client;

import com.ecommerce.orderservice.client.ProductClient.WantedLine;
import com.ecommerce.orderservice.config.ProductServiceProperties;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a tiny fake product-service started inside the test, so the real HTTP code is exercised. */
class RestProductClientTest {

    HttpServer server;
    RestProductClient client;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastTraceId = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new RestProductClient(new ProductServiceProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.clear();
    }

    /** Every request to this path gets the given status and JSON body. */
    private void answer(String path, int status, String json) {
        server.createContext(path, exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastTraceId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
    }

    @Test
    void reserveSendsTheLinesAndReadsTheRealPrices() {
        MDC.put("traceId", "trace-123");
        answer("/internal/v1/product/reservations", 200, """
                {"orderNo":"MX-1","lines":[{"productId":1,"partNumber":"123HG","name":"Sprocket","quantity":2,
                 "unitPrice":4500.00,"lineTotal":9000.00}]}""");

        var lines = client.reserve("MX-1", List.of(new WantedLine(1L, 2)));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).partNumber()).isEqualTo("123HG");
        assertThat(lines.get(0).unitPrice()).isEqualByComparingTo("4500.00");
        assertThat(lines.get(0).lineTotal()).isEqualByComparingTo("9000.00");
        assertThat(lastBody.get()).contains("\"orderNo\":\"MX-1\"").contains("\"productId\":1").contains("\"quantity\":2");
        assertThat(lastTraceId.get()).isEqualTo("trace-123");
    }

    @Test
    void outOfStockKeepsTheCodeAndTheList() {
        answer("/internal/v1/product/reservations", 409, """
                {"status":409,"code":"OUT_OF_STOCK","message":"Not enough stock for this order.",
                 "details":["123HG Sprocket: wanted 5, only 1 left"]}""");

        assertThatThrownBy(() -> client.reserve("MX-1", List.of(new WantedLine(1L, 5))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo(ErrorCode.OUT_OF_STOCK);
                    assertThat(e.getDetails()).containsExactly("123HG Sprocket: wanted 5, only 1 left");
                });
        assertThat(calls.get()).as("a clear answer is not asked twice").isEqualTo(1);
    }

    @Test
    void anUnknownPartBecomesOutOfStock() {
        answer("/internal/v1/product/reservations", 404, "{\"code\":\"NOT_FOUND\"}");

        assertThatThrownBy(() -> client.reserve("MX-1", List.of(new WantedLine(99L, 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.OUT_OF_STOCK));
    }

    @Test
    void aServerErrorIsTriedTwiceThenBecomes503() {
        answer("/internal/v1/product/reservations", 500, "{\"code\":\"INTERNAL_ERROR\"}");

        assertThatThrownBy(() -> client.reserve("MX-1", List.of(new WantedLine(1L, 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
                });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void whenNothingListensTheAnswerIs503() {
        server.stop(0);

        assertThatThrownBy(() -> client.reserve("MX-1", List.of(new WantedLine(1L, 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
    }

    @Test
    void releaseCallsDeleteWithTheOrderNumber() {
        answer("/internal/v1/product/reservations/MX-1", 204, "");

        client.release("MX-1");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void releaseFailureBecomes503() {
        answer("/internal/v1/product/reservations/MX-1", 503, "{}");

        assertThatThrownBy(() -> client.release("MX-1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
    }
}
