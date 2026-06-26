package com.richard.fyoung.customerweb;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.admin.registry.AgentDescriptor;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * customer-web 集成冒烟测试：验证 admin 控制台上下文加载，且本项目客服 Agent 被
 * {@link AgentRegistry} 自动接管（Agent / Model / Toolkit / AgentStateStore Bean 装配齐全）。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest
class CustomerWebIntegrationTest {

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private Agent customerServiceAgent;

    @Autowired
    private Model chatModel;

    @Autowired
    private Toolkit customerToolkit;

    @Autowired
    private AgentStateStore agentStateStore;

    @Test
    void contextLoads_andCoreBeansWired() {
        assertNotNull(customerServiceAgent, "客服 Agent Bean 应装配");
        assertNotNull(chatModel, "模型 Bean 应装配");
        assertNotNull(agentStateStore, "状态存储 Bean 应装配");
        assertTrue(customerToolkit.getToolNames().contains("queryOrder"),
            "工具集应含业务工具: " + customerToolkit.getToolNames());
    }

    @Test
    void agent_shouldBeRegisteredInAdminRegistry() {
        assertTrue(agentRegistry.size() >= 1, "AgentRegistry 应至少接管一个 Agent");

        List<String> names = agentRegistry.list().stream()
            .map(a -> AgentDescriptor.of(a).name())
            .toList();
        assertTrue(names.contains("CustomerServiceAgent"),
            "admin 控制台应接管本项目客服 Agent: " + names);
    }
}
