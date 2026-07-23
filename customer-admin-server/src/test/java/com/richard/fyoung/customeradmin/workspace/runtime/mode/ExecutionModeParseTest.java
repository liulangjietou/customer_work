package com.richard.fyoung.customeradmin.workspace.runtime.mode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ExecutionMode#parse} 单测：五档序列化值解析、大小写/枚举名兼容、未指定与非法值均返回 null
 * （非法值不静默当 AUTO），对应 {@code ChatRequest.mode} 的解析契约。
 * @author owlzhangfq@gmail.com
 */
class ExecutionModeParseTest {

    @Test
    void parse_shouldResolveAllSerializedValues() {
        assertEquals(ExecutionMode.AUTO, ExecutionMode.parse("auto"));
        assertEquals(ExecutionMode.MANUAL, ExecutionMode.parse("manual"));
        assertEquals(ExecutionMode.ACCEPT_EDITS, ExecutionMode.parse("accept_edits"));
        assertEquals(ExecutionMode.PLAN, ExecutionMode.parse("plan"));
        assertEquals(ExecutionMode.BYPASS, ExecutionMode.parse("bypass"));
    }

    @Test
    void parse_shouldBeCaseInsensitive_andAcceptEnumName() {
        assertEquals(ExecutionMode.ACCEPT_EDITS, ExecutionMode.parse("ACCEPT_EDITS"));
        assertEquals(ExecutionMode.PLAN, ExecutionMode.parse("  Plan "));
    }

    @Test
    void parse_shouldReturnNull_whenUnspecified() {
        assertNull(ExecutionMode.parse(null), "null 视为未指定");
        assertNull(ExecutionMode.parse(""), "空白视为未指定");
        assertNull(ExecutionMode.parse("   "));
    }

    @Test
    void parse_shouldReturnNull_whenIllegal() {
        // 非法值走 log.error 后按未指定处理，不静默当成 AUTO
        assertNull(ExecutionMode.parse("turbo"));
        assertNull(ExecutionMode.parse("acceptedits"));
    }
}
