package com.richard.fyoung.customeradmin.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册租户上下文拦截器（仅 {@code admin.tenant.enabled=true} 时生效）。
 *
 * <p>排在最后执行：Sa-Token 的鉴权拦截器先跑完，未登录请求已被拦下，
 * 到这里剩下的要么带着登录态、要么是放行的公开接口。</p>
 *
 * <p>缺省不装配，必须与 {@link AdminTenantProperties#isEnabled()} 的 Java 默认值一致：
 * 若这里缺省不装、而持久层按 Java 默认值装了 SQL 拦截器，每个请求都会缺租户上下文而 fail-closed。
 * "默认开启"由 {@code application.yml} 表达，不写进这两处的缺省值。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "admin.tenant", name = "enabled", havingValue = "true")
public class TenantWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantContextInterceptor())
            .addPathPatterns("/**")
            .order(Ordered.LOWEST_PRECEDENCE);
    }
}
