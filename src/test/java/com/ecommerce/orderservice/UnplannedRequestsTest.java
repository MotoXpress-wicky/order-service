package com.ecommerce.orderservice;

import com.ecommerce.orderservice.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A client mistake must be answered with the right 4xx status and the shared error JSON, never with a 500
 * (a 500 means "the server is broken": it would start alarms and hide real problems).
 * Found by sending these requests to the real services: several answered 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnplannedRequestsTest {

    private static final String BASE = "/api/v1/order";
    private static final String JSON_POST = "/api/v1/order/orders";
    private static final String COLLECTION = "/api/v1/order/admin/orders";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("an address that does not exist: 404 NOT_FOUND")
    void unknownPath() throws Exception {
        mockMvc.perform(get(BASE + "/does-not-exist").cookie(TestTokens.adminCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("a method the address does not know: 405 METHOD_NOT_ALLOWED with an Allow header")
    void wrongMethod() throws Exception {
        mockMvc.perform(put(COLLECTION).cookie(TestTokens.adminCookie()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("a content type the address does not read: 415 UNSUPPORTED_MEDIA_TYPE")
    void wrongContentType() throws Exception {
        mockMvc.perform(post(JSON_POST).cookie(TestTokens.userCookie(1)).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("a body that is not JSON: 400 INVALID_REQUEST")
    void malformedJson() throws Exception {
        mockMvc.perform(post(JSON_POST).cookie(TestTokens.userCookie(1)).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
