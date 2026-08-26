package com.richard.fyoung.customeradmin.workspace.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 为所有后台 Agent 状态读写入口注册可信主体，不与主体配额开关绑定。 */
@Configuration
public class AdminAgentIdentityWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAgentIdentityInterceptor())
            .addPathPatterns(
                "/api/workspace/**",
                "/api/aiconfig/agent-task/**",
                "/api/aiconfig/agent/*/memory")
            // 先建立身份，再由后续配额拦截器在真正调模型的路径上判定与记账。
            .order(Ordered.LOWEST_PRECEDENCE - 20);
    }
}
