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
    private static final int MIN_A2A_TOKEN_BYTES = 32;
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
        requireText(violations, "admin.customer-work.api-key-id");
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
        validateModelHealth(violations);
        validateOpenApi(violations);
        validateA2a(violations);
        validateRuntimePublish(violations);
        validateGovernance(violations);
        validatePublicDeployment(violations);
        validateEmailVerification(violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException("admin production readiness validation failed, invalid keys: "
                + String.join(", ", violations));
        }
    }

    /**
     * 对外开放实例的额外门禁。
     *
     * <p>只在 {@code admin.public-deployment.enabled=true} 时生效，逐条都是"漏了就会被人
     * 拿去烧钱或翻数据"的项：</p>
     * <ul>
     *   <li><b>用量配额必须开</b>：注册者是陌生人，不限量等于把平台的模型账单交给公众；</li>
     *   <li><b>默认档不能是 admin-default</b>：那是按内部员工定的 1 小时 200 万 token，
     *       对外该用 {@code public-trial}（cw 库 V23 种子）；</li>
     *   <li><b>多租户必须开</b>：关掉等于所有注册者与平台共处一个隔离域。</li>
     * </ul>
     *
     * <p>邮件那一项不在这里判——它同时是"审核结果要能通知到人"和"注册验证码要发得出去"
     * 两件事的前提，且都只在自助注册开着时才成立，故统一收在
     * {@link #validateEmailVerification} 一处，避免两段各判一次而条件不一致。</p>
     */
    private void validatePublicDeployment(List<String> violations) {
        if (!environment.getProperty("admin.public-deployment.enabled", Boolean.class, false)) {
            return;
        }
        require(violations, "admin.subject-quota.enabled",
            environment.getProperty("admin.subject-quota.enabled", Boolean.class, false));
        String defaultLevel = value("admin.subject-quota.default-level");
        require(violations, "admin.subject-quota.default-level",
            hasText(defaultLevel) && !"admin-default".equals(defaultLevel));
        require(violations, "admin.tenant.enabled",
            environment.getProperty("admin.tenant.enabled", Boolean.class, true));
    }

    /**
     * 自助注册开着时，邮件必须真的能发。
     *
     * <p>两件事都指向它：<b>审核结果要通知到人</b>（只发站内信的话，被拒绝的人永远看不到，
     * 通过的人也不知道自己已经可以用了），以及<b>注册验证码要发得出去</b>
     * （开了邮箱验证却没配 SMTP，注册链路会在"获取验证码"那一步整体失败——
     * 运行时有 fail-closed 兜底，但那时用户已经在注册页上了，不如启动时就拒绝）。</p>
     *
     * <p><b>前置条件是自助注册开着</b>：关掉自助注册的实例只由管理员预建账号，
     * 既不会发验证码、也没有待审核的人要通知，此时强制配 SMTP 只是白挡一道。</p>
     *
     * <p><b>找回密码刻意不参与这里的判定</b>，尽管它同样要发信：它的能力跟随
     * {@code AdminMailSender#available()}，邮件不可用时登录页干脆不渲染入口、接口直接拒绝，
     * 不存在"用户走到一半才发现发不出去"的状态。把它也列成硬性前置，等于强迫每一个
     * 只用管理员建号的内网实例都去配一套 SMTP。对外实例本就因邮箱验证被强制配好了邮件，
     * 找回密码在那里必然可用。</p>
     */
    private void validateEmailVerification(List<String> violations) {
        if (!environment.getProperty("admin.registration.self-service-enabled", Boolean.class, true)) {
            return;
        }
        boolean required = environment.getProperty("admin.public-deployment.enabled", Boolean.class, false)
            || environment.getProperty("admin.registration.email-verification.enabled", Boolean.class, false);
        if (!required) {
            return;
        }
        requireWorkingMail(violations);
    }

    private void requireWorkingMail(List<String> violations) {
        require(violations, "admin.notification.mail.enabled",
            environment.getProperty("admin.notification.mail.enabled", Boolean.class, false));
        requireText(violations, "admin.notification.mail.host");
    }

    private void validateModelEgress(List<String> violations) {
        List<String> allowedHosts = Binder.get(environment)
            .bind("admin.model.egress.allowed-hosts", Bindable.listOf(String.class))
            .orElse(List.of());
        require(violations, "admin.model.egress.allowed-hosts",
            allowedHosts.stream().anyMatch(this::hasText));
    }

    private void validateModelHealth(List<String> violations) {
        if (!environment.getProperty("admin.model-health.enabled", Boolean.class, false)) {
            return;
        }
        require(violations, "admin.runtime-publish.nacos.enabled",
            environment.getProperty("admin.runtime-publish.nacos.enabled", Boolean.class, false));
        require(violations, "admin.model-health.scan-interval-ms",
            positiveLong("admin.model-health.scan-interval-ms"));
        require(violations, "admin.model-health.batch-size",
            environment.getProperty("admin.model-health.batch-size", Integer.class, 0) > 0);
        require(violations, "admin.model-health.worker-count",
            environment.getProperty("admin.model-health.worker-count", Integer.class, 0) > 0);
        require(violations, "admin.model-health.queue-capacity",
            environment.getProperty("admin.model-health.queue-capacity", Integer.class, 0) > 0);
        require(violations, "admin.model-health.probe-timeout-seconds",
            positiveLong("admin.model-health.probe-timeout-seconds"));
        require(violations, "admin.model-health.failure-threshold",
            environment.getProperty("admin.model-health.failure-threshold", Integer.class, 0) > 0);
        require(violations, "admin.model-health.recovery-threshold",
            environment.getProperty("admin.model-health.recovery-threshold", Integer.class, 0) > 0);
        require(violations, "admin.model-health.probe-interval-seconds",
            positiveLong("admin.model-health.probe-interval-seconds"));
        require(violations, "admin.model-health.cooldown-seconds",
            positiveLong("admin.model-health.cooldown-seconds"));
        require(violations, "admin.model-health.max-override-hours",
            environment.getProperty("admin.model-health.max-override-hours", Integer.class, 0) > 0);
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
        require(violations, "admin.runtime-publish.signing.enabled",
            environment.getProperty("admin.runtime-publish.signing.enabled", Boolean.class, false));
        requireText(violations, "admin.runtime-publish.signing.key-id");
        String signingSecret = value("admin.runtime-publish.signing.secret");
        require(violations, "admin.runtime-publish.signing.secret",
            isProductionSecret(signingSecret)
                && signingSecret.getBytes(StandardCharsets.UTF_8).length >= 32);
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

    private void validateA2a(List<String> violations) {
        if (!environment.getProperty("admin.a2a.enabled", Boolean.class, false)) {
            return;
        }
        requireText(violations, "admin.a2a.agent-code");
        String token = value("admin.a2a.token");
        require(violations, "admin.a2a.token", isProductionSecret(token)
            && token.getBytes(StandardCharsets.UTF_8).length >= MIN_A2A_TOKEN_BYTES);
    }

    private void validateGovernance(List<String> violations) {
        require(violations, "admin.governance.approval-expiry-hours",
            environment.getProperty("admin.governance.approval-expiry-hours", Integer.class, 24) > 0);
        require(violations, "admin.governance.execution-timeout-seconds",
            environment.getProperty("admin.governance.execution-timeout-seconds", Integer.class, 600) >= 60);
        require(violations, "admin.governance.audit-retention-days",
            environment.getProperty("admin.governance.audit-retention-days", Integer.class, 3650) >= 365);
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
