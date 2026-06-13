package com.richard.fyoung.customerwork.runtime;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.session.InMemorySession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时维护任务单测：巡检输出包含会话后端、在途请求与是否接收请求等运维信号。
 * @author owlzhangfq@gmail.com
 */
class MaintenanceSchedulerTest {

    @Test
    void runMaintenance_shouldSummarizeRuntimeState() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSession().setMode("memory");
        GracefulShutdownService shutdown = new GracefulShutdownService(props);
        MaintenanceScheduler scheduler =
            new MaintenanceScheduler(props, new InMemorySession(), shutdown);

        String summary = scheduler.runMaintenance();

        assertTrue(summary.contains("backend=memory"), summary);
        assertTrue(summary.contains("impl=InMemorySession"), summary);
        assertTrue(summary.contains("activeRequests="), summary);
        assertTrue(summary.contains("accepting="), summary);
    }
}
