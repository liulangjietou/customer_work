package com.richard.fyoung.customerworkapp;

import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 应用上下文装配冒烟测试：验证所有 Bean（模型层、会话、记忆、RAG、Skill、MCP、可观测等）
 * 能被 Spring 正确装配，不抛异常。用配置默认 key 离线构建模型 Bean，不发起真实网络调用。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationContextTest {

    @Autowired
    private CustomerServiceService service;

    @Autowired
    private CustomerServiceAgentFactory agentFactory;

    @Test
    void contextLoads_andCanAssembleAgent() {
        assertNotNull(service, "会话服务应被装配");
        assertNotNull(agentFactory, "Agent 工厂应被装配");
        // 真正装配一个 Agent，覆盖 RAG 灌库、Skill 加载、PlanNotebook、长期记忆等全部接线
        assertNotNull(agentFactory.createAgent("smoke:conv-1"), "应能装配出 Agent");
    }
}
