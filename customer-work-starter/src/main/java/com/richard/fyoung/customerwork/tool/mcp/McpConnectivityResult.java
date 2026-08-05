package com.richard.fyoung.customerwork.tool.mcp;

import java.time.LocalDateTime;

/**
 * MCP 连通性探测结果。只表达「这次探测成没成、什么时候探的、失败原因是什么」，
 * 不带任何业务侧的状态码语义——调用方（如后台管理的 MCP 列表页）自行映射成自己的枚举值。
 *
 * @param success     是否连通（能完成握手并列出工具）
 * @param testedAt    本次探测时间
 * @param errorMessage 失败原因（成功时为 null；过长会被截断）
 * @author owlzhangfq@gmail.com
 */
public record McpConnectivityResult(boolean success, LocalDateTime testedAt, String errorMessage) {
}
