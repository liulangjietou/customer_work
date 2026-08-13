package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.FileFactLog;
import com.richard.fyoung.customerwork.core.memory.LongTermMemoryProvider;
import com.richard.fyoung.customerwork.core.memory.InMemoryLongTermMemoryStore;
import com.richard.fyoung.customerwork.core.memory.LongTermMemoryStore;
import com.richard.fyoung.customerwork.data.rag.KnowledgeProvider;
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
    private final LongTermMemoryStore store = new InMemoryLongTermMemoryStore();

    private CustomerServiceAgentFactory factory(CustomerWorkProperties props) {
        FactLog factLog = new FileFactLog(false, Path.of("target/test-facts"));
        return new CustomerServiceAgentFactory(
            model, props,
            new LongTermMemoryProvider(props, store, factLog),
            new KnowledgeProvider(props),
            new McpToolkitConfigurer(props, new com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry()),
            new HigressToolkitConfigurer(props),
            new com.richard.fyoung.customerwork.tool.ToolRegistrar(
                new com.richard.fyoung.customerwork.tool.backend.MockOrderBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockProductBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockMemberBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend(),
                new com.richard.fyoung.customerwork.capability.approval.PendingApprovalService(),
                new com.richard.fyoung.customerwork.capability.handoff.HandoffService(),
                null),
            new InMemoryAgentStateStore(),
            new com.richard.fyoung.customerwork.infra.config.PermissionConfig().permissionContextState(props),
            new com.richard.fyoung.customerwork.infra.config.NacosPromptService(props),
            new com.richard.fyoung.customerwork.core.support.TenantResolver(props),
            new com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry(),
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

    /**
     * 端到端回归（AgentScope 2.0 GA 空状态覆盖激活组问题）：配置状态存储时，新会话首次调用
     * 实际传给模型的工具清单必须包含业务工具。此前框架用 fresh state 的空 activatedGroups
     * 覆盖 Toolkit 激活组，模型只能收到未分组基础工具（表现为"没有订单查询工具"）。
     */
    @Test
    void createAgent_freshSessionWithStateStore_businessToolsShouldReachModel() {
        CustomerServiceAgentFactory f = factory(new CustomerWorkProperties());
        io.agentscope.core.ReActAgent agent = f.createAgent("tenantX:conv-tool-surface");

        java.util.concurrent.atomic.AtomicReference<java.util.List<io.agentscope.core.model.ToolSchema>> captured =
            new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.when(model.getModelName()).thenReturn("capture-mock");
        org.mockito.Mockito.when(model.stream(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                captured.set(inv.getArgument(1));
                // 捕获后即终止推理循环（工具清单在模型调用前已确定，报错不影响断言目标）
                return reactor.core.publisher.Flux.error(new IllegalStateException("capture-only"));
            });

        agent.call("帮我查订单 20260613001", f.contextFor("tenantX:conv-tool-surface"))
            .onErrorResume(e -> {
                System.out.println("[capture-test] call terminated: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
                return reactor.core.publisher.Mono.empty();
            })
            .block(java.time.Duration.ofSeconds(15));

        assertTrue(captured.get() != null && !captured.get().isEmpty(), "模型未收到任何工具 schema");
        Set<String> names = captured.get().stream()
            .map(io.agentscope.core.model.ToolSchema::getName)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("queryOrder"), "新会话下订单工具未到达模型层: " + names);
        assertTrue(names.contains("searchKnowledge"), "知识库工具未到达模型层: " + names);
        assertTrue(names.contains("transferToHuman"), "转人工工具未到达模型层: " + names);
    }
}
