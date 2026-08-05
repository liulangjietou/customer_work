package com.richard.fyoung.customerwork.tool.mcp;

/**
 * MCP 工具返回的单个图片内容块。{@code data} 是 MCP 协议 {@code ImageContent} 原样的 base64
 * 编码字符串，本层不做任何解码/转码，交由调用方决定如何呈现（如前端拼
 * {@code data:<mimeType>;base64,<data>} 渲染成 img）。
 *
 * @param mimeType 图片 MIME 类型
 * @param data     base64 编码的图片数据
 * @author owlzhangfq@gmail.com
 */
public record McpImageContent(String mimeType, String data) {
}
