package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimeAckIdentity;
import com.richard.fyoung.customerwork.core.constant.DevDefaultCredentials;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** admin 生产启动硬门禁：拒绝本地依赖、开发密钥和无隔离代码执行进入流量池。 */
@Component
@Profile("prod")
public class AdminProductionReadinessValidator implements InitializingBean {

    private static final String DEV_AES_KEY = "0123456789abcdef0123456789abcdef";
    private static final int MIN_RUNTIME_ACK_TOKEN_BYTES = 32;
    private static final List<String> FORBIDDEN_PRODUCTION_AI_CODING_FEATURES = List.of(
        "admin.sandbox.features.command-execution-enabled",
        "admin.sandbox.features.diagnosis-enabled",
        "admin.sandbox.features.refactor-enabled",
        "admin.sandbox.features.management-enabled");

    private final Environment environment;

    public AdminProductionReadinessValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = new ArrayList<>();
        requireRemote(violations, "spring.datasource.url");
        requireSecret(violations, "spring.datasource.password");
        requireRemote(violations, "admin.redis.host");
        requireSecret(violations, "admin.redis.password");
        require(violations, "admin.sa-token.redis-persistent",
            environment.getProperty("admin.sa-token.redis-persistent", Boolean.class, true));

        String aesKey = value("admin.aes-secret-key");
        int aesBytes = hasText(aesKey) ? aesKey.getBytes(StandardCharsets.UTF_8).length : 0;
        require(violations, "admin.aes-secret-key",
            isProductionSecret(aesKey) && !DEV_AES_KEY.equals(aesKey) && aesBytes == 32);

        requireRemote(violations, "admin.customer-work.base-url");
        requireRemote(violations, "admin.customer-work.ws-url");
        requireSecret(violations, "admin.customer-work.api-key");
        String agentSecret = value("admin.customer-work.agent-secret");
        require(violations, "admin.customer-work.agent-secret",
            isProductionSecret(agentSecret) && !DevDefaultCredentials.AGENT_ACCESS_SECRET.equals(agentSecret)
                && agentSecret.length() >= 32);

        String sandboxMode = value("admin.sandbox.mode");
        boolean safeSandbox = "docker".equalsIgnoreCase(sandboxMode)
            || "disabled".equalsIgnoreCase(sandboxMode);
        require(violations, "admin.sandbox.mode", safeSandbox);
        if ("docker".equalsIgnoreCase(sandboxMode)) {
            require(violations, "admin.sandbox.docker.network",
                "none".equalsIgnoreCase(value("admin.sandbox.docker.network")));
        }
        FORBIDDEN_PRODUCTION_AI_CODING_FEATURES.forEach(key ->
            require(violations, key, !environment.getProperty(key, Boolean.class, false)));

