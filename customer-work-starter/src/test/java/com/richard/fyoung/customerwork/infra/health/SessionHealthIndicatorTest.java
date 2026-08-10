package com.richard.fyoung.customerwork.infra.health;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态后端健康检查单测：可用→UP，探测异常→DOWN，并附带后端类型。
 * @author owlzhangfq@gmail.com
 */
class SessionHealthIndicatorTest {

    @Test
    void health_shouldBeUp_whenStoreReachable() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSession().setMode("memory");
        SessionHealthIndicator indicator =
            new SessionHealthIndicator(new InMemoryAgentStateStore(), props);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("memory", health.getDetails().get("backend"));
    }

    @Test
    void health_shouldBeDown_whenStoreThrows() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSession().setMode("redis");
        AgentStateStore failing = mock(AgentStateStore.class);
        when(failing.exists(anyString(), anyString()))
            .thenThrow(new RuntimeException("connection refused"));

        Health health = new SessionHealthIndicator(failing, props).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("redis", health.getDetails().get("backend"));
    }
}
