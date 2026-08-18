package com.richard.fyoung.customeradmin.subjectquota;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.subjectquota.runtime.AdminQuotaInterceptor;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectExceedAction;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevel;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台用量判定拦截器单测：关闭放行、未登录放行、额度内放行并写上下文、超限 429、请求后清理上下文。
 * @author owlzhangfq@gmail.com
 */
class AdminQuotaInterceptorTest {

    private static final String ADMIN_ID = "42";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        QuotaSubjectContext.clear();
        TenantContext.clear();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/workspace/demo/chat/stream");
        return request;
    }

    private static SubjectQuotaDecision exceeded() {
        SubjectQuotaLevel level = new SubjectQuotaLevel(null, TenantContext.DEFAULT, "admin-default",
            "后台用户", QuotaSubjectType.ADMIN_USER, 3600, 0L, 200, SubjectExceedAction.BLOCK, true, null);
        return SubjectQuotaDecision.exceeded(SubjectQuotaDecision.LimitKind.REQUEST, level, 200L);
    }

    @Test
    void preHandle_shouldPassThrough_whenDisabled() throws Exception {
        SubjectQuotaGuard guard = mock(SubjectQuotaGuard.class);
        when(guard.isEnabled()).thenReturn(false);
        AdminQuotaInterceptor interceptor = new AdminQuotaInterceptor(guard, objectMapper);

        assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()));
        // 关闭时连登录态都不该去碰，更不该写上下文
        assertNull(QuotaSubjectContext.get());
        verify(guard, never()).check(any(), any());
    }

    @Test
    void preHandle_shouldPassThrough_whenNotLogin() throws Exception {
        SubjectQuotaGuard guard = mock(SubjectQuotaGuard.class);
        when(guard.isEnabled()).thenReturn(true);
        AdminQuotaInterceptor interceptor = new AdminQuotaInterceptor(guard, objectMapper);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);
            // 未登录由 Sa-Token 的鉴权拦截器负责拒绝，这里既不重复判身份，也无从算额度
            assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()));
            verify(guard, never()).check(any(), any());
        }
    }

    @Test
    void preHandle_shouldRecordAndBindSubject_whenAllowed() throws Exception {
        SubjectQuotaGuard guard = mock(SubjectQuotaGuard.class);
        when(guard.isEnabled()).thenReturn(true);
        when(guard.check(any(), any())).thenReturn(SubjectQuotaDecision.allow());
        AdminQuotaInterceptor interceptor = new AdminQuotaInterceptor(guard, objectMapper);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class);
             MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsString).thenReturn(ADMIN_ID);
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("acme");

            assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()));

            QuotaSubject bound = QuotaSubjectContext.get();
            assertNotNull(bound, "放行后必须把主体写进上下文，否则 token 记不到人头上");
            assertEquals(QuotaSubjectType.ADMIN_USER, bound.type());
            assertEquals(ADMIN_ID, bound.id());
            verify(guard).recordRequest(bound);
        }
    }

    @Test
    void preHandle_shouldReject_whenExceeded() throws Exception {
        SubjectQuotaGuard guard = mock(SubjectQuotaGuard.class);
        when(guard.isEnabled()).thenReturn(true);
        when(guard.check(any(), any())).thenReturn(exceeded());
        AdminQuotaInterceptor interceptor = new AdminQuotaInterceptor(guard, objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class);
             MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsString).thenReturn(ADMIN_ID);
            tenantSession.when(TenantSession::effectiveTenant).thenReturn(null);

            assertFalse(interceptor.preHandle(request(), response, new Object()));

            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
            assertEquals("3600", response.getHeader(HttpHeaders.RETRY_AFTER),
                "429 必须带 Retry-After，否则前端只能瞎猜什么时候能重试");
            assertTrue(response.getContentAsString().contains("40043"),
                "用专属错误码而不是权限/参数码：额度用尽既不是没权限也不是参数错");
            // 被拒的请求不占额度：判定只读，记账发生在放行之后
            verify(guard, never()).recordRequest(any());
            assertNull(QuotaSubjectContext.get(), "被拒时不写上下文");
        }
    }

    @Test
    void afterCompletion_shouldClearContext() {
        SubjectQuotaGuard guard = mock(SubjectQuotaGuard.class);
        AdminQuotaInterceptor interceptor = new AdminQuotaInterceptor(guard, objectMapper);
        QuotaSubjectContext.set(QuotaSubject.adminUser(ADMIN_ID));

        interceptor.afterCompletion(request(), new MockHttpServletResponse(), new Object(), null);
        // Tomcat 线程是复用的，不清理会把上一个请求的身份带给下一个人
        assertNull(QuotaSubjectContext.get());
    }
}
