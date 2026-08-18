package com.richard.fyoung.customeradmin.config;

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

/** admin 生产启动硬门禁：拒绝本地依赖、开发密钥和无隔离代码执行进入流量池。 */
@Component
@Profile("prod")
public class AdminProductionReadinessValidator implements InitializingBean {

    private static final String DEV_AES_KEY = "0123456789abcdef0123456789abcdef";
    private static final String DEV_AGENT_SECRET = "dev-agent-secret-change-me-0001";
    private static final String DEV_MINIO_CREDENTIAL = "minioadmin";

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
            isProductionSecret(agentSecret) && !DEV_AGENT_SECRET.equals(agentSecret)
                && agentSecret.length() >= 32);

        String sandboxMode = value("admin.sandbox.mode");
        boolean safeSandbox = "docker".equalsIgnoreCase(sandboxMode)
            || "disabled".equalsIgnoreCase(sandboxMode);
        require(violations, "admin.sandbox.mode", safeSandbox);
        if ("docker".equalsIgnoreCase(sandboxMode)) {
            require(violations, "admin.sandbox.docker.network",
                "none".equalsIgnoreCase(value("admin.sandbox.docker.network")));
        }

        requireRemote(violations, "customer-work.attachment.storage.minio.endpoint");
        requireNonDefaultSecret(violations, "customer-work.attachment.storage.minio.access-key",
            DEV_MINIO_CREDENTIAL);
        requireNonDefaultSecret(violations, "customer-work.attachment.storage.minio.secret-key",
            DEV_MINIO_CREDENTIAL);
        validateOpenApi(violations);
        validateRuntimePublish(violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException("admin production readiness validation failed, invalid keys: "
                + String.join(", ", violations));
        }
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
        require(violations, "admin.runtime-publish.nacos.timeout-ms",
            positiveLong("admin.runtime-publish.nacos.timeout-ms"));
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
