package com.richard.fyoung.customeradmin.openapi;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customerwork.core.constant.OpenApiProtocol;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigAckAuthInterceptorTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void dedicatedInstanceTokenShouldBindTenantAndInstance() throws Exception {
        RuntimePublishProperties properties = new RuntimePublishProperties();
        properties.setAckIdentities(List.of(
            "tenant-a|pod-a|ack-secret-a",
            "tenant-a|pod-b|ack-secret-b"));
        RuntimeConfigAckAuthInterceptor interceptor = new RuntimeConfigAckAuthInterceptor(properties);
        MockHttpServletRequest request = request("ack-secret-b",
            OpenApiProtocol.RUNTIME_CONFIG_ACK_TOKEN_HEADER);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("tenant-a", TenantContext.get());
        assertEquals("pod-b", request.getAttribute(
            RuntimeConfigAckAuthInterceptor.AUTHENTICATED_INSTANCE_ATTRIBUTE));
    }

    @Test
    void generalOpenApiTokenMustNotAuthorizeRuntimeAck() throws Exception {
        RuntimePublishProperties properties = new RuntimePublishProperties();
        properties.setAckIdentities(List.of("tenant-a|pod-a|ack-secret-a"));
        RuntimeConfigAckAuthInterceptor interceptor = new RuntimeConfigAckAuthInterceptor(properties);
        MockHttpServletRequest request = request("tenant-open-api-token", OpenApiProtocol.TOKEN_HEADER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("RUNTIME-ACK-AUTH-FAIL"));
        assertFalse(TenantContext.isPresent());
    }

    @Test
    void duplicateTokenMappingMustFailClosed() throws Exception {
        RuntimePublishProperties properties = new RuntimePublishProperties();
        properties.setAckIdentities(List.of(
            "tenant-a|pod-a|same-secret",
            "tenant-b|pod-b|same-secret"));
        RuntimeConfigAckAuthInterceptor interceptor = new RuntimeConfigAckAuthInterceptor(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
            request("same-secret", OpenApiProtocol.RUNTIME_CONFIG_ACK_TOKEN_HEADER),
            response, new Object()));
        assertEquals(401, response.getStatus());
    }

    private MockHttpServletRequest request(String token, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", OpenApiWebConfig.RUNTIME_CONFIG_ACK_PATH);
        request.addHeader(header, token);
        return request;
    }
}
