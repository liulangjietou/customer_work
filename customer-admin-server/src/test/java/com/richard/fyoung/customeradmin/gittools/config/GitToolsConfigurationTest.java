package com.richard.fyoung.customeradmin.gittools.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * 装配门控：gittools 默认不启用，启用后端点与鉴权一起装配，启用却漏配时启动即失败。
 * @author owlzhangfq@gmail.com
 */
class GitToolsConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, ValidationAutoConfiguration.class))
        .withUserConfiguration(GitToolsConfiguration.class);

    @Test
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(McpSyncServer.class);
            assertThat(context).doesNotHaveBean(GitToolsProperties.class);
        });
    }

    @Test
    void enabledAssemblesMcpServerAndAuthenticationFilter() {
        runner.withPropertyValues(
                "admin.gittools.enabled=true",
                "admin.gittools.repository=https://github.com/acme/demo",
                "admin.gittools.token=git-token",
                "admin.gittools.server-token=server-token")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(McpSyncServer.class);
                FilterRegistrationBean<?> filter =
                    context.getBean("gitToolsMcpAuthenticationFilter", FilterRegistrationBean.class);
                assertThat(filter.getUrlPatterns())
                    .containsExactlyInAnyOrder(GitToolsConfiguration.MCP_ENDPOINT, GitToolsConfiguration.MCP_ENDPOINT + "/*");
            });
    }

    @Test
    void enabledWithoutServerTokenFailsFast() {
        runner.withPropertyValues(
                "admin.gittools.enabled=true",
                "admin.gittools.repository=https://github.com/acme/demo",
                "admin.gittools.token=git-token")
            .run(context -> assertThat(context).hasFailed());
    }
}
