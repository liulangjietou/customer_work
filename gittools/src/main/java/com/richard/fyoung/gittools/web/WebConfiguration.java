package com.richard.fyoung.gittools.web;

import com.richard.fyoung.gittools.config.GitToolsProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfiguration {
    @Bean
    public FilterRegistrationBean<McpAuthenticationFilter> mcpAuthenticationFilter(GitToolsProperties properties) {
        FilterRegistrationBean<McpAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpAuthenticationFilter(properties));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(1);
        return registration;
    }
}
