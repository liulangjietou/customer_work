package com.richard.fyoung.customerwork.data.calllog;

/**
 * 智能体调用分段类别（分段耗时统计维度）。
 *
 * <ul>
 *   <li>{@link #MODEL}：大模型 API 调用（每次 onModelCall 一段）；</li>
 *   <li>{@link #TOOL}：业务工具执行（默认类别）；</li>
 *   <li>{@link #MCP}：MCP 接入的外部工具；</li>
 *   <li>{@link #SKILL}：Skill 技能工具。</li>
 * </ul>
 *
 * <p>MCP 与 SKILL 在框架里同样以 Tool 形态经 onActing 执行，仅凭执行流无法区分，
 * 由 {@link ToolKindRegistry} 按工具名登记归类，未登记的工具一律落 {@link #TOOL}。</p>
 * @author owlzhangfq@gmail.com
 */
public enum AgentCallKind {
    MODEL, TOOL, MCP, SKILL
}
