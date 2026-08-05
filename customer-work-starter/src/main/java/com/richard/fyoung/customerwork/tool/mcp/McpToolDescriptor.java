package com.richard.fyoung.customerwork.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具描述：{@code inputSchema} 是标准 JSON Schema（通常 object 类型），
 * {@code properties} 每个 value 本身也是一段 JSON Schema 片段
 * （如 {@code {"type":"string","description":"..."}}），调用方据此动态渲染参数表单或做参数校验。
 *
 * @param name        工具名
 * @param description 工具描述
 * @param schemaType  入参 schema 的顶层类型（无 schema 时按 object 兜底）
 * @param properties  入参字段定义
 * @param required    必填字段名
 * @author owlzhangfq@gmail.com
 */
public record McpToolDescriptor(
    String name,
    String description,
    String schemaType,
    Map<String, Object> properties,
    List<String> required) {
}
