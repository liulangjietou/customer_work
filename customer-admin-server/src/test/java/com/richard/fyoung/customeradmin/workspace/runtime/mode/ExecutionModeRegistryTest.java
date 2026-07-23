package com.richard.fyoung.customeradmin.workspace.runtime.mode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ExecutionModeRegistry} 单测：登记/读取/摘除、sessionId 归一、null 清除、会话隔离。
 * @author owlzhangfq@gmail.com
 */
class ExecutionModeRegistryTest {

    private final ExecutionModeRegistry registry = new ExecutionModeRegistry();

    @Test
    void put_then_get_shouldReturnMode() {
        registry.put("coder", "s1", ExecutionMode.PLAN);
        assertEquals(ExecutionMode.PLAN, registry.get("coder", "s1"));
    }

    @Test
    void get_shouldReturnNull_whenUnregistered() {
        assertNull(registry.get("coder", "missing"), "未登记应返回 null，交由全局回落");
    }

    @Test
    void put_null_shouldClearExisting() {
        registry.put("coder", "s1", ExecutionMode.MANUAL);
        registry.put("coder", "s1", null);
        assertNull(registry.get("coder", "s1"), "null 视为未指定，应清除已有登记");
    }

    @Test
    void remove_shouldDropRegistration() {
        registry.put("coder", "s1", ExecutionMode.AUTO);
        registry.remove("coder", "s1");
        assertNull(registry.get("coder", "s1"));
    }

    @Test
    void blankSessionId_shouldNormalizeToDefault() {
        registry.put("coder", null, ExecutionMode.BYPASS);
        assertEquals(ExecutionMode.BYPASS, registry.get("coder", "default"), "空 sessionId 归一为 default，与 RuntimeContext 一致");
        assertEquals(ExecutionMode.BYPASS, registry.get("coder", ""));
    }

    @Test
    void differentSessions_shouldBeIsolated() {
        registry.put("coder", "s1", ExecutionMode.PLAN);
        registry.put("coder", "s2", ExecutionMode.MANUAL);
        assertEquals(ExecutionMode.PLAN, registry.get("coder", "s1"));
        assertEquals(ExecutionMode.MANUAL, registry.get("coder", "s2"));
    }
}
