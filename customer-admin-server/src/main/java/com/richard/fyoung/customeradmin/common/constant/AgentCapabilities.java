package com.richard.fyoung.customeradmin.common.constant;

/**
 * 智能体能力标识（{@code ai_agent.capabilities} 列存的值）。
 *
 * <p>这列存的是逗号分隔的能力码，写入方（智能体配置）、装配方（运行时工厂）、消费方
 * （VibeCoding / Git 助手 / 协同编码 / 菜单聚合）必须认同一套字符串。此前分隔符在 6 个类、
 * {@code vibecoding} 在 4 个类各写一遍——改一个能力码而漏掉某处，表现是"后台勾了能力、
 * 运行时不生效"，两侧都不报错。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class AgentCapabilities {

    /** 能力码分隔符（列里是逗号分隔的字符串，不是 JSON 数组）。 */
    public static final String DELIMITER = ",";

    /** 基础对话能力（所有智能体都有，配置里显式列出便于校验）。 */
    public static final String CHAT = "chat";
    public static final String VIBECODING = "vibecoding";
    public static final String PLAN = "plan";
    public static final String SUBAGENT = "subagent";
    public static final String TASKLIST = "tasklist";
    public static final String SKILL_LEARNING = "skill-learning";
    public static final String DYNAMIC_SUBAGENT = "dynamic-subagent";
    public static final String MEMORY = "memory";

    private AgentCapabilities() {
    }
}
