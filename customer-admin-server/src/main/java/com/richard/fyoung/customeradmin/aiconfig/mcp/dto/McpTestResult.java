package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import java.time.LocalDateTime;

/**
 * MCP 连通性测试结果。
 *
 * @param testStatus 0未测试 / 1成功 / 2失败
 * @param testTime   本次测试时间
 * @param message    失败原因（成功时为 null）
 * @author owlzhangfq@gmail.com
 */
public record McpTestResult(int testStatus, LocalDateTime testTime, String message) {

    public static final int STATUS_UNTESTED = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
}
