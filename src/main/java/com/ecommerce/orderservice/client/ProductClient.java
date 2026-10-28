package com.ecommerce.orderservice.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * The calls to product-service (stock lives there). The rest of the service only knows this interface.
 * Both calls are safe to repeat: product-service answers the same for the same order number.
 */
public interface ProductClient {

    /**
     * Takes the stock for these lines and answers with the real price of each line.
     * Throws ApiException: 409 OUT_OF_STOCK when a line is short, 503 PRODUCT_SERVICE_UNAVAILABLE when it cannot be reached.
     */
    List<ReservedLine> reserve(String orderNo, List<WantedLine> lines);

    /** Gives the stock of this order back. Throws ApiException 503 when product-service cannot be reached. */
    void release(String orderNo);

    record WantedLine(Long productId, int quantity) {
    }

    record ReservedLine(Long productId, String partNumber, String name, int quantity,
                        BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
