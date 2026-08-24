package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpCredentialServiceTest {

    private final SecretRefService secretRefService = mock(SecretRefService.class);
    private final McpCredentialService service = new McpCredentialService(secretRefService);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void create_shouldPersistOnlySecretRefMarkersAndRestoreAtExecutionBoundary() throws Exception {
        when(secretRefService.createLocal(eq("tenant-a"), eq("mcp"), any(), any(), isNull()))
            .thenReturn(new SecretRefService.SecretWriteResult(9L, 1, "cipher", null));
        String submitted = "{\"url\":\"https://mcp.example.com/mcp\","
            + "\"headers\":{\"Authorization\":\"Bearer top-secret\",\"X-Key\":\"key-1\"}}";

        McpCredentialService.StoredConfig stored = service.create(
            "tenant-a", "orders", "http", submitted, null);

        assertEquals(9L, stored.secretRefId());
        assertFalse(stored.protectedConfig().contains("top-secret"));
        assertFalse(stored.protectedConfig().contains("key-1"));
        assertTrue(stored.protectedConfig().contains(McpConfigProtector.SECRET_REF_MARKER));
        ArgumentCaptor<String> material = ArgumentCaptor.forClass(String.class);
        verify(secretRefService).createLocal(eq("tenant-a"), eq("mcp"), any(), material.capture(), isNull());

        AiMcp mcp = mcp(stored.protectedConfig(), 9L);
        when(secretRefService.resolvePlaintext(9L, "tenant-a")).thenReturn(material.getValue());
        JsonNode resolved = objectMapper.readTree(service.resolve(mcp));

        assertEquals("Bearer top-secret", resolved.path("headers").path("Authorization").asText());
        assertEquals("key-1", resolved.path("headers").path("X-Key").asText());
    }

    @Test
    void update_shouldRotateBundleWhileRedactedFieldKeepsPreviousSecret() throws Exception {
        String protectedConfig = "{\"url\":\"https://mcp.example.com/mcp\","
            + "\"headers\":{\"Authorization\":\"__MCP_SECRET_REF__\"}}";
        String oldMaterial = "{\"/headers/Authorization\":\"Bearer old\"}";
        AiMcp current = mcp(protectedConfig, 9L);
        when(secretRefService.resolvePlaintext(9L, "tenant-a")).thenReturn(oldMaterial);
        when(secretRefService.rotateLocal(eq(9L), eq("tenant-a"), any(), isNull()))
            .thenReturn(new SecretRefService.SecretWriteResult(9L, 2, "cipher-2", null));
        String submitted = "{\"url\":\"https://mcp.example.com/mcp\","
            + "\"headers\":{\"Authorization\":\"__MCP_SECRET_REDACTED__\","
            + "\"X-Token\":\"new-token\"}}";

        McpCredentialService.StoredConfig stored = service.update(current, "http", submitted, null);

        ArgumentCaptor<String> material = ArgumentCaptor.forClass(String.class);
        verify(secretRefService).rotateLocal(eq(9L), eq("tenant-a"), material.capture(), isNull());
        JsonNode bundle = objectMapper.readTree(material.getValue());
        assertEquals("Bearer old", bundle.path("/headers/Authorization").asText());
        assertEquals("new-token", bundle.path("/headers/X-Token").asText());
        assertFalse(stored.protectedConfig().contains("Bearer old"));
        assertFalse(stored.protectedConfig().contains("new-token"));
    }

    @Test
    void removingAllSecrets_shouldRevokeOldReference() {
        AiMcp current = mcp("{\"url\":\"https://mcp.example.com/mcp\","
            + "\"headers\":{\"Authorization\":\"__MCP_SECRET_REF__\"}}", 9L);
        when(secretRefService.resolvePlaintext(9L, "tenant-a"))
            .thenReturn("{\"/headers/Authorization\":\"Bearer old\"}");

        McpCredentialService.StoredConfig stored = service.update(current, "http",
            "{\"url\":\"https://mcp.example.com/mcp\"}", null);

        assertEquals(null, stored.secretRefId());
        verify(secretRefService).revoke(9L, "tenant-a");
    }

    @Test
    void legacyInlineSecretIsDetectedButNonSecretConfigIsNot() {
        assertTrue(service.hasLegacyInlineSecrets(mcp(
            "{\"url\":\"https://mcp.example.com\",\"headers\":{\"Authorization\":\"old\"}}", null)));
        assertFalse(service.hasLegacyInlineSecrets(mcp(
            "{\"url\":\"https://mcp.example.com\"}", null)));
    }

    private AiMcp mcp(String config, Long refId) {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setTenantId("tenant-a");
        mcp.setMcpName("orders");
        mcp.setMcpType("http");
        mcp.setConfig(config);
        mcp.setSecretRefId(refId);
        return mcp;
    }
}
