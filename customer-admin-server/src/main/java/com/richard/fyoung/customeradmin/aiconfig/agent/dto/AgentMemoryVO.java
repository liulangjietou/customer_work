package com.richard.fyoung.customeradmin.aiconfig.agent.dto;

/**
 * 智能体长期记忆查看结果。{@code exists=false} 表示 MEMORY.md 尚未生成（记忆能力刚开启或已被清空），
 * 此时 {@code content} 为空串、{@code updateTime} 为 null。
 * @author owlzhangfq@gmail.com
 */
public record AgentMemoryVO(
    boolean exists,
    String content,
    String updateTime
) {
}
