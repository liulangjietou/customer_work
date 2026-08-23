package com.richard.fyoung.customeradmin.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** admin 生产启动硬门禁单测。 */
class AdminProductionReadinessValidatorTest {

    @Test
    void productionConfiguration_shouldPass_withRemoteDependenciesAndDisabledExecutionSurface() {
        MockEnvironment environment = validEnvironment();

        assertDoesNotThrow(() -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());
    }

    @Test
    void modelEgressAllowlist_shouldBeRequiredInProduction() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.model.egress.allowed-hosts", "   ");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.model.egress.allowed-hosts"));
    }

    @Test
    void developmentDefaults_shouldFail_withoutLeakingValues() {
        MockEnvironment environment = validEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/customer_admin")
            .withProperty("admin.aes-secret-key", "0123456789abcdef0123456789abcdef")
            .withProperty("admin.redis.password", "REPLACE_ME")
            .withProperty("admin.sandbox.mode", "local");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("spring.datasource.url"));
        assertTrue(error.getMessage().contains("admin.aes-secret-key"));
        assertTrue(error.getMessage().contains("admin.redis.password"));
        assertTrue(error.getMessage().contains("admin.sandbox.mode"));
        assertFalse(error.getMessage().contains("REPLACE_ME"));
    }

    @Test
    void dockerSandbox_shouldRequireNetworkIsolation() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.sandbox.mode", "docker")
            .withProperty("admin.sandbox.docker.network", "bridge");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.sandbox.docker.network"));
    }

    @Test
    void aiCodingFeatures_shouldBeForbiddenInProduction() {
        List<String> featureKeys = List.of(
            "admin.sandbox.features.command-execution-enabled",
            "admin.sandbox.features.diagnosis-enabled",
            "admin.sandbox.features.refactor-enabled",
            "admin.sandbox.features.management-enabled");

        for (String featureKey : featureKeys) {
            MockEnvironment environment = validEnvironment().withProperty(featureKey, "true");

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

            assertTrue(error.getMessage().contains(featureKey));
        }
    }

    @Test
    void runtimePublish_shouldRejectLocalNacosAndMissingDedicatedAckIdentities() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.runtime-publish.nacos.server-addr", "localhost:8848")
            .withProperty("admin.open-api.token", "REPLACE_ME")
            .withProperty("admin.runtime-publish.ack-identities[0]", "broken")
            .withProperty("admin.runtime-publish.ack-identities[1]", "also-broken");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.runtime-publish.nacos.server-addr"));
        assertTrue(error.getMessage().contains("admin.open-api.token"));
        assertTrue(error.getMessage().contains("admin.runtime-publish.ack-identities"));
    }

    @Test
    void runtimePublish_shouldRejectWeakDedicatedAckToken() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.runtime-publish.ack-identities[0]", "default|customer-work-1|short")
            .withProperty("admin.runtime-publish.ack-identities[1]",
                "default|customer-work-2|runtime-ack-secret-instance-0002");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.runtime-publish.ack-identities"));
    }

    @Test
    void runtimePublish_shouldRequireMinimumAckCountForEveryTenant() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.runtime-publish.ack-identities[0]",
                "tenant-a|customer-work-1|runtime-ack-secret-tenant-a-0001")
            .withProperty("admin.runtime-publish.ack-identities[1]",
                "tenant-b|customer-work-1|runtime-ack-secret-tenant-b-0001");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.runtime-publish.ack-identities"));
    }

    @Test
    void tenantMode_shouldRequireTenantBoundOpenApiToken() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.tenant.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.open-api.tenant-tokens"));

        environment.withProperty("admin.open-api.tenant-tokens[runtime-ack-secret]", "tenant-a");
        assertDoesNotThrow(() -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());
    }

    private MockEnvironment validEnvironment() {
        return new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://mysql.internal:3306/customer_admin")
            .withProperty("spring.datasource.password", "database-secret")
            .withProperty("admin.redis.host", "redis.internal")
            .withProperty("admin.redis.password", "redis-secret")
            .withProperty("admin.sa-token.redis-persistent", "true")
            .withProperty("admin.aes-secret-key", "production-aes-key-32-bytes-0001")
            .withProperty("admin.customer-work.base-url", "http://customer-work-app:8080")
            .withProperty("admin.customer-work.ws-url", "wss://customer.example/ws/agent")
            .withProperty("admin.customer-work.api-key", "customer-work-api-secret")
            .withProperty("admin.customer-work.agent-secret", "production-agent-secret-32-bytes-0001")
            .withProperty("admin.model.egress.allowed-hosts",
                "api.openai.com,api.anthropic.com,generativelanguage.googleapis.com,"
                    + "dashscope.aliyuncs.com,ollama.internal")
            .withProperty("admin.sandbox.mode", "disabled")
            .withProperty("customer-work.attachment.storage.minio.endpoint", "http://minio.internal:9000")
            .withProperty("customer-work.attachment.storage.minio.access-key", "minio-access-secret")
            .withProperty("customer-work.attachment.storage.minio.secret-key", "minio-secret-value")
            .withProperty("admin.runtime-publish.nacos.enabled", "true")
            .withProperty("admin.runtime-publish.nacos.server-addr", "nacos.internal:8848")
            .withProperty("admin.runtime-publish.nacos.namespace", "customer-work")
            .withProperty("admin.runtime-publish.nacos.group", "DEFAULT_GROUP")
            .withProperty("admin.runtime-publish.nacos.data-id", "customer-work-runtime-config")
            .withProperty("admin.runtime-publish.nacos.username", "nacos-user")
            .withProperty("admin.runtime-publish.nacos.password", "nacos-secret")
            .withProperty("admin.open-api.token", "runtime-ack-secret")
            .withProperty("admin.runtime-publish.scan-interval-ms", "5000")
            .withProperty("admin.runtime-publish.lease-ms", "60000")
            .withProperty("admin.runtime-publish.batch-size", "20")
            .withProperty("admin.runtime-publish.max-attempts", "8")
            .withProperty("admin.runtime-publish.base-backoff-ms", "5000")
            .withProperty("admin.runtime-publish.nacos.timeout-ms", "3000")
            .withProperty("admin.runtime-publish.minimum-ack-count", "2")
            .withProperty("admin.runtime-publish.ack-identities[0]",
                "default|customer-work-1|runtime-ack-secret-instance-0001")
            .withProperty("admin.runtime-publish.ack-identities[1]",
                "default|customer-work-2|runtime-ack-secret-instance-0002");
    }
}
