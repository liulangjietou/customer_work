package com.richard.fyoung.customeradmin.workspace.security;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** {@link AdminAgentIdentityInterceptor} 登录主体绑定与线程清理契约测试。 */
class AdminAgentIdentityInterceptorTest {

    private final AdminAgentIdentityInterceptor interceptor = new AdminAgentIdentityInterceptor();

    @AfterEach
    void tearDown() {
        AgentInvocationIdentityContext.clear();
    }

    @Test
    void preHandle_shouldBindAuthenticatedAdminForHistoryReads() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class);
             MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsString).thenReturn("42");
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("tenant-a");

            assertTrue(interceptor.preHandle(request(), response(), new Object()));

            AgentInvocationIdentity identity = AgentInvocationIdentityContext.get();
            assertEquals("tenant-a", identity.tenantId());
            assertEquals(QuotaSubjectType.ADMIN_USER, identity.subjectType());
            assertEquals("42", identity.subjectId());
            assertEquals(AgentInvocationIdentity.CHANNEL_ADMIN, identity.channelCode());
        }
    }

    @Test
    void preHandle_shouldClearStaleIdentity_whenNotLoggedIn() {
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(
            "stale", QuotaSubjectType.ADMIN_USER, "old", true));
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            assertTrue(interceptor.preHandle(request(), response(), new Object()));
            assertNull(AgentInvocationIdentityContext.get());
        }
    }

    @Test
    void afterCompletion_shouldClearIdentity() {
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(
            "tenant-a", QuotaSubjectType.ADMIN_USER, "42", true));

        interceptor.afterCompletion(request(), response(), new Object(), null);

        assertNull(AgentInvocationIdentityContext.get());
    }

    @Test
    void afterConcurrentHandlingStarted_shouldClearRequestThreadIdentity() {
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(
            "tenant-a", QuotaSubjectType.ADMIN_USER, "42", true));

        interceptor.afterConcurrentHandlingStarted(request(), response(), new Object());

        assertNull(AgentInvocationIdentityContext.get());
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/workspace/java-assistant/chat/sessions");
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }
}
