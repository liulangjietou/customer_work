package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 登录滑块运维配置必须在启动期失败，而不是把错误推迟到请求期。 */
class LoginCaptchaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_shouldBindSuccessfully() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LoginCaptchaProperties.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "challenge-ttl-seconds",
        "proof-ttl-seconds",
        "max-issue-per-window",
        "max-verify-per-window",
        "max-proof-consume-per-window",
        "rate-limit-window-seconds",
        "max-in-memory-entries",
        "max-user-agent-length"
    })
    void everyNonPositiveOperationalValue_shouldFailStartupValidation(String propertyName) {
        contextRunner
            .withPropertyValues("admin.login-captcha." + propertyName + "=0")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoginCaptchaProperties.class)
    static class TestConfig {
    }
}
