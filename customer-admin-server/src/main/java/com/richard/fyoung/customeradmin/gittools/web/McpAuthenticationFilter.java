package com.richard.fyoung.customeradmin.gittools.web;

import static com.richard.fyoung.customerwork.core.constant.HttpAuthConstants.BEARER_PREFIX;

import com.richard.fyoung.customeradmin.gittools.config.GitToolsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * gittools MCP 端点鉴权：常量时间比较专用 Bearer Token。
 *
 * <p>作用路径由 {@code GitToolsConfiguration} 的 FilterRegistrationBean 限定，本类不再自行判断路径。</p>
 * @author owlzhangfq@gmail.com
 */
public class McpAuthenticationFilter extends OncePerRequestFilter {

    private final byte[] expectedToken;

    public McpAuthenticationFilter(GitToolsProperties properties) {
        this.expectedToken = properties.getServerToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null
            || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            unauthorized(response);
            return;
        }
        byte[] presented = authorization.substring(BEARER_PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, presented)) {
            unauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
