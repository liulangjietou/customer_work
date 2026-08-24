package com.richard.fyoung.customeradmin.openapi;

import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.core.constant.OpenApiProtocol;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

/**
 * {@link OpenApiAuthInterceptor} 单测：token 未配置 / 缺失 / 不匹配一律 401，匹配放行。
 * @author owlzhangfq@gmail.com
 */
class OpenApiAuthInterceptorTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        QuotaSubjectContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    private OpenApiAuthInterceptor interceptor(String configuredToken) {
        OpenApiProperties props = new OpenApiProperties();
        props.setToken(configuredToken);
        return new OpenApiAuthInterceptor(props, new AdminTenantProperties());
    }

    private OpenApiAuthInterceptor tenantInterceptor(Map<String, String> tenantTokens) {
        OpenApiProperties props = new OpenApiProperties();
        props.setToken("legacy-token");
        props.setTenantTokens(tenantTokens);
        AdminTenantProperties tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        return new OpenApiAuthInterceptor(props, tenant);
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/open/channel/robots");
        if (token != null) {
            req.addHeader(OpenApiProtocol.TOKEN_HEADER, token);
        }
        return req;
    }

    @Test
    void shouldReject_whenTokenNotConfigured() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean pass = interceptor("").preHandle(requestWithToken("anything"), resp, new Object());

        assertFalse(pass);
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("OPEN-API-AUTH-FAIL"));
    }

    @Test
    void shouldReject_whenHeaderMissing() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean pass = interceptor("secret-token").preHandle(requestWithToken(null), resp, new Object());

        assertFalse(pass);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void shouldReject_whenTokenMismatch() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean pass = interceptor("secret-token").preHandle(requestWithToken("wrong-token"), resp, new Object());

        assertFalse(pass);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void shouldPass_whenTokenMatches() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean pass = interceptor("secret-token").preHandle(requestWithToken("secret-token"), resp, new Object());

        assertTrue(pass);
        assertEquals(200, resp.getStatus());
        assertEquals(QuotaSubjectType.API_KEY, QuotaSubjectContext.get().type());
        assertEquals(QuotaSubjectType.API_KEY,
            AgentInvocationIdentityContext.get().subjectType());
    }

    @Test
    void tenantModeShouldResolveTenantFromCredentialAndRejectLegacyToken() throws Exception {
        OpenApiAuthInterceptor interceptor = tenantInterceptor(Map.of(
            "tenant-a-token", "tenant-a",
            "tenant-b-token", "tenant-b"));

        assertTrue(interceptor.preHandle(requestWithToken("tenant-a-token"),
            new MockHttpServletResponse(), new Object()));
        assertEquals("tenant-a", TenantContext.get());

        TenantContext.clear();
        MockHttpServletResponse legacyResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(requestWithToken("legacy-token"), legacyResponse, new Object()));
        assertEquals(401, legacyResponse.getStatus());
    }

    @Test
    void tenantModeShouldRejectCredentialMappedToInvalidTenant() throws Exception {
        OpenApiAuthInterceptor interceptor = tenantInterceptor(Map.of("legacy-tenant-token", "_legacy"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(
            requestWithToken("legacy-tenant-token"), response, new Object());

        assertFalse(pass);
        assertEquals(401, response.getStatus());
        assertFalse(TenantContext.isPresent());
        assertFalse(QuotaSubjectContext.isPresent());
        assertTrue(AgentInvocationIdentityContext.get() == null);
    }

    @Test
    void shouldClearTenantAfterRequestCompletion() throws Exception {
        OpenApiAuthInterceptor interceptor = tenantInterceptor(Map.of("tenant-token", "tenant-a"));
        MockHttpServletRequest request = requestWithToken("tenant-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertFalse(TenantContext.isPresent());
    }
}
