package com.richard.fyoung.customerwork.tool.mcp.contract;

/**
 * 一条具体的契约变更。
 *
 * @param toolName 涉及的工具名
 * @param type     变更类型
 * @param detail   人可读的细节（字段名、类型前后值等），供运营在页面上直接看懂
 * @author owlzhangfq@gmail.com
 */
public record McpToolChange(String toolName, McpChangeType type, String detail) {

    static McpToolChange of(String toolName, McpChangeType type, String detail) {
        return new McpToolChange(toolName, type, detail);
    }
}
