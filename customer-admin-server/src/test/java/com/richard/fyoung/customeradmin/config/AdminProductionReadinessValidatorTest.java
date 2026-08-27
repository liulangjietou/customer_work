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
    void a2aShouldRejectDevelopmentTokenWhenEnabled() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.a2a.enabled", "true")
            .withProperty("admin.a2a.agent-code", "customer-service")
            .withProperty("admin.a2a.token", "111111");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.a2a.token"));
        assertFalse(error.getMessage().contains("111111"));
    }

    @Test
    void a2aShouldAcceptDedicatedStrongTokenWhenEnabled() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.a2a.enabled", "true")
            .withProperty("admin.a2a.agent-code", "customer-service")
            .withProperty("admin.a2a.token", "production-a2a-token-at-least-32-bytes");

        assertDoesNotThrow(() -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());
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

    @Test
    void governance_shouldRequireBoundedApprovalAndMinimumAuditRetention() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.governance.approval-expiry-hours", "0")
            .withProperty("admin.governance.execution-timeout-seconds", "30")
            .withProperty("admin.governance.audit-retention-days", "30");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.governance.approval-expiry-hours"));
        assertTrue(error.getMessage().contains("admin.governance.execution-timeout-seconds"));
        assertTrue(error.getMessage().contains("admin.governance.audit-retention-days"));
    }

    @Test
    void modelHealth_shouldRequireBoundedStateMachineAndReliablePublish() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.model-health.failure-threshold", "0")
            .withProperty("admin.model-health.recovery-threshold", "0")
            .withProperty("admin.model-health.cooldown-seconds", "0")
            .withProperty("admin.model-health.max-override-hours", "0");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.model-health.failure-threshold"));
        assertTrue(error.getMessage().contains("admin.model-health.recovery-threshold"));
        assertTrue(error.getMessage().contains("admin.model-health.cooldown-seconds"));
        assertTrue(error.getMessage().contains("admin.model-health.max-override-hours"));
    }

    /**
     * 对外开放实例的额外门禁：只在 {@code admin.public-deployment.enabled=true} 时生效，
     * 内网实例不受影响。
     */
    @Test
    void publicDeploymentGate_shouldStayInactiveOnInternalDeployment() {
        MockEnvironment environment = validEnvironment()
            .withProperty("admin.subject-quota.enabled", "false");

        assertDoesNotThrow(() -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());
    }

    /** 注册者是陌生人，不限量等于把平台的模型账单交给公众。 */
    @Test
    void publicDeployment_shouldRequireSubjectQuotaEnabled() {
        MockEnvironment environment = publicDeploymentEnvironment()
            .withProperty("admin.subject-quota.enabled", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.subject-quota.enabled"));
    }

    /** admin-default 是按内部员工定的 1 小时 200 万 token，对外该用 public-trial。 */
    @Test
    void publicDeployment_shouldRejectInternalDefaultQuotaLevel() {
        MockEnvironment environment = publicDeploymentEnvironment()
            .withProperty("admin.subject-quota.default-level", "admin-default");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.subject-quota.default-level"));
    }

    /** 关掉多租户等于所有注册者与平台共处一个隔离域。 */
    @Test
    void publicDeployment_shouldRequireTenantIsolation() {
        MockEnvironment environment = publicDeploymentEnvironment()
            .withProperty("admin.tenant.enabled", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.tenant.enabled"));
    }

    /**
     * 开放自助注册就必须能通知：只发站内信的话，被拒绝的人永远看不到，
     * 通过的人也不知道自己已经可以用了。
     */
    @Test
    void publicDeployment_shouldRequireMailWhenSelfRegistrationOpen() {
        MockEnvironment environment = publicDeploymentEnvironment()
            .withProperty("admin.notification.mail.enabled", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());

        assertTrue(error.getMessage().contains("admin.notification.mail.enabled"));
    }

    /** 关闭自助注册（仅管理员预建账号）时不强制邮件——没有待通知的人。 */
    @Test
    void publicDeployment_shouldNotRequireMailWhenSelfRegistrationClosed() {
        MockEnvironment environment = publicDeploymentEnvironment()
            .withProperty("admin.registration.self-service-enabled", "false")
            .withProperty("admin.notification.mail.enabled", "false")
            .withProperty("admin.notification.mail.host", "");

        assertDoesNotThrow(() -> new AdminProductionReadinessValidator(environment).afterPropertiesSet());
    }

    @Test
    void publicDeployment_shouldPassWithCompleteConfiguration() {
        assertDoesNotThrow(() ->
            new AdminProductionReadinessValidator(publicDeploymentEnvironment()).afterPropertiesSet());
    }

    private MockEnvironment publicDeploymentEnvironment() {
        return validEnvironment()
            .withProperty("admin.public-deployment.enabled", "true")
            .withProperty("admin.subject-quota.enabled", "true")
            .withProperty("admin.subject-quota.default-level", "public-trial")
            .withProperty("admin.tenant.enabled", "true")
            // 多租户一开，开放 API 就必须配 token→租户映射（见 validateOpenApi），
            // 与本节要验的对外部署门禁无关，这里补齐以免干扰断言
            .withProperty("admin.open-api.tenant-tokens[runtime-ack-secret]", "tenant-a")
            .withProperty("admin.registration.self-service-enabled", "true")
            .withProperty("admin.notification.mail.enabled", "true")
            .withProperty("admin.notification.mail.host", "smtp.example.com");
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
            .withProperty("admin.customer-work.api-key-id", "admin-eval")
            .withProperty("admin.customer-work.api-key", "customer-work-api-secret")
            .withProperty("admin.customer-work.agent-secret", "production-agent-secret-32-bytes-0001")
            .withProperty("admin.model.egress.allowed-hosts",
                "api.openai.com,api.anthropic.com,generativelanguage.googleapis.com,"
                    + "dashscope.aliyuncs.com,ollama.internal")
            .withProperty("admin.model-health.enabled", "true")
            .withProperty("admin.model-health.scan-interval-ms", "60000")
            .withProperty("admin.model-health.batch-size", "20")
            .withProperty("admin.model-health.worker-count", "8")
            .withProperty("admin.model-health.queue-capacity", "32")
            .withProperty("admin.model-health.probe-timeout-seconds", "10")
            .withProperty("admin.model-health.failure-threshold", "3")
            .withProperty("admin.model-health.recovery-threshold", "2")
            .withProperty("admin.model-health.probe-interval-seconds", "300")
            .withProperty("admin.model-health.cooldown-seconds", "60")
            .withProperty("admin.model-health.max-override-hours", "24")
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
            .withProperty("admin.runtime-publish.signing.enabled", "true")
            .withProperty("admin.runtime-publish.signing.key-id", "runtime-signing-2026-08")
            .withProperty("admin.runtime-publish.signing.secret",
                "runtime-signing-secret-at-least-32-bytes")
            .withProperty("admin.runtime-publish.ack-identities[0]",
                "default|customer-work-1|runtime-ack-secret-instance-0001")
            .withProperty("admin.runtime-publish.ack-identities[1]",
                "default|customer-work-2|runtime-ack-secret-instance-0002");
    }
}
