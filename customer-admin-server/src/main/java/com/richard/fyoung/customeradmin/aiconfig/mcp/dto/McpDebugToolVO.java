package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * MCP 调试面板 · 工具列表项：{@code inputSchema} 是标准 JSON Schema（object 类型），
 * {@code properties} 每个 value 本身也是一段 JSON Schema 片段（如 {"type":"string","description":"..."}），
 * 前端据此动态渲染参数表单。
 * @author owlzhangfq@gmail.com
 */
public record McpDebugToolVO(
    String name,
    String description,
    String schemaType,
    Map<String, Object> properties,
    List<String> required) {
}
