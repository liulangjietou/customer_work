package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.user.UserAccountService;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接入层鉴权过滤器装配。
 *
 * <p>以 {@code @Bean} 显式注册用户 / 坐席两个 {@code WebFilter}（而非 {@code @Component}）：一来集中管理
 * 装配与依赖，二来避免 {@code @WebFluxTest} 控制器切片自动扫描到 app 包下的 WebFilter 组件、却因切片未提供
 * 其依赖（如 {@code UserJwtService}）而加载失败——切片测试需要某个过滤器时按需 {@code @Import} 即可。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SecurityWebFilterConfig {

    @Bean
    public UserAuthWebFilter userAuthWebFilter(UserJwtService jwtService,
                                               TenantAccessGuard tenantAccessGuard,
                                               UserAccountService userAccountService) {
        return new UserAuthWebFilter(jwtService, tenantAccessGuard, userAccountService);
    }

    @Bean
    public AgentAuthWebFilter agentAuthWebFilter(CustomerWorkProperties properties,
                                                 TenantAccessGuard tenantAccessGuard) {
        return new AgentAuthWebFilter(properties, tenantAccessGuard);
    }
}
