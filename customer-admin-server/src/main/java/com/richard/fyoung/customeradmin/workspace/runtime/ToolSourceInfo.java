package com.richard.fyoung.customeradmin.workspace.runtime;

import java.util.Set;

/**
 * 一个智能体实例的工具来源登记表：{@link AdminAgentInstanceFactory#build} 装配 Toolkit 时，
 * 记录哪些工具名来自 Skill、哪些来自 MCP（{@code ToolUseBlock} 本身不带来源信息，{@code Toolkit}
 * 也没有暴露"这个工具是哪来的"的查询能力，只能在装配时用注册前后的工具名集合差分自己记下来）。
 * 既不在 Skill 集合也不在 MCP 集合里的，视为内置/业务工具（比如 order/product 相关工具）。
 * @author owlzhangfq@gmail.com
 */
public record ToolSourceInfo(Set<String> skillToolNames, Set<String> mcpToolNames) {

    public static final ToolSourceInfo EMPTY = new ToolSourceInfo(Set.of(), Set.of());

    public boolean isSkillTool(String toolName) {
        return skillToolNames.contains(toolName);
    }

    public boolean isMcpTool(String toolName) {
        return mcpToolNames.contains(toolName);
    }
}
