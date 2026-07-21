package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customerwork.tool.devtool.DevToolboxTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开发者工具箱系统工具装配。
 *
 * <p>admin-server 排除了 starter 的自动装配（{@code spring.autoconfigure.exclude}），需要 starter 能力时
 * 在自己的 {@code @Configuration} 里显式 new（沿用 {@code CustomerWorkClientConfig} 等惯例），不假设容器里
 * 已有现成 Bean。</p>
 *
 * <p>Bean 名精确等于 {@code ai_system_tool.tool_code}（"devtoolbox"）：运行时
 * {@code AdminAgentInstanceFactory#buildSystemTools} 通过 {@code applicationContext.getBean("devtoolbox")}
 * 按名取本 Bean 注册进智能体的 Toolkit，故 Bean 名不可改。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class DevToolboxConfig {

    /** 开发者工具箱工具 Bean（名称 = tool_code "devtoolbox"，纯本地计算无外部依赖，直接 new）。 */
    @Bean(name = "devtoolbox")
    public DevToolboxTools devtoolbox() {
        return new DevToolboxTools();
    }
}
