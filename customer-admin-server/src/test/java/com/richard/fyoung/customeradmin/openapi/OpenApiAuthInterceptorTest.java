package com.richard.fyoung.customeradmin.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenApiAuthInterceptor} 单测：token 未配置 / 缺失 / 不匹配一律 401，匹配放行。
 * @author owlzhangfq@gmail.com
 */
class OpenApiAuthInterceptorTest {

    private OpenApiAuthInterceptor interceptor(String configuredToken) {
        OpenApiProperties props = new OpenApiProperties();
        props.setToken(configuredToken);
        return new OpenApiAuthInterceptor(props);
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/open/channel/robots");
        if (token != null) {
            req.addHeader(OpenApiAuthInterceptor.HEADER_TOKEN, token);
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
    }
}
