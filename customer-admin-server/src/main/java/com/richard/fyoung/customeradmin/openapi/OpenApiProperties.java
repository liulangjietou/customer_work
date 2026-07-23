package com.richard.fyoung.customeradmin.openapi;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 开放 API 配置：{@code admin.open-api.*}。
 *
 * <p>{@code token} 无默认值（env {@code ADMIN_OPEN_API_TOKEN} 注入），供 customer-channel 等外部模块
 * 通过请求头 {@code X-Open-Api-Token} 调用 {@code /api/open/**}。未配置时所有开放 API 一律 401
 * （见 {@link OpenApiAuthInterceptor}），不给"空 token 放行"的后门。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.open-api")
public class OpenApiProperties {

    /** 开放 API 访问令牌，无默认值（由 env ADMIN_OPEN_API_TOKEN 注入）。 */
    private String token;
}
