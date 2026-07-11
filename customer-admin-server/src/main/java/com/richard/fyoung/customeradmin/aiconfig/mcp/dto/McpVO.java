package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class McpVO {
    private Long id;
    private String mcpName;
    private String mcpType;
    private String config;
    private String description;
    private Integer status;
    private Integer testStatus;
    private LocalDateTime testTime;
    private LocalDateTime createTime;
}
