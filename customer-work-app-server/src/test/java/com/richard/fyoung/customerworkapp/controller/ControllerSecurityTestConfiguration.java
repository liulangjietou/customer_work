package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 控制器切片测试的鉴权过滤器装配，显式选择带依赖的安全构造器。 */
final class ControllerSecurityTestConfiguration {

    private ControllerSecurityTestConfiguration() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UserAuth {

        @Bean
        UserAuthWebFilter userAuthWebFilter(UserJwtService jwtService) {
            return new UserAuthWebFilter(jwtService);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AgentAuth {

        @Bean
        AgentAuthWebFilter agentAuthWebFilter(CustomerWorkProperties properties) {
            return new AgentAuthWebFilter(properties);
        }
    }
}
