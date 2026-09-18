package com.richard.fyoung.gittools.web;

import com.richard.fyoung.gittools.config.GitToolsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** MCP 入口鉴权；health 保持公开，便于容器探针检查。 */
public class McpAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] expectedToken;

    public McpAuthenticationFilter(GitToolsProperties properties) {
        this.expectedToken = properties.getServerToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/mcp") && !request.getRequestURI().startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response);
            return;
        }
        byte[] presented = authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, presented)) {
            unauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
