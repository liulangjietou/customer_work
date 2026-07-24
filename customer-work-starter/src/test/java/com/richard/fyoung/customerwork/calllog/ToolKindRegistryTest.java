package com.richard.fyoung.customerwork.calllog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工具归类登记表单测：默认 TOOL、MCP/SKILL 登记后归类、幂等覆盖、空值兜底。
 * @author owlzhangfq@gmail.com
 */
class ToolKindRegistryTest {

    @Test
    void classify_unregistered_shouldDefaultToTool() {
        ToolKindRegistry registry = new ToolKindRegistry();
        assertEquals(AgentCallKind.TOOL, registry.classify("queryOrder"));
        assertEquals(AgentCallKind.TOOL, registry.classify(null), "null 工具名兜底 TOOL");
    }

    @Test
    void classify_registered_shouldReturnRegisteredKind() {
        ToolKindRegistry registry = new ToolKindRegistry();
        registry.registerMcpTools(List.of("mcp_weather", "mcp_stock"));
        registry.registerSkillTools(List.of("skill_pdf"));

        assertEquals(AgentCallKind.MCP, registry.classify("mcp_weather"));
        assertEquals(AgentCallKind.MCP, registry.classify("mcp_stock"));
        assertEquals(AgentCallKind.SKILL, registry.classify("skill_pdf"));
        assertEquals(AgentCallKind.TOOL, registry.classify("queryOrder"), "未登记仍为 TOOL");
        assertEquals(3, registry.size());
    }

    @Test
    void register_shouldIgnoreBlankAndBeIdempotent() {
        ToolKindRegistry registry = new ToolKindRegistry();
        registry.registerMcpTools(java.util.Arrays.asList("mcp_a", null, " ", "mcp_a"));
        assertEquals(1, registry.size(), "空白/重复不新增条目");
        assertEquals(AgentCallKind.MCP, registry.classify("mcp_a"));
    }
}
