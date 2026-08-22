package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * MCP 新建/编辑请求。{@code mcpType} 仅接受 stdio/sse/http；{@code config} 须为合法 JSON
 * （校验见 {@code McpService}）。
 * @author owlzhangfq@gmail.com
 */
public record McpSaveRequest(
    @NotBlank(message = "mcpName 不能为空") String mcpName,
    @NotBlank(message = "mcpType 不能为空") String mcpType,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "config 不能为空") String config,
    String description,
    Integer status) {
}
