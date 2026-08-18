package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.data.attachment.AttachmentProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

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
        properties.getUserAuth().setJwtSecret(ProductionReadinessValidator.DEV_USER_JWT_SECRET);
        properties.getAgentAccess().setSecret(ProductionReadinessValidator.DEV_AGENT_ACCESS_SECRET);
        AttachmentProperties attachment = new AttachmentProperties();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionReadinessValidator(properties, attachment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("customer-work.security.auth.enabled"));
        assertTrue(error.getMessage().contains("customer-work.user-auth.jwt-secret"));
        assertTrue(error.getMessage().contains("customer-work.attachment.storage.minio.secret-key"));
        assertFalse(error.getMessage().contains(ProductionReadinessValidator.DEV_USER_JWT_SECRET));
        assertFalse(error.getMessage().contains(ProductionReadinessValidator.DEV_MINIO_CREDENTIAL));
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
    }

    private CustomerWorkProperties validProperties() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getModel().setApiKey("model-secret");
        properties.getSession().getMysql().setPassword("database-secret");
        properties.getSession().getMysql().setMigrationEnabled(true);
        properties.getHumanApproval().setStoreMode("jdbc");
        properties.getSecurity().getAuth().setEnabled(true);
        properties.getSecurity().getAuth().setApiKeys(List.of("api-secret"));
        properties.getSecurity().getApprovalAuth().setEnabled(true);
        properties.getSecurity().getApprovalAuth().getOperators().put("approval-secret", "operator-a");
        properties.getUserAuth().setJwtSecret("jwt-secret-with-at-least-32-characters-0001");
        properties.getAgentAccess().setSecret("agent-secret-with-at-least-32-characters-01");
        properties.getDistributed().setCounterMode("redis");
        properties.getDistributed().setSessionLockMode("redis");
        properties.getSkill().setRepository("mysql");
        properties.getNotification().setWebhookUrl("https://notify.internal.example/messages");
        properties.getNotification().setAuthToken("notification-secret");
        return properties;
    }

    private AttachmentProperties validAttachmentProperties() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.getStorage().getMinio().setEndpoint("https://minio.internal");
        properties.getStorage().getMinio().setAccessKey("production-access-key");
        properties.getStorage().getMinio().setSecretKey("production-secret-key");
        return properties;
    }
}
