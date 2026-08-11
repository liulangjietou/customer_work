package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import com.richard.fyoung.customeradmin.config.AdminModelFailoverProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * admin 薄壳职责单测：只验证 {@code admin.model.failover.*} 配置属性被正确绑到下沉版熔断登记表的构造参数上。
 * 熔断本身的语义（阈值/半开/重置）由 starter 的同名测试覆盖，这里不重复。
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
    void shouldExtendSharedRegistry() {
        assertInstanceOf(com.richard.fyoung.customerwork.core.model.failover.ModelCircuitBreakerRegistry.class,
            newRegistry(3, 60));
    }

    @Test
    void shouldApplyFailureThresholdFromProperties() {
        ModelCircuitBreakerRegistry registry = newRegistry(2, 60);

        registry.recordFailure(MODEL_ID);
        assertFalse(registry.isOpen(MODEL_ID)); // 1 次未达配置阈值 2

        registry.recordFailure(MODEL_ID);
        assertTrue(registry.isOpen(MODEL_ID)); // 达到配置阈值即熔断
    }

    @Test
    void shouldApplyOpenDurationFromProperties() {
        // openDurationSeconds=0：熔断窗口即刻到期，isOpen 走半开分支放行
        ModelCircuitBreakerRegistry registry = newRegistry(1, 0);

        registry.recordFailure(MODEL_ID);

        assertFalse(registry.isOpen(MODEL_ID));
    }
}
