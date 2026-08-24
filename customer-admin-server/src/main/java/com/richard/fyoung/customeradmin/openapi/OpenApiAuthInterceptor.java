package com.richard.fyoung.customeradmin.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.core.constant.OpenApiProtocol;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API 统一鉴权拦截器：校验请求头 {@code X-Open-Api-Token} 是否等于配置 {@code admin.open-api.token}。
 *
 * <p>校验失败（token 未配置 / 请求头缺失 / 不匹配）一律 401 + 错误码 {@code OPEN-API-AUTH-FAIL}。
 * 这是开放链路（无后台登录态）的唯一一处鉴权兜底（fast fail 单点防御），下游端点不再重复校验 token。
 * 参考内网工作台 {@code WorkbenchAgentController} 的 X-Workbench-Token 自校验范式，做成拦截器复用。</p>
 * @author owlzhangfq@gmail.com
 */
public class OpenApiAuthInterceptor implements AsyncHandlerInterceptor {

    /** 开放 API 令牌请求头名。 */

    private static final String ERROR_CODE = "OPEN-API-AUTH-FAIL";
    private static final Logger log = LoggerFactory.getLogger(OpenApiAuthInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenApiProperties properties;
    private final AdminTenantProperties tenantProperties;

    public OpenApiAuthInterceptor(OpenApiProperties properties, AdminTenantProperties tenantProperties) {
        this.properties = properties;
        this.tenantProperties = tenantProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        QuotaSubjectContext.clear();
        AgentInvocationIdentityContext.clear();
        String actual = request.getHeader(OpenApiProtocol.TOKEN_HEADER);
        String tenantId = authenticate(actual);
        if (tenantId == null) {
            log.error("open api auth failed, code={}, uri={}", ERROR_CODE, request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        if (tenantProperties.isEnabled()) {
            TenantContext.set(tenantId);
        }
        QuotaSubject subject = QuotaSubject.apiKey(actual);
        QuotaSubjectContext.set(subject);
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(
            tenantId, subject.type(), subject.id(), true)
            .withChannel(AgentInvocationIdentity.CHANNEL_API));
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        TenantContext.clear();
        QuotaSubjectContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
        QuotaSubjectContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    private String authenticate(String actual) {
        if (!StringUtils.hasText(actual)) {
            return null;
        }
        if (!tenantProperties.isEnabled()) {
            return constantTimeEquals(actual, properties.getToken()) ? TenantContext.DEFAULT : null;
        }
        String matchedTenant = null;
        for (Map.Entry<String, String> entry : properties.getTenantTokens().entrySet()) {
            if (constantTimeEquals(actual, entry.getKey())
                && TenantContext.isValidTenantId(entry.getValue())) {
                matchedTenant = entry.getValue();
            }
        }
        return matchedTenant;
    }

    private boolean constantTimeEquals(String actual, String expected) {
        return StringUtils.hasText(expected) && MessageDigest.isEqual(
            actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ERROR_CODE);
        body.put("message", "open api token missing or mismatch");
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
