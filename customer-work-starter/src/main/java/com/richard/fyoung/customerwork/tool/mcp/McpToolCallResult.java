package com.richard.fyoung.customerwork.tool.mcp;

import java.util.List;

/**
 * MCP 单次工具调用结果。{@code success=false} 表示工具本身执行报错（MCP 协议里的
 * {@code isError=true}，即协议调用成功但业务逻辑失败）；连接/超时这类协议层异常不在这里表达，
 * 由 {@link McpClientFactory} 直接往外抛，交调用方按自己的错误体系兜底。
 *
 * <p>{@code images}：MCP 协议里 {@code ImageContent}（base64 图片 + mimeType）与 {@code TextContent}
 * 是不同的内容块类型，呈现方式也不同，故拆开返回而不是混进纯文本。</p>
 *
 * <p>{@code outputLooksBinary}：部分通用型 MCP 工具（如文件系统 server 的 {@code read_file}）不区分
 * 文件是否为二进制，把图片等二进制文件内容原样当文本读出、通过 {@code TextContent} 返回。此时二进制
 * 字节在 MCP 服务端那一步已经损毁、原始字节不可逆丢失，本层无法还原。此标记只是让调用方把「一坨看不懂
 * 的乱码」换成清晰的诊断提示，而不是掩盖问题——损毁的原始文本仍通过 {@code output} 原样透出，不隐藏数据。</p>
 *
 * @param output           文本内容块拼接结果（多块以换行连接）
 * @param errorMessage     工具执行失败时的错误文案
 * @param images           图片内容块
 * @param outputLooksBinary 文本疑似为「二进制被误当文本解码」
 * @author owlzhangfq@gmail.com
 */
public record McpToolCallResult(boolean success, String output, String errorMessage, List<McpImageContent> images,
                                boolean outputLooksBinary) {
}
