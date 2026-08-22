package com.richard.fyoung.customeradmin.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimeAckIdentity;
import com.richard.fyoung.customerwork.core.constant.OpenApiProtocol;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时配置 ACK 专用鉴权：实例 token 在服务端绑定租户和 instanceId，禁止复用通用 Open API token。
 */
public class RuntimeConfigAckAuthInterceptor implements AsyncHandlerInterceptor {

    public static final String AUTHENTICATED_INSTANCE_ATTRIBUTE =
        RuntimeConfigAckAuthInterceptor.class.getName() + ".instanceId";

    private static final String ERROR_CODE = "RUNTIME-ACK-AUTH-FAIL";
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigAckAuthInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuntimePublishProperties properties;

    public RuntimeConfigAckAuthInterceptor(RuntimePublishProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {
        String token = request.getHeader(OpenApiProtocol.RUNTIME_CONFIG_ACK_TOKEN_HEADER);
        Optional<RuntimeAckIdentity> identity = properties.authenticateAckToken(token);
        if (identity.isEmpty()) {
            log.error("runtime config ACK auth failed, code={}, uri={}",
                ERROR_CODE, request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        TenantContext.set(identity.get().tenantId());
        request.setAttribute(AUTHENTICATED_INSTANCE_ATTRIBUTE, identity.get().instanceId());
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        TenantContext.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ERROR_CODE);
        body.put("message", "runtime config ACK identity missing or mismatch");
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
