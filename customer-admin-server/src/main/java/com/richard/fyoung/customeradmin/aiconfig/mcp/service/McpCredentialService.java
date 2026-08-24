package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * MCP 非敏感连接配置与 SecretRef 材料的唯一编排边界。
 * 数据库 config 只保存不可执行占位符；明文只在连接构建的局部变量中短暂存在。
 */
@Service
public class McpCredentialService {

    private final SecretRefService secretRefService;
    private final McpConfigProtector protector = new McpConfigProtector(new ObjectMapper());

    public McpCredentialService(SecretRefService secretRefService) {
        this.secretRefService = secretRefService;
    }

    public StoredConfig create(String tenantId, String mcpName, String mcpType,
                               String submittedConfig, LocalDateTime expiresAt) {
        String normalized = protector.prepareForCreate(mcpType, submittedConfig);
        McpConfigProtector.SecretExtraction extraction = protector.extractSecrets(normalized);
        if (!extraction.hasSecrets()) {
            return new StoredConfig(extraction.protectedConfig(), null);
        }
        SecretRefService.SecretWriteResult secret = secretRefService.createLocal(
            tenantId, "mcp", mcpName + " 凭据", extraction.secretBundleJson(), expiresAt);
        return new StoredConfig(extraction.protectedConfig(), secret.refId());
    }

    public StoredConfig update(AiMcp current, String submittedType, String submittedConfig,
                               LocalDateTime expiresAt) {
        String currentPlaintext = resolve(current);
        String merged = protector.prepareForUpdate(
            current.getMcpType(), currentPlaintext, submittedType, submittedConfig);
        McpConfigProtector.SecretExtraction extraction = protector.extractSecrets(merged);
        Long nextRefId = current.getSecretRefId();
        if (extraction.hasSecrets()) {
            SecretRefService.SecretWriteResult secret = nextRefId == null
                ? secretRefService.createLocal(current.getTenantId(), "mcp",
                    current.getMcpName() + " 凭据", extraction.secretBundleJson(), expiresAt)
                : secretRefService.rotateLocal(nextRefId, current.getTenantId(),
                    extraction.secretBundleJson(), expiresAt);
            nextRefId = secret.refId();
        } else if (nextRefId != null) {
            secretRefService.revoke(nextRefId, current.getTenantId());
            nextRefId = null;
        }
        return new StoredConfig(extraction.protectedConfig(), nextRefId);
    }

    public String resolve(AiMcp mcp) {
        if (mcp.getSecretRefId() == null) {
            return mcp.getConfig();
        }
        if (!StringUtils.hasText(mcp.getTenantId())) {
            throw new IllegalStateException("MCP SecretRef tenant is missing");
        }
        String material = secretRefService.resolvePlaintext(mcp.getSecretRefId(), mcp.getTenantId());
        return protector.restoreSecrets(mcp.getConfig(), material);
    }

    public String redactForDetail(AiMcp mcp) {
        return protector.redactForDetail(mcp.getMcpType(), mcp.getConfig());
    }

    public SecretMetadataVO metadata(AiMcp mcp) {
        return secretRefService.metadata(mcp.getSecretRefId(), mcp.getTenantId());
    }

    public boolean hasLegacyInlineSecrets(AiMcp mcp) {
        return mcp.getSecretRefId() == null && protector.containsInlineSecrets(mcp.getConfig());
    }

    public record StoredConfig(String protectedConfig, Long secretRefId) {
    }
}
