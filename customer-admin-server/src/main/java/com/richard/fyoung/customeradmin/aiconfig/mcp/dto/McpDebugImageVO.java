package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

/**
 * MCP 调试面板 · 单个图片内容块。{@code data} 是 MCP 协议 {@code ImageContent} 原样的 base64
 * 编码字符串，前端直接拼成 {@code data:<mimeType>;base64,<data>} 渲染为 {@code <img>}，
 * 不在服务端做任何解码/转码。
 * @author owlzhangfq@gmail.com
 */
public record McpDebugImageVO(String mimeType, String data) {
}
