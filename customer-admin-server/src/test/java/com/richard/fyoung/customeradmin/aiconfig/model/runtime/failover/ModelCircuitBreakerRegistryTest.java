package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import com.richard.fyoung.customeradmin.config.AdminModelFailoverProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelCircuitBreakerRegistry} 单测：阈值触发熔断、过期半开放行、成功重置计数。
 * @author owlzhangfq@gmail.com
 */
class ModelCircuitBreakerRegistryTest {

    private static final Long MODEL_ID = 1L;

    private ModelCircuitBreakerRegistry newRegistry(int threshold, int openSeconds) {
        AdminModelFailoverProperties props = new AdminModelFailoverProperties();
        props.setFailureThreshold(threshold);
        props.setOpenDurationSeconds(openSeconds);
        return new ModelCircuitBreakerRegistry(props);
    }

    @Test
    void shouldOpen_whenConsecutiveFailuresReachThreshold() {
        ModelCircuitBreakerRegistry registry = newRegistry(3, 60);

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID);
        assertFalse(registry.isOpen(MODEL_ID)); // 2 次未达阈值

        registry.recordFailure(MODEL_ID);
        assertTrue(registry.isOpen(MODEL_ID)); // 第 3 次触发熔断
    }

    @Test
    void shouldHalfOpen_whenWindowExpired() {
        // openDurationSeconds=0：熔断打开后立即到期，isOpen 走半开分支放行（重置计数）
        ModelCircuitBreakerRegistry registry = newRegistry(2, 0);

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID); // 达阈值，openUntil≈now

        assertFalse(registry.isOpen(MODEL_ID)); // 窗口已过 -> 半开放行
        // 半开已重置计数：再失败一次不足以立即重新熔断（阈值 2）
        registry.recordFailure(MODEL_ID);
        assertFalse(registry.isOpen(MODEL_ID));
    }

    @Test
    void shouldResetCounter_onSuccess() {
        ModelCircuitBreakerRegistry registry = newRegistry(3, 60);

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID);
        registry.recordSuccess(MODEL_ID); // 清零

        registry.recordFailure(MODEL_ID);
        registry.recordFailure(MODEL_ID);
        assertFalse(registry.isOpen(MODEL_ID)); // 重置后仅 2 次连续失败，未达阈值
    }

    @Test
    void isOpen_shouldReturnFalse_forUnknownModel() {
        ModelCircuitBreakerRegistry registry = newRegistry(3, 60);

        assertFalse(registry.isOpen(999L));
    }
}
