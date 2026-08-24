package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    private SecretMetadataVO credential;
    private String description;
    private Integer status;
    private Integer testStatus;
    private LocalDateTime testTime;
    private List<String> allowedSubjectTypes;
    private LocalDateTime createTime;
}
