package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** 生产启动门禁：启用 MCP 不得继续携带数据库内联凭据。 */
@Component
@Profile("prod")
public class McpCredentialProductionValidator implements ApplicationRunner {

    private final AiMcpMapper mcpMapper;
    private final McpCredentialService credentialService;

    public McpCredentialProductionValidator(AiMcpMapper mcpMapper,
                                            McpCredentialService credentialService) {
        this.mcpMapper = mcpMapper;
        this.credentialService = credentialService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> legacyIds = mcpMapper.selectAllEnabledForProductionValidation().stream()
            .filter(credentialService::hasLegacyInlineSecrets)
            .map(AiMcp::getId)
            .toList();
        if (!legacyIds.isEmpty()) {
            throw new IllegalStateException(
                "admin production readiness validation failed, enabled MCP credentials must use SecretRef, ids="
                    + legacyIds);
        }
    }
}
