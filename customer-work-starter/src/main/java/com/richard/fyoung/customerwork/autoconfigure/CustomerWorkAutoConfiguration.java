package com.richard.fyoung.customerwork.autoconfigure;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.notification.LoggingNotificationChannel;
import com.richard.fyoung.customerwork.notification.NotificationChannel;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * customer-work starter 的自动配置入口。
 *
 * <p>通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册：下游应用只要把本 starter 加入依赖，<b>无需关心自身基础包、无需手动 {@code @ComponentScan}</b>，
 * 即可自动装配本库的全部能力（模型层 / 记忆 / RAG / 工具 / 会话 / 安全 / 可观测 / Nacos 等）。</p>
 *
 * <p>扫描范围固定为 starter 自身基础包 {@code com.richard.fyoung.customerwork}，与下游应用包名互不影响，
 * 因此不会与下游自己的组件扫描发生重复装配。下游若要覆盖默认实现（如自定义 {@code OrderBackend}），
 * 声明同类型 Bean 即可（默认实现以 {@code @ConditionalOnMissingBean} 让位）。</p>
 * @author owlzhangfq@gmail.com
 */
@AutoConfiguration
@ComponentScan("com.richard.fyoung.customerwork")
@EnableConfigurationProperties(CustomerWorkProperties.class)
@EnableScheduling
public class CustomerWorkAutoConfiguration {

    /**
     * 默认审计落地实现（写专用 logger）。下游声明自己的 {@link AuditSink} Bean 即可覆盖，
     * 把审计轨迹投递到 Kafka / 数据库 / SIEM。
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditSink auditSink() {
        return new LoggingAuditSink();
    }

    /**
     * 默认主动通知通道（仅日志）。下游声明自己的 {@link NotificationChannel} Bean 即可覆盖，
     * 复用飞书 / 钉钉等 Channel 推送把订单状态通知 / 满意度回访推达用户。
     */
    @Bean
    @ConditionalOnMissingBean
    public NotificationChannel notificationChannel() {
        return new LoggingNotificationChannel();
    }
}
