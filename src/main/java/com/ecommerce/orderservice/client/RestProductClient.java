package com.ecommerce.orderservice.client;

import com.ecommerce.orderservice.config.ProductServiceProperties;
import com.ecommerce.orderservice.config.filter.RequestIdFilter;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ProductClient over HTTP with Spring's RestClient.
 * Short timeouts, the trace id is passed on, and a failed call is tried one more time
 * (safe, because product-service treats a repeated order number as a repeat).
 */
@Slf4j
@Component
public class RestProductClient implements ProductClient {

    private static final int ATTEMPTS = 2;

    private final RestClient http;

    public RestProductClient(ProductServiceProperties properties) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(properties.readTimeout());

        this.http = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public List<ReservedLine> reserve(String orderNo, List<WantedLine> lines) {
        Map<String, Object> body = Map.of("orderNo", orderNo, "lines", lines);

        ReservationAnswer answer = withRetry(() -> http.post()
                .uri("/internal/v1/product/reservations")
                .header(RequestIdFilter.HEADER, traceId())
                .body(body)
                .retrieve()
                .body(ReservationAnswer.class));

        if (answer == null || answer.lines() == null) {
            throw unavailable(null);
        }
        return answer.lines();
    }

    @Override
    public void release(String orderNo) {
        withRetry(() -> {
            http.delete()
                    .uri("/internal/v1/product/reservations/{orderNo}", orderNo)
                    .header(RequestIdFilter.HEADER, traceId())
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    // ---------- helpers ----------

    private <T> T withRetry(Supplier<T> call) {
        ApiException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                ApiException translated = translate(e);
                if (!e.getStatusCode().is5xxServerError()) {
                    throw translated; // a clear answer (409, 404, 400): asking again changes nothing
                }
                last = translated;
            } catch (ResourceAccessException e) {
                last = unavailable(e);
            }
            log.warn("product-service call failed (attempt {} of {})", attempt, ATTEMPTS);
        }
        throw last;
    }

    private ApiException translate(RestClientResponseException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        Map<?, ?> error = readError(e);

        if (status == HttpStatus.CONFLICT) {
            String code = String.valueOf(error.get("code"));
            ErrorCode errorCode = ErrorCode.OUT_OF_STOCK.name().equals(code) ? ErrorCode.OUT_OF_STOCK : ErrorCode.DATA_CONFLICT;
            String message = error.get("message") instanceof String text ? text : errorCode.getMessage();
            List<String> details = error.get("details") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            return ApiException.conflict(errorCode, message, details);
        }
        if (status == HttpStatus.NOT_FOUND) {
            return ApiException.conflict(ErrorCode.OUT_OF_STOCK, "A part in your order is no longer available.");
        }
        if (status == HttpStatus.BAD_REQUEST) {
            log.error("product-service refused the request: {}", error);
            return ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "The stock service refused the order lines.");
        }
        return unavailable(e);
    }

    private Map<?, ?> readError(RestClientResponseException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            return body == null ? Map.of() : body;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private ApiException unavailable(Exception cause) {
        log.error("product-service is not available", cause);
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PRODUCT_SERVICE_UNAVAILABLE,
                ErrorCode.PRODUCT_SERVICE_UNAVAILABLE.getMessage(), List.of());
    }

    private String traceId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id == null ? "no-trace" : id;
    }

    /** The answer of POST /internal/v1/product/reservations. */
    record ReservationAnswer(String orderNo, List<ReservedLine> lines) {
    }
}
