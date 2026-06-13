package com.example.customerwork.health;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话后端健康检查单测：可用→UP，探测异常→DOWN，并附带后端类型。
 * @author owlzhangfq@gmail.com
 */
class SessionHealthIndicatorTest {

    @Test
    void health_shouldBeUp_whenSessionReachable() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSession().setMode("memory");
        SessionHealthIndicator indicator =
            new SessionHealthIndicator(new InMemorySession(), props);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("memory", health.getDetails().get("backend"));
    }

    @Test
    void health_shouldBeDown_whenSessionThrows() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSession().setMode("redis");
        Session failing = mock(Session.class);
        when(failing.exists(any(SessionKey.class)))
            .thenThrow(new RuntimeException("connection refused"));

        Health health = new SessionHealthIndicator(failing, props).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("redis", health.getDetails().get("backend"));
    }
}