        requireRemote(violations, "customer-work.attachment.storage.minio.endpoint");
        requireNonDefaultSecret(violations, "customer-work.attachment.storage.minio.access-key",
            DevDefaultCredentials.MINIO_CREDENTIAL);
        requireNonDefaultSecret(violations, "customer-work.attachment.storage.minio.secret-key",
            DevDefaultCredentials.MINIO_CREDENTIAL);
        validateModelEgress(violations);
        validateOpenApi(violations);
        validateRuntimePublish(violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException("admin production readiness validation failed, invalid keys: "
                + String.join(", ", violations));
        }
    }

    private void validateModelEgress(List<String> violations) {
        List<String> allowedHosts = Binder.get(environment)
            .bind("admin.model.egress.allowed-hosts", Bindable.listOf(String.class))
            .orElse(List.of());
        require(violations, "admin.model.egress.allowed-hosts",
            allowedHosts.stream().anyMatch(this::hasText));
    }

    private void validateRuntimePublish(List<String> violations) {
        if (!environment.getProperty("admin.runtime-publish.nacos.enabled", Boolean.class, false)) {
            return;
        }
        requireRemote(violations, "admin.runtime-publish.nacos.server-addr");
        requireText(violations, "admin.runtime-publish.nacos.namespace");
        requireText(violations, "admin.runtime-publish.nacos.group");
        requireText(violations, "admin.runtime-publish.nacos.data-id");
        requireText(violations, "admin.runtime-publish.nacos.username");
        requireNonDefaultSecret(violations, "admin.runtime-publish.nacos.password", "nacos");
        if (!environment.getProperty("admin.tenant.enabled", Boolean.class, false)) {
            requireSecret(violations, "admin.open-api.token");
        }
        require(violations, "admin.runtime-publish.scan-interval-ms",
            positiveLong("admin.runtime-publish.scan-interval-ms"));
        require(violations, "admin.runtime-publish.lease-ms",
            positiveLong("admin.runtime-publish.lease-ms"));
        require(violations, "admin.runtime-publish.batch-size",
            environment.getProperty("admin.runtime-publish.batch-size", Integer.class, 0) > 0);
        require(violations, "admin.runtime-publish.max-attempts",
            environment.getProperty("admin.runtime-publish.max-attempts", Integer.class, 0) > 0);
        require(violations, "admin.runtime-publish.base-backoff-ms",
            positiveLong("admin.runtime-publish.base-backoff-ms"));
        require(violations, "admin.runtime-publish.minimum-ack-count",
            environment.getProperty("admin.runtime-publish.minimum-ack-count", Integer.class, 0) > 0);
        validateRuntimeAckIdentities(violations);
        require(violations, "admin.runtime-publish.nacos.timeout-ms",
            positiveLong("admin.runtime-publish.nacos.timeout-ms"));
    }

    private void validateRuntimeAckIdentities(List<String> violations) {
        List<String> configured = Binder.get(environment)
            .bind("admin.runtime-publish.ack-identities", Bindable.listOf(String.class))
            .orElse(List.of());
        List<RuntimeAckIdentity> identities = configured.stream()
            .map(RuntimeAckIdentity::parse)
            .flatMap(java.util.Optional::stream)
            .toList();
        int minimumAckCount = environment.getProperty(
            "admin.runtime-publish.minimum-ack-count", Integer.class, 0);
        boolean syntaxValid = !configured.isEmpty() && identities.size() == configured.size();
        boolean secretsValid = identities.stream().allMatch(identity ->
            isProductionSecret(identity.token())
                && identity.token().getBytes(StandardCharsets.UTF_8).length >= MIN_RUNTIME_ACK_TOKEN_BYTES);
        boolean uniqueTokens = identities.stream().map(RuntimeAckIdentity::token).distinct().count()
            == identities.size();
        boolean uniqueInstances = identities.stream()
            .map(identity -> TenantContext.normalizedTenantKey(identity.tenantId())
                + "\u001f" + identity.instanceId())
            .distinct().count() == identities.size();
        Map<String, Long> instanceCountsByTenant = identities.stream().collect(
            Collectors.groupingBy(identity -> TenantContext.normalizedTenantKey(identity.tenantId()),
                Collectors.counting()));
        boolean everyTenantCanReachQuorum = !instanceCountsByTenant.isEmpty()
            && instanceCountsByTenant.values().stream().allMatch(count -> count >= minimumAckCount);
        require(violations, "admin.runtime-publish.ack-identities",
            syntaxValid && secretsValid && uniqueTokens && uniqueInstances
                && everyTenantCanReachQuorum);
    }

    private void validateOpenApi(List<String> violations) {
        if (!environment.getProperty("admin.tenant.enabled", Boolean.class, false)) {
            return;
        }
        Map<String, String> tenantTokens = Binder.get(environment)
            .bind("admin.open-api.tenant-tokens", Bindable.mapOf(String.class, String.class))
            .orElse(Map.of());
        require(violations, "admin.open-api.tenant-tokens",
            tenantTokens.entrySet().stream().anyMatch(entry ->
                isProductionSecret(entry.getKey()) && hasText(entry.getValue())));
    }

    private void requireRemote(List<String> violations, String key) {
        String configured = value(key);
        String normalized = configured == null ? "" : configured.toLowerCase();
        require(violations, key, hasText(configured)
            && !normalized.contains("localhost") && !normalized.contains("127.0.0.1")
            && !normalized.contains("replace"));
    }

    private void requireSecret(List<String> violations, String key) {
        require(violations, key, isProductionSecret(value(key)));
    }

    private void requireText(List<String> violations, String key) {
        require(violations, key, hasText(value(key)));
    }

    private void requireNonDefaultSecret(List<String> violations, String key, String defaultValue) {
        String configured = value(key);
        require(violations, key, isProductionSecret(configured) && !defaultValue.equals(configured));
    }

    private String value(String key) {
        return environment.getProperty(key);
    }

    private boolean positiveLong(String key) {
        return environment.getProperty(key, Long.class, 0L) > 0L;
    }

    private boolean isProductionSecret(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase();
        return !normalized.contains("replace") && !normalized.contains("change-me")
            && !normalized.contains("changeme") && !normalized.contains("example");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void require(List<String> violations, String key, boolean valid) {
        if (!valid) {
            violations.add(key);
        }
    }
}
