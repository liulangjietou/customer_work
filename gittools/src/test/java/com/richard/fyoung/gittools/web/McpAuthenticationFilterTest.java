package com.richard.fyoung.gittools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.richard.fyoung.gittools.config.GitToolsProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpAuthenticationFilterTest {
    @Test
    void requiresExactBearerTokenForMcpPath() throws Exception {
        GitToolsProperties properties = new GitToolsProperties();
        properties.setServerToken("server-token");
        McpAuthenticationFilter filter = new McpAuthenticationFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());

        request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer server-token");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    @Test
    void leavesHealthEndpointPublic() throws Exception {
        GitToolsProperties properties = new GitToolsProperties();
        properties.setServerToken("server-token");
        McpAuthenticationFilter filter = new McpAuthenticationFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
