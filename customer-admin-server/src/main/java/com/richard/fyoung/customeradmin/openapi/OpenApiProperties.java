package com.richard.fyoung.customeradmin.openapi;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API 配置：{@code admin.open-api.*}。
 *
 * <p>单租户使用 {@code token}（env {@code ADMIN_OPEN_API_TOKEN}），多租户只接受
 * {@code tenantTokens} 的 token→tenant 映射。两者都通过请求头 {@code X-Open-Api-Token} 调用
 * {@code /api/open/**}；缺失有效配置时所有开放 API 一律 401（见 {@link OpenApiAuthInterceptor}），
 * 不给“空 token 放行”的后门。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.open-api")
public class OpenApiProperties {

    /** 开放 API 访问令牌，无默认值（由 env ADMIN_OPEN_API_TOKEN 注入）。 */
    private String token;

    /**
     * 多租户开放凭据，Key 为令牌、Value 为不可伪造的租户身份。
     * 多租户开启后只接受本映射，旧的全局 token 不再生效。
     */
    private Map<String, String> tenantTokens = new LinkedHashMap<>();
}
