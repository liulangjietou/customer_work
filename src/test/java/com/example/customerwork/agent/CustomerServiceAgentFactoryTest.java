package com.example.customerwork.agent;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Agent 工厂单测：校验工具按业务域分组注册后，全部工具对模型可见可调用
 * （防止"分组导致工具被意外隐藏"的回归）。无需模型 / Spring 上下文。
 */
class CustomerServiceAgentFactoryTest {

    @Test
    void buildToolkit_shouldExposeAllBusinessTools() {
        Model model = mock(Model.class);
        CustomerServiceAgentFactory factory =
            new CustomerServiceAgentFactory(model, new CustomerWorkProperties());

        Toolkit toolkit = factory.buildToolkit();
        Set<String> toolNames = toolkit.getToolNames();

        // 六个业务工具都应注册成功
        assertTrue(toolNames.contains("queryOrder"), "缺少 queryOrder: " + toolNames);
        assertTrue(toolNames.contains("queryLogistics"), "缺少 queryLogistics: " + toolNames);
        assertTrue(toolNames.contains("searchKnowledge"), "缺少 searchKnowledge: " + toolNames);
        assertTrue(toolNames.contains("checkRefundEligibility"), "缺少 checkRefundEligibility: " + toolNames);
        assertTrue(toolNames.contains("submitRefund"), "缺少 submitRefund: " + toolNames);
        assertTrue(toolNames.contains("transferToHuman"), "缺少 transferToHuman: " + toolNames);

        // 分组为 active，工具 Schema 应实际暴露给模型
        assertTrue(toolkit.getToolSchemas().size() >= 6,
            "暴露给模型的工具 Schema 数量异常: " + toolkit.getToolSchemas().size());
    }
}
