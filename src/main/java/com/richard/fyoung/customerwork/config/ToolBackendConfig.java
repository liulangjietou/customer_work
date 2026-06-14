package com.richard.fyoung.customerwork.config;

import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务工具后端的默认实现装配（扩展点核心）。
 *
 * <p>用 {@code @ConditionalOnMissingBean} 注册默认 Mock 后端：使用者只要在自己的工程里声明同类型的
 * {@code @Bean} / {@code @Component}（调真实订单 / 售后 / 知识系统），这里的默认实现就自动让位——
 * 实现"开箱即用 + 零改框架即可接入自有业务"。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ToolBackendConfig {

    @Bean
    @ConditionalOnMissingBean(OrderBackend.class)
    public OrderBackend orderBackend() {
        return new MockOrderBackend();
    }

    @Bean
    @ConditionalOnMissingBean(AfterSalesBackend.class)
    public AfterSalesBackend afterSalesBackend() {
        return new MockAfterSalesBackend();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeBackend.class)
    public KnowledgeBackend knowledgeBackend() {
        return new MockKnowledgeBackend();
    }
}
