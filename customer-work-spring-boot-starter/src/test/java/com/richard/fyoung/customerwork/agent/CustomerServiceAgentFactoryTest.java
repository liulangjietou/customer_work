package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.memory.FactLog;
import com.richard.fyoung.customerwork.memory.LongTermMemoryProvider;
import com.richard.fyoung.customerwork.memory.LongTermMemoryStore;
import com.richard.fyoung.customerwork.rag.KnowledgeProvider;
import com.richard.fyoung.customerwork.tool.HigressToolkitConfigurer;
import com.richard.fyoung.customerwork.tool.McpToolkitConfigurer;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Agent 工厂单测：工具分组注册完整性、Meta-Tool 开关、租户解析。无需 Spring 上下文。
 * @author owlzhangfq@gmail.com
 */
class CustomerServiceAgentFactoryTest {

    private final Model model = mock(Model.class);
    private final LongTermMemoryStore store = new LongTermMemoryStore();

    private CustomerServiceAgentFactory factory(CustomerWorkProperties props) {
        FactLog factLog = new FactLog(false, Path.of("target/test-facts"));
        return new CustomerServiceAgentFactory(
            model, props,
            new LongTermMemoryProvider(props, store, factLog),
            new KnowledgeProvider(props),
            new McpToolkitConfigurer(props),
            new HigressToolkitConfigurer(props),
            new com.richard.fyoung.customerwork.tool.ToolRegistrar(
                new com.richard.fyoung.customerwork.tool.backend.MockOrderBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockProductBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockMemberBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend(),
                new com.richard.fyoung.customerwork.approval.PendingApprovalService(),
                new com.richard.fyoung.customerwork.handoff.HandoffService(),
                null),
            new InMemoryAgentStateStore(),
            new com.richard.fyoung.customerwork.config.PermissionConfig().permissionContextState(props),
            new com.richard.fyoung.customerwork.config.NacosPromptService(props),
            new com.richard.fyoung.customerwork.support.TenantResolver(props),
            null,    // 无可插拔 Hook
            null);   // 无 MeterRegistry（观测降级为仅日志）
    }

    @Test
    void buildToolkit_shouldExposeAllBusinessTools() {
        Toolkit toolkit = factory(new CustomerWorkProperties()).buildToolkit();
        Set<String> toolNames = toolkit.getToolNames();

        assertTrue(toolNames.contains("queryOrder"), "缺少 queryOrder: " + toolNames);
        assertTrue(toolNames.contains("queryLogistics"), "缺少 queryLogistics: " + toolNames);
        assertTrue(toolNames.contains("searchKnowledge"), "缺少 searchKnowledge: " + toolNames);
        assertTrue(toolNames.contains("checkRefundEligibility"), "缺少 checkRefundEligibility: " + toolNames);
        assertTrue(toolNames.contains("submitRefund"), "缺少 submitRefund: " + toolNames);
        assertTrue(toolNames.contains("transferToHuman"), "缺少 transferToHuman: " + toolNames);

        assertTrue(toolkit.getToolSchemas().size() >= 6,
            "暴露给模型的工具 Schema 数量异常: " + toolkit.getToolSchemas().size());
    }

    @Test
    void buildToolkit_metaTool_shouldAddExtraToolsWhenEnabled() {
        CustomerWorkProperties off = new CustomerWorkProperties();
        off.getAgent().setMetaToolEnabled(false);
        int withoutMeta = factory(off).buildToolkit().getToolNames().size();

        CustomerWorkProperties on = new CustomerWorkProperties();
        on.getAgent().setMetaToolEnabled(true);
        int withMeta = factory(on).buildToolkit().getToolNames().size();

        assertTrue(withMeta > withoutMeta,
            "开启 Meta-Tool 后应注册额外的元工具，with=" + withMeta + " without=" + withoutMeta);
    }

    @Test
    void resolveTenant_shouldSplitOnDelimiter() {
        CustomerServiceAgentFactory f = factory(new CustomerWorkProperties());
        assertEquals("tenantA", f.resolveTenant("tenantA:conv-1"));
        assertEquals("tenantA", f.resolveTenant("tenantA:conv-2"));
        assertEquals("u1001", f.resolveTenant("u1001"));
        assertEquals("default", f.resolveTenant(""));
    }
}
