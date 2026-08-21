package com.richard.fyoung.customeradmin.openapi;

import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开放 API 拦截器注册：把 {@link OpenApiAuthInterceptor} 挂到 {@code /api/open/**}。
 *
 * <p>{@code /api/open/**} 已在 {@code SaTokenConfig} 的 excludePathPatterns 放行（不走后台登录态），
 * 鉴权收敛到本拦截器的 X-Open-Api-Token 校验，两者互补。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class OpenApiWebConfig implements WebMvcConfigurer {

    /** 开放 API 使用独立机器凭据，不得再被后台 Sa-Token 上下文覆盖。 */
    public static final String PATH_PATTERN = "/api/open/**";

    private final OpenApiProperties properties;
    private final AdminTenantProperties tenantProperties;

    public OpenApiWebConfig(OpenApiProperties properties, AdminTenantProperties tenantProperties) {
        this.properties = properties;
        this.tenantProperties = tenantProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OpenApiAuthInterceptor(properties, tenantProperties))
            .addPathPatterns(PATH_PATTERN);
    }
}
