package com.richard.fyoung.customeradmin.aiconfig.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 新建/编辑请求。{@code mcpType} 仅接受 stdio/sse/http；{@code config} 须为合法 JSON
 * （校验见 {@code McpService}）。
 * @author owlzhangfq@gmail.com
 */
public record McpSaveRequest(
    @NotBlank(message = "mcpName 不能为空") String mcpName,
    @NotBlank(message = "mcpType 不能为空") String mcpType,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "config 不能为空") String config,
    String description,
    List<String> allowedSubjectTypes,
    LocalDateTime secretExpiresAt,
    Integer status) {

    /** 兼容内部旧调用点；新建时服务端仍按 ADMIN_USER 最小权限落库。 */
    public McpSaveRequest(String mcpName, String mcpType, String config, String description, Integer status) {
        this(mcpName, mcpType, config, description, null, null, status);
    }

    /** 兼容已有显式主体策略调用点。 */
    public McpSaveRequest(String mcpName, String mcpType, String config, String description,
                          List<String> allowedSubjectTypes, Integer status) {
        this(mcpName, mcpType, config, description, allowedSubjectTypes, null, status);
    }
}
