package com.richard.fyoung.customerwork.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * W3C traceparent 解析单测：合法头取出 32 位 trace-id，非法/全零/空返回 null。
 * @author owlzhangfq@gmail.com
 */
class TraceContextWebFilterTest {

    @Test
    void extractsTraceIdFromValidTraceparent() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736",
            TraceContextWebFilter.extractTraceId(traceparent));
    }

    @Test
    void returnsNullForNullOrBlank() {
        assertNull(TraceContextWebFilter.extractTraceId(null));
        assertNull(TraceContextWebFilter.extractTraceId("   "));
    }

    @Test
    void returnsNullForMalformed() {
        assertNull(TraceContextWebFilter.extractTraceId("not-a-traceparent"));
        assertNull(TraceContextWebFilter.extractTraceId("00-tooShort-00f067aa0ba902b7-01"));
        // 大写十六进制不符合 W3C 规范（必须小写）
        assertNull(TraceContextWebFilter.extractTraceId(
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"));
    }

    @Test
    void returnsNullForAllZeroTraceId() {
        assertNull(TraceContextWebFilter.extractTraceId(
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
    }
}
