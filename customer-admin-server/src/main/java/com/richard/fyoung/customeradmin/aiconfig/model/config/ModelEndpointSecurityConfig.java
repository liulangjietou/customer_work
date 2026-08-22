package com.richard.fyoung.customeradmin.aiconfig.model.config;

import com.richard.fyoung.customeradmin.config.AdminModelEgressProperties;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 模型保存校验与真实探测共同使用的端点策略装配。 */
@Configuration(proxyBeanMethods = false)
public class ModelEndpointSecurityConfig {

    @Bean
    public ModelEndpointPolicy modelEndpointPolicy(AdminModelEgressProperties properties) {
        return new ModelEndpointPolicy(properties::getAllowedHosts);
    }
}
