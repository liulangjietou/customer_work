package com.richard.fyoung.customeradmin.publicdeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 对外开放实例的额外装配：内部工具闸门。
 *
 * <p>{@code @EnableConfigurationProperties} 是 {@link PublicDeploymentProperties} 的唯一注册入口
 * （该类只有 {@code @ConfigurationProperties} 而没有 {@code @Component}），
 * 因此挂在没有条件的外层类上——开关为 false 时属性 Bean 仍要存在，注册与审核链路都要读它。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PublicDeploymentProperties.class)
public class PublicDeploymentWebConfig {

    /**
     * 内部工具拦截器只在对外实例上注册。
     *
     * <p>Order 取 {@link Ordered#HIGHEST_PRECEDENCE}：必须排在 Sa-Token 登录校验之前，
     * 否则免登的 {@code /api/workbench/agent/**} 会先被登录拦截器放行/拒绝，轮不到这里。</p>
     */
    @Configuration
    @ConditionalOnProperty(prefix = "admin.public-deployment", name = "enabled", havingValue = "true")
    static class InternalToolGuardConfig implements WebMvcConfigurer {

        private final ObjectMapper objectMapper;

        InternalToolGuardConfig(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            log.info("public deployment mode enabled, internal tool endpoints are disabled");
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new InternalToolGuardInterceptor(objectMapper))
                .addPathPatterns("/api/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
        }
    }
}
