package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.safety.security.RequestIdWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 验证全局异常处理把 requestId 带进错误响应体（供用户报障时自助提供定位凭据）。
 * @author owlzhangfq@gmail.com
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ServerWebExchange exchangeWithRequestId(String requestId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/chat").build());
        if (requestId != null) {
            exchange.getResponse().getHeaders().set(RequestIdWebFilter.HEADER, requestId);
        }
        return exchange;
    }

    @Test
    @SuppressWarnings("unchecked")
    void genericErrorBodyContainsRequestId() {
        ServerWebExchange exchange = exchangeWithRequestId("req-abc123");
        ResponseEntity<Map<String, Object>> resp =
            handler.handleGeneric(new RuntimeException("boom"), exchange);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertEquals("req-abc123", body.get("requestId"));
        assertEquals(500, body.get("status"));
    }

    @Test
    void responseStatusErrorBodyContainsRequestId() {
        ServerWebExchange exchange = exchangeWithRequestId("req-xyz");
        ResponseEntity<Map<String, Object>> resp = handler.handleResponseStatus(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "approval not found"), exchange);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("req-xyz", resp.getBody().get("requestId"));
        assertEquals("approval not found", resp.getBody().get("message"));
    }

    @Test
    void bodyOmitsRequestIdWhenHeaderAbsent() {
        ServerWebExchange exchange = exchangeWithRequestId(null);
        ResponseEntity<Map<String, Object>> resp =
            handler.handleGeneric(new RuntimeException("boom"), exchange);

        assertFalse(resp.getBody().containsKey("requestId"),
            "无 X-Request-Id 响应头时不应放入空 requestId 字段");
    }
}
