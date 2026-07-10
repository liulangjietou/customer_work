package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

/**
 * MCP 调试面板 · 单次工具调用结果。{@code success=false} 既可能是协议层调用失败（连接/超时异常），
 * 也可能是工具本身执行报错（MCP 协议里的 {@code isError=true}，调用本身成功但业务逻辑失败），
 * 两种情况前端都统一展示 {@code errorMessage}，不强行区分。
 * @author owlzhangfq@gmail.com
 */
public record McpDebugCallResult(boolean success, String output, String errorMessage) {
}
