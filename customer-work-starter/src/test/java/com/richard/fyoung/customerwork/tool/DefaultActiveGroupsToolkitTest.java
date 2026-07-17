package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockMemberBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.MockProductBackend;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁死 {@link DefaultActiveGroupsToolkit} 的"空激活组回退默认"语义。
 *
 * <p>回归背景：AgentScope 2.0.0 GA 在配置 AgentStateStore 时，会用新会话的空 activatedGroups
 * 全量覆盖 Toolkit 激活组并按空集合解析工具面，导致业务工具组整体丢失（模型侧表现为
 * "没有订单查询工具"）。若框架升级后本测试失败，说明该兜底语义被破坏，需重新评估。</p>
 * @author owlzhangfq@gmail.com
 */
class DefaultActiveGroupsToolkitTest {

    private static final String TOOL_QUERY_ORDER = "queryOrder";
    private static final String TOOL_SEARCH_KNOWLEDGE = "searchKnowledge";

    private DefaultActiveGroupsToolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new DefaultActiveGroupsToolkit();
        ToolRegistrar registrar = new ToolRegistrar(new MockOrderBackend(), new MockAfterSalesBackend(),
            new MockKnowledgeBackend(), new MockProductBackend(), new MockMemberBackend(),
            new MockComplaintBackend(), null, null, null);
        registrar.registerBusinessTools(toolkit);
    }

    private Set<String> names(List<ToolSchema> schemas) {
        return schemas.stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    @Test
    void emptyActiveGroups_shouldFallBackToDefaultGroups() {
        // 模拟框架推理路径：新会话状态的 activatedGroups 为空
        Set<String> tools = names(toolkit.getToolSchemas(List.of()));
        assertTrue(tools.contains(TOOL_QUERY_ORDER), "空激活组应回退默认组，订单工具必须可见");
        assertTrue(tools.contains(TOOL_SEARCH_KNOWLEDGE), "知识库工具必须可见");
    }

    @Test
    void emptySetActiveGroups_shouldNotWipeDefaults() {
        // 模拟框架会话槽激活路径：用空状态覆盖激活组
        toolkit.setActiveGroups(List.of());
        Set<String> tools = names(toolkit.getToolSchemas());
        assertTrue(tools.contains(TOOL_QUERY_ORDER), "空覆盖应被忽略，默认激活组保留");
    }

    @Test
    void explicitActiveGroups_shouldStillBeRespected() {
        // 会话真实持久化过非空激活组时，仍按会话集合裁剪工具面
        Set<String> tools = names(toolkit.getToolSchemas(List.of(ToolRegistrar.GROUP_ORDER)));
        assertTrue(tools.contains(TOOL_QUERY_ORDER), "显式激活的订单组工具可见");
        assertFalse(tools.contains(TOOL_SEARCH_KNOWLEDGE), "未激活的知识库组工具不可见");
    }

    @Test
    void explicitSetActiveGroups_shouldReplaceDefaults() {
        toolkit.setActiveGroups(List.of(ToolRegistrar.GROUP_ORDER));
        Set<String> tools = names(toolkit.getToolSchemas());
        assertTrue(tools.contains(TOOL_QUERY_ORDER));
        assertFalse(tools.contains(TOOL_SEARCH_KNOWLEDGE), "非空覆盖仍是全量替换语义");
    }
}
