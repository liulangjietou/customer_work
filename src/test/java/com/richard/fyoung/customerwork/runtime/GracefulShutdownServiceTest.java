package com.richard.fyoung.customerwork.runtime;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 优雅停机服务单测：默认接收请求、相位顺序正确、start/stop 生命周期状态切换。
 * 不触发真实 JVM 停机。
 * @author owlzhangfq@gmail.com
 */
class GracefulShutdownServiceTest {

    private final GracefulShutdownService service =
        new GracefulShutdownService(new CustomerWorkProperties());

    @Test
    void shouldBeAcceptingRequests_byDefault() {
        assertTrue(service.isAcceptingRequests());
        assertEquals(0, service.activeRequests());
    }

    @Test
    void lifecycle_startShouldMarkRunning() {
        service.start();
        assertTrue(service.isRunning());
    }

    @Test
    void phase_shouldBeLateStartEarlyStop() {
        assertTrue(service.getPhase() > 1_000_000, "应取较大相位以便晚启动早停止");
    }

    @Test
    void isRunning_shouldBeFalse_beforeStart() {
        assertFalse(new GracefulShutdownService(new CustomerWorkProperties()).isRunning());
    }
}
