package com.richard.fyoung.customeradmin.aiconfig.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 智能体新建/编辑请求。{@code mcpIds}/{@code skillIds} 可选多选；{@code modelId} 必填。
 * @author owlzhangfq@gmail.com
 */
public record AgentSaveRequest(
    @NotBlank(message = "agentName 不能为空") String agentName,
    @NotBlank(message = "agentCode 不能为空") String agentCode,
    @NotNull(message = "modelId 不能为空") Long modelId,
    List<Long> mcpIds,
    List<Long> skillIds,
    String systemPrompt,
    List<String> capabilities,
    String icon,
    Integer status) {
}
