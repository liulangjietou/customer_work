package com.richard.fyoung.customerchannel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * customer-channel 启动类（AgentScope 2.0 配套前端 · 客服 Agent 管理控制台）。
 *
 * <p>本模块是基于官方 {@code agentscope-admin-spring-boot-starter} 的 <b>Spring MVC</b> 管理控制台：
 * 复用 customer-work 的客服 Agent 能力（模型层 / 工具 / 权限 / 中间件 / 状态存储），把 Agent 暴露为
 * Spring Bean 后由 admin 自动接管，提供与 ReActAgent 协议对齐的开箱即用 Web 管理界面
 * （会话查看 / 工具与权限巡检 / 用量统计 / 子智能体任务 / 优雅 drain 等，集成 AgentEvent 与 Permission HITL）。</p>
 *
 * <p>与可运行的 WebFlux 对话 API（{@code customer-work-app-server}）按 Web 类型分离：对话走响应式，管理走 servlet。</p>
 * @author owlzhangfq@gmail.com
 */
@SpringBootApplication
public class CustomerChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerChannelApplication.class, args);
    }
}
