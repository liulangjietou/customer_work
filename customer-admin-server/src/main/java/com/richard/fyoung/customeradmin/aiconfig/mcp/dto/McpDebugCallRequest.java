package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * MCP 调试面板 · 单次工具调用请求。
 * @author owlzhangfq@gmail.com
 */
public record McpDebugCallRequest(
    @NotBlank(message = "toolName 不能为空") String toolName,
    Map<String, Object> arguments) {
}
