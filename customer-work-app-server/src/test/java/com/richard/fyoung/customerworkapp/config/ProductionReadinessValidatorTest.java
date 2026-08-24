package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.core.constant.DevDefaultCredentials;
import com.richard.fyoung.customerwork.data.attachment.AttachmentProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SecurityProperties;
import com.richard.fyoung.customerwork.safety.security.ApiKeySecretHasher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生产启动门禁单元测试。 */
class ProductionReadinessValidatorTest {

    @Test
    void validProductionProperties_shouldPass() {
        CustomerWorkProperties properties = validProperties();
        AttachmentProperties attachment = validAttachmentProperties();

        assertDoesNotThrow(() -> new ProductionReadinessValidator(properties, attachment).afterPropertiesSet());
    }

    @Test
    void developmentDefaults_shouldFailWithoutLeakingSecretValues() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getUserAuth().setJwtSecret(DevDefaultCredentials.USER_JWT_SECRET);
        properties.getAgentAccess().setSecret(DevDefaultCredentials.AGENT_ACCESS_SECRET);
        AttachmentProperties attachment = new AttachmentProperties();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, attachment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.security.auth.enabled"));
        assertTrue(error.getMessage().contains("customer-work.user-auth.jwt-secret"));
        assertTrue(error.getMessage().contains("customer-work.attachment.storage.minio.secret-key"));
        assertFalse(error.getMessage().contains(DevDefaultCredentials.USER_JWT_SECRET));
        assertFalse(error.getMessage().contains(DevDefaultCredentials.MINIO_CREDENTIAL));
    }

    @Test
    void placeholderSecrets_shouldFail() {
        CustomerWorkProperties properties = validProperties();
        properties.getModel().setApiKey("REPLACE_ME");
        AttachmentProperties attachment = validAttachmentProperties();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, attachment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.model.api-key"));
        assertFalse(error.getMessage().contains("REPLACE_ME"));
    }

    @Test
    void runtimeConfigWithoutDurableAck_shouldFail() {
        CustomerWorkProperties properties = validProperties();
        properties.getNacos().setRuntimeConfigEnabled(true);
        properties.getNacos().setConfigAesKey("1234567890abcdef");
        properties.getNacos().setRuntimeConfigSubscribeRetryMs(0L);
        properties.getOutbox().setStoreMode("memory");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.nacos.runtime-config-ack-url"));
        assertTrue(error.getMessage().contains("customer-work.nacos.runtime-config-ack-token"));
        assertTrue(error.getMessage().contains("customer-work.nacos.runtime-config-subscribe-retry-ms"));
        assertTrue(error.getMessage().contains("customer-work.outbox.store-mode"));
        assertTrue(error.getMessage().contains("customer-work.model.egress.allowed-hosts"));
        assertTrue(error.getMessage().contains("customer-work.nacos.runtime-config-signature-required"));
        assertTrue(error.getMessage().contains("customer-work.nacos.runtime-config-signing-key-id"));
        assertTrue(error.getMessage().contains("customer-work.nacos.runtime-config-signing-secret"));
    }

    @Test
    void volatileCallLog_shouldFailBecauseExperimentAndSloFactsWouldDisappear() {
        CustomerWorkProperties properties = validProperties();
        properties.getCallLog().setStoreMode("memory");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.call-log.store-mode"));
    }

    @Test
    void longTermMemoryWithoutConsent_shouldFail() {
        CustomerWorkProperties properties = validProperties();
        properties.getMemory().setConsentRequired(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.memory.consent-required"));
    }

    @Test
    void longTermMemoryWithVolatileConsentStore_shouldFail() {
        CustomerWorkProperties properties = validProperties();
        properties.getMemory().setConsentStoreMode("memory");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.memory.consent-store-mode"));
    }

    @Test
    void longTermMemoryWithVolatileMemoryStore_shouldFail() {
        CustomerWorkProperties properties = validProperties();
        properties.getMemory().setStoreMode("memory");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.memory.store-mode"));
    }

    @Test
    void externalMemoryProviderWithoutErasureCapability_shouldFail() {
        CustomerWorkProperties properties = validProperties();
        properties.getMemory().setProvider("mem0");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.memory.provider.external-erasure-capability"));
    }

    @Test
    void disabledMemoryRetentionCleanup_shouldFail() {
        CustomerWorkProperties properties = validProperties();
        properties.getMemory().setRetentionCleanupEnabled(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.memory.retention-cleanup-enabled"));
    }

    @Test
    void legacyPlaintextApiKey_shouldFailProductionGate() {
        CustomerWorkProperties properties = validProperties();
        properties.getSecurity().getAuth().setApiKeys(List.of("legacy-plaintext-secret"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.security.auth.legacy-plaintext-disabled"));
        assertFalse(error.getMessage().contains("legacy-plaintext-secret"));
    }

    @Test
    void malformedOrExpiredStructuredApiKey_shouldFailProductionGate() {
        CustomerWorkProperties malformed = validProperties();
        malformed.getSecurity().getAuth().getCredentials().get(0).setKeyHash("not-sha256");
        CustomerWorkProperties expired = validProperties();
        expired.getSecurity().getAuth().getCredentials().get(0)
            .setExpiresAt(Instant.parse("2020-01-01T00:00:00Z"));

        IllegalStateException malformedError = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(malformed, validAttachmentProperties()).afterPropertiesSet());
        IllegalStateException expiredError = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(expired, validAttachmentProperties()).afterPropertiesSet());

        assertTrue(malformedError.getMessage().contains("customer-work.security.auth.credentials"));
        assertTrue(expiredError.getMessage().contains("customer-work.security.auth.credentials"));
    }

    private CustomerWorkProperties validProperties() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getModel().setApiKey("model-secret");
        properties.getSession().getMysql().setPassword("database-secret");
        properties.getSession().getMysql().setMigrationEnabled(true);
        properties.getHumanApproval().setStoreMode("jdbc");
        properties.getCallLog().setEnabled(true);
        properties.getCallLog().setStoreMode("jdbc");
        properties.getSecurity().getAuth().setEnabled(true);
        properties.getSecurity().getAuth().getCredentials().add(structuredCredential());
        properties.getSecurity().getApprovalAuth().setEnabled(true);
        properties.getSecurity().getApprovalAuth().getOperators().put("approval-secret", "operator-a");
        properties.getUserAuth().setJwtSecret("jwt-secret-with-at-least-32-characters-0001");
        properties.getAgentAccess().setSecret("agent-secret-with-at-least-32-characters-01");
        properties.getDistributed().setCounterMode("redis");
        properties.getDistributed().setSessionLockMode("redis");
        properties.getMemory().setConsentRequired(true);
        properties.getMemory().setStoreMode("jdbc");
        properties.getMemory().setConsentStoreMode("jdbc");
        properties.getMemory().setProvider("memory");
        properties.getSkill().setRepository("mysql");
        properties.getNotification().setWebhookUrl("https://notify.internal.example/messages");
        properties.getNotification().setAuthToken("notification-secret");
        return properties;
    }

    private SecurityProperties.Credential structuredCredential() {
        SecurityProperties.Credential credential = new SecurityProperties.Credential();
        credential.setKeyId("partner-a");
        credential.setKeyHash(ApiKeySecretHasher.sha256Hex("api-secret"));
        credential.setTenantId("default");
        credential.setScopes(List.of("*"));
        credential.setEpoch(1L);
        return credential;
    }

    private AttachmentProperties validAttachmentProperties() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.getStorage().getMinio().setEndpoint("https://minio.internal");
        properties.getStorage().getMinio().setAccessKey("production-access-key");
        properties.getStorage().getMinio().setSecretKey("production-secret-key");
        return properties;
    }
}
