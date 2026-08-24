package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpCredentialProductionValidatorTest {

    @Test
    void rejectsEnabledLegacyInlineCredential() {
        AiMcpMapper mapper = mock(AiMcpMapper.class);
        McpCredentialService service = mock(McpCredentialService.class);
        AiMcp mcp = new AiMcp();
        mcp.setId(7L);
        when(mapper.selectAllEnabledForProductionValidation()).thenReturn(List.of(mcp));
        when(service.hasLegacyInlineSecrets(mcp)).thenReturn(true);

        McpCredentialProductionValidator validator =
            new McpCredentialProductionValidator(mapper, service);

        assertThrows(IllegalStateException.class,
            () -> validator.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void acceptsSecretRefAndNonSensitiveConfig() {
        AiMcpMapper mapper = mock(AiMcpMapper.class);
        McpCredentialService service = mock(McpCredentialService.class);
        AiMcp first = new AiMcp();
        AiMcp second = new AiMcp();
        when(mapper.selectAllEnabledForProductionValidation()).thenReturn(List.of(first, second));
        when(service.hasLegacyInlineSecrets(first)).thenReturn(false);
        when(service.hasLegacyInlineSecrets(second)).thenReturn(false);

        McpCredentialProductionValidator validator =
            new McpCredentialProductionValidator(mapper, service);

        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments(new String[0])));
    }
}
