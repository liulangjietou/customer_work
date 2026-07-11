package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import java.util.List;

/**
 * MCP 调试面板 · 单次工具调用结果。{@code success=false} 既可能是协议层调用失败（连接/超时异常），
 * 也可能是工具本身执行报错（MCP 协议里的 {@code isError=true}，调用本身成功但业务逻辑失败），
 * 两种情况前端都统一展示 {@code errorMessage}，不强行区分。
 *
 * <p>{@code images}：MCP 协议里 {@code ImageContent}（base64 图片数据 + mimeType）与
 * {@code TextContent} 是不同的内容块类型，前端渲染方式也不同（前者应作为图片展示，不能当纯文本塞进
 * 文本框——否则不可见）。</p>
 *
 * <p>{@code outputLooksBinary}：另一类不同的问题——部分通用型 MCP 工具（如文件系统 server 的
 * {@code read_file}）不区分文件是否为二进制，把图片等二进制文件内容原样当 UTF-8/Latin-1 文本读出并
 * 通过 {@code TextContent} 返回。此时二进制字节在 MCP 服务端那一步已经损毁、原始字节不可逆丢失，
 * 我们收到的就已经是乱码，无法在这一侧还原。此标记只是让前端把"一坨看不懂的乱码"换成清晰的诊断提示，
 * 而不是掩盖问题——原始（已损毁的）文本仍然通过 {@code output} 原样透出，不隐藏数据。</p>
 * @author owlzhangfq@gmail.com
 */
public record McpDebugCallResult(boolean success, String output, String errorMessage, List<McpDebugImageVO> images,
                                  boolean outputLooksBinary) {
}
