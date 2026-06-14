package com.richard.fyoung.customerwork.autoconfigure;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
}
