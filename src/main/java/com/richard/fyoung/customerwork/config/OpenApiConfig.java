package com.richard.fyoung.customerwork.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 文档配置。
 *
 * <p>启动后访问：</p>
 * <ul>
 *   <li>Swagger UI：{@code http://localhost:8080/swagger-ui.html}</li>
 *   <li>OpenAPI JSON：{@code http://localhost:8080/v3/api-docs}</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customerWorkOpenAPI() {
        return new OpenAPI().info(new Info()
            .title("AgentScope 智能客服系统 API")
            .description("基于 AgentScope Java 1.0.12 的生产级智能客服：对话 / 流式 / 结构化意图 / "
                + "多 Agent 协作 / AG-UI / 会话中断与持久化。")
            .version("1.0.0")
            .contact(new Contact().name("customer-work").email("owlzhangfq@gmail.com"))
            .license(new License().name("Apache-2.0")));
    }
}
