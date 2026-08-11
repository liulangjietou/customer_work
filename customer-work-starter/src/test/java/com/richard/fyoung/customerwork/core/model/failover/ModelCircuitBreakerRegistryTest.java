package com.richard.fyoung.customerwork.core.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelCircuitBreakerRegistry} 单测：阈值触发熔断、过期半开放行、成功重置计数。
 * （由 customer-admin-server 同名测试平移，构造入参从配置类改为显式阈值/时长。）
 * @author owlzhangfq@gmail.com
 */
class ModelCircuitBreakerRegistryTest {

    private static final Long MODEL_ID = 1L;

    @Test
    void shouldOpen_whenConsecutiveFailuresReachThreshold() {
        ModelCircuitBreakerRegistry registry = new ModelCircuitBreakerRegistry(3, 60);

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID);
        assertFalse(registry.isOpen(MODEL_ID)); // 2 次未达阈值

        registry.recordFailure(MODEL_ID);
        assertTrue(registry.isOpen(MODEL_ID)); // 第 3 次触发熔断
    }

    @Test
    void shouldHalfOpen_whenWindowExpired() {
        // openDurationSeconds=0：熔断打开后立即到期，isOpen 走半开分支放行（重置计数）
        ModelCircuitBreakerRegistry registry = new ModelCircuitBreakerRegistry(2, 0);

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID); // 达阈值，openUntil≈now

        assertFalse(registry.isOpen(MODEL_ID)); // 窗口已过 -> 半开放行
        // 半开已重置计数：再失败一次不足以立即重新熔断（阈值 2）
        registry.recordFailure(MODEL_ID);
        assertFalse(registry.isOpen(MODEL_ID));
    }

    @Test
    void shouldResetCounter_onSuccess() {
        ModelCircuitBreakerRegistry registry = new ModelCircuitBreakerRegistry(3, 60);

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID);
        registry.recordSuccess(MODEL_ID); // 清零

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID);
        assertFalse(registry.isOpen(MODEL_ID)); // 重置后仅 2 次连续失败，未达阈值
    }

    @Test
    void isOpen_shouldReturnFalse_forUnknownModel() {
        ModelCircuitBreakerRegistry registry = new ModelCircuitBreakerRegistry(3, 60);

        assertFalse(registry.isOpen(999L));
    }
}
