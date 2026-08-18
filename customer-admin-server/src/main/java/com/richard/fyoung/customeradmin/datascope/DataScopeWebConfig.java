package com.richard.fyoung.customeradmin.datascope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册数据范围上下文拦截器。
 *
 * <p>排在租户拦截器之后（{@code LOWEST_PRECEDENCE}，注册顺序决定同优先级下的先后）：
 * 解析角色要查 {@code sys_role}，而该表本身受租户过滤。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "admin.data-scope", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataScopeWebConfig implements WebMvcConfigurer {

    private final DataScopeResolver resolver;

    public DataScopeWebConfig(DataScopeResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DataScopeInterceptor(resolver))
            .addPathPatterns("/**")
            .order(Ordered.LOWEST_PRECEDENCE);
    }
}
