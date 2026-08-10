package com.richard.fyoung.customerwork.data.calllog;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具名 → 调用类别登记表（进程级共享，MCP / SKILL 归类的唯一依据）。
 *
 * <p>MCP 工具与 Skill 工具在框架里都以普通 Tool 形态经 onActing 执行，执行流无法区分，故在注册点
 * （{@code McpToolkitConfigurer} 接入 MCP client 时、{@code CustomerServiceAgentFactory#buildSkillBox}
 * 注册 skill 时）顺手把工具名登记进来。业务工具不登记，{@link #classify} 默认返回 {@link AgentCallKind#TOOL}。</p>
 *
 * <p><b>键设计</b>：直接用「工具名」作键。框架里工具名在 Toolkit 内即为唯一标识（onActing 的
 * {@code ToolUseBlock.getName()} 就是它），同名工具在不同 agent 里语义一致、类别相同，故全局单表登记不会
 * 产生"同名异类"冲突；重复登记为幂等覆盖。表随会话不断装配而累积，量级等于工具总数，可忽略。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ToolKindRegistry {

    /** 仅登记 MCP / SKILL；未登记者由 {@link #classify} 兜底为 TOOL。 */
    private final ConcurrentHashMap<String, AgentCallKind> kinds = new ConcurrentHashMap<>();

    /** 批量登记 MCP 工具名。 */
    public void registerMcpTools(Collection<String> toolNames) {
        register(toolNames, AgentCallKind.MCP);
    }

    /** 批量登记 Skill 工具名。 */
    public void registerSkillTools(Collection<String> toolNames) {
        register(toolNames, AgentCallKind.SKILL);
    }

    private void register(Collection<String> toolNames, AgentCallKind kind) {
        if (toolNames == null) {
            return;
        }
        for (String name : toolNames) {
            if (name != null && !name.isBlank()) {
                kinds.put(name, kind);
            }
        }
    }

    /** 按工具名归类：已登记返回登记类别，否则默认 {@link AgentCallKind#TOOL}。 */
    public AgentCallKind classify(String toolName) {
        if (toolName == null) {
            return AgentCallKind.TOOL;
        }
        return kinds.getOrDefault(toolName, AgentCallKind.TOOL);
    }

    /** 当前登记条目数（测试 / 诊断用）。 */
    public int size() {
        return kinds.size();
    }
}
