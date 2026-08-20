package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import java.time.LocalDateTime;

/**
 * MCP 连通性测试结果。
 *
 * @param testStatus 取值见 {@link com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus}
 * @param testTime   本次测试时间
 * @param message    失败原因（成功时为 null）
 * @author owlzhangfq@gmail.com
 */
public record McpTestResult(int testStatus, LocalDateTime testTime, String message) {
}
