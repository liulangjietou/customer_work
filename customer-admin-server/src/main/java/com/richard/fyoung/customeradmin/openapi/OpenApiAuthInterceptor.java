package com.richard.fyoung.customeradmin.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
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
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    /** 开放 API 令牌请求头名。 */
    public static final String HEADER_TOKEN = "X-Open-Api-Token";

    private static final String ERROR_CODE = "OPEN-API-AUTH-FAIL";
    private static final Logger log = LoggerFactory.getLogger(OpenApiAuthInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenApiProperties properties;

    public OpenApiAuthInterceptor(OpenApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String expected = properties.getToken();
        String actual = request.getHeader(HEADER_TOKEN);
        if (!StringUtils.hasText(expected) || !expected.equals(actual)) {
            log.error("open api auth failed, code={}, uri={}", ERROR_CODE, request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        return true;
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
