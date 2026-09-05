package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.core.constant.KnowledgeProviders;
import com.richard.fyoung.customerwork.capability.approval.ApprovalExecutionHandler;
import com.richard.fyoung.customerwork.core.constant.DevDefaultCredentials;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.data.attachment.AttachmentProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.NacosProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SecurityProperties;
import com.richard.fyoung.customerwork.safety.security.ApiKeySecretHasher;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生产配置启动门禁：只检查会导致裸奔、跨实例失效或数据不可恢复的硬约束。
 *
 * <p>校验信息只输出配置键，不输出密钥值。任一项不满足即 fast fail，避免实例带着开发默认密钥或
 * 进程内实现进入流量池。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@Profile("prod")
public class ProductionReadinessValidator implements InitializingBean {

    private final CustomerWorkProperties properties;
    private final AttachmentProperties attachmentProperties;
    private final boolean approvalExecutionHandlerConfigured;

    public ProductionReadinessValidator(CustomerWorkProperties properties,
                                        AttachmentProperties attachmentProperties) {
        this(properties, attachmentProperties, true);
    }

    @Autowired
    public ProductionReadinessValidator(CustomerWorkProperties properties,
                                        AttachmentProperties attachmentProperties,
                                        ObjectProvider<ApprovalExecutionHandler> handlerProvider) {
        this(properties, attachmentProperties, handlerProvider.getIfAvailable() != null);
    }

    private ProductionReadinessValidator(CustomerWorkProperties properties,
                                         AttachmentProperties attachmentProperties,
                                         boolean approvalExecutionHandlerConfigured) {
        this.properties = properties;
        this.attachmentProperties = attachmentProperties;
        this.approvalExecutionHandlerConfigured = approvalExecutionHandlerConfigured;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = new ArrayList<>();
        validateModel(violations);
        validateDatabase(violations);
        validateAuthentication(violations);
        validateDistributedRuntime(violations);
        validateStorage(violations);
        validateMemoryPrivacy(violations);
        validateKnowledge(violations);
        validateNotification(violations);
        validateRuntimeConfig(violations);
        require(violations, "customer-work.human-approval.execution-handler",
            !properties.getHumanApproval().isEnabled() || approvalExecutionHandlerConfigured);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("production readiness validation failed, invalid keys: "
                + String.join(", ", violations));
        }
    }

    private void validateModel(List<String> violations) {
        if (!ModelProviders.OLLAMA.equalsIgnoreCase(properties.getModel().getProvider())) {
            requireSecret(violations, "customer-work.model.api-key", properties.getModel().getApiKey());
        }
    }

    private void validateDatabase(List<String> violations) {
        require(violations, "customer-work.session.mysql.migration-enabled",
            properties.getSession().getMysql().isSchemaMigrationEnabled());
        requireSecret(violations, "customer-work.session.mysql.password",
            properties.getSession().getMysql().getPassword());
        require(violations, "customer-work.human-approval.store-mode",
            !properties.getHumanApproval().isEnabled()
                || StoreModes.isJdbc(properties.getHumanApproval().getStoreMode()));
        require(violations, "customer-work.call-log.enabled", properties.getCallLog().isEnabled());
        require(violations, "customer-work.call-log.store-mode",
            StoreModes.isJdbc(properties.getCallLog().getStoreMode()));
    }

    private void validateAuthentication(List<String> violations) {
        SecurityProperties.Auth auth = properties.getSecurity().getAuth();
        require(violations, "customer-work.security.auth.enabled", auth.isEnabled());
        boolean legacyPlaintextAbsent = CollectionUtils.isEmpty(auth.getApiKeys())
            && (auth.getTenantKeys() == null || auth.getTenantKeys().isEmpty());
        require(violations, "customer-work.security.auth.legacy-plaintext-disabled", legacyPlaintextAbsent);
        require(violations, "customer-work.security.auth.credentials", validStructuredCredentials(auth));

        SecurityProperties.ApprovalAuth approvalAuth = properties.getSecurity().getApprovalAuth();
        require(violations, "customer-work.security.approval-auth.enabled", approvalAuth.isEnabled());
        require(violations, "customer-work.security.approval-auth.operators",
            approvalAuth.getOperators().entrySet().stream().anyMatch(this::validCredentialEntry));

        String jwtSecret = properties.getUserAuth().getJwtSecret();
        require(violations, "customer-work.user-auth.jwt-secret",
            isProductionSecret(jwtSecret) && !DevDefaultCredentials.USER_JWT_SECRET.equals(jwtSecret) && jwtSecret.length() >= 32);
        String agentSecret = properties.getAgentAccess().getSecret();
        require(violations, "customer-work.agent-access.secret",
            isProductionSecret(agentSecret) && !DevDefaultCredentials.AGENT_ACCESS_SECRET.equals(agentSecret)
                && agentSecret.length() >= 32);
    }

    private void validateDistributedRuntime(List<String> violations) {
        require(violations, "customer-work.distributed.counter-mode",
            StoreModes.isRedis(properties.getDistributed().getCounterMode()));
        require(violations, "customer-work.distributed.session-lock-mode",
            StoreModes.isRedis(properties.getDistributed().getSessionLockMode()));
    }

    private void validateStorage(List<String> violations) {
        require(violations, "customer-work.skill.repository",
            "mysql".equalsIgnoreCase(properties.getSkill().getRepository()));
        if (!attachmentProperties.isEnabled()) {
            return;
        }
        AttachmentProperties.Minio minio = attachmentProperties.getStorage().getMinio();
        requireText(violations, "customer-work.attachment.storage.minio.endpoint", minio.getEndpoint());
        require(violations, "customer-work.attachment.storage.minio.access-key",
            isProductionSecret(minio.getAccessKey()) && !DevDefaultCredentials.MINIO_CREDENTIAL.equals(minio.getAccessKey()));
        require(violations, "customer-work.attachment.storage.minio.secret-key",
            isProductionSecret(minio.getSecretKey()) && !DevDefaultCredentials.MINIO_CREDENTIAL.equals(minio.getSecretKey()));
    }

    /**
     * 外部长期记忆 Provider 当前没有可验证的删除回执契约，生产先限制为本地 JDBC；
     * 否则页面显示“已删除”而第三方仍保留数据，会形成虚假合规承诺。
     */
    private void validateMemoryPrivacy(List<String> violations) {
        if (!properties.getMemory().isLongTermEnabled()) {
            return;
        }
        require(violations, "customer-work.memory.consent-required",
            properties.getMemory().isConsentRequired());
        require(violations, "customer-work.memory.store-mode",
            StoreModes.isJdbc(properties.getMemory().getStoreMode()));
        require(violations, "customer-work.memory.consent-store-mode",
            StoreModes.isJdbc(properties.getMemory().getConsentStoreMode()));
        require(violations, "customer-work.memory.retention-cleanup-enabled",
            properties.getMemory().isRetentionCleanupEnabled());
        require(violations, "customer-work.memory.retention-days",
            properties.getMemory().getRetentionDays() > 0);
        require(violations, "customer-work.memory.withdrawn-consent-retention-days",
            properties.getMemory().getWithdrawnConsentRetentionDays() > 0);
        require(violations, "customer-work.memory.provider.external-erasure-capability",
            "memory".equalsIgnoreCase(properties.getMemory().getProvider()));
    }

    /**
     * RAG 知识库不得使用内置演示语料。
     *
     * <p>{@code provider=memory} 的语料是 {@code KnowledgeProvider} 里硬编码的 4 条售后政策演示文本，
     * 打分函数只是"查询里有几个字出现在文档中"。它上生产的后果是：客服智能体的全部知识就是那 4 句话，
     * 而后台整套企业知识库（版本、ACL、新鲜度门禁、同步任务）对线上对话零影响——
     * 运营看着后台以为知识库在工作，用户那边一问三不知，且不报任何错。</p>
     *
     * <p>这与数据库里的演示数据是同一类问题的两面：那边有 {@code scripts/clear-demo-data.sh} 可清，
     * 代码里的这份没有任何脚本能处理，只能在这里挡住。</p>
     */
    private void validateKnowledge(List<String> violations) {
        if (!properties.getRag().isEnabled()) {
            return;
        }
        require(violations, "customer-work.rag.provider",
            KnowledgeProviders.isProductionAllowed(properties.getRag().getProvider()));
    }

    private void validateRuntimeConfig(List<String> violations) {
        NacosProperties nacos = properties.getNacos();
        if (!nacos.isRuntimeConfigEnabled()) {
            return;
        }
        require(violations, "customer-work.model.egress.allowed-hosts",
            properties.getModel().getEgress().getAllowedHosts().stream().anyMatch(this::hasText));
        int keyBytes = hasText(nacos.getConfigAesKey())
            ? nacos.getConfigAesKey().getBytes(StandardCharsets.UTF_8).length : 0;
        require(violations, "customer-work.nacos.config-aes-key",
            keyBytes == 16 || keyBytes == 24 || keyBytes == 32);
        require(violations, "customer-work.nacos.runtime-config-signature-required",
            nacos.isRuntimeConfigSignatureRequired());
        requireText(violations, "customer-work.nacos.runtime-config-signing-key-id",
            nacos.getRuntimeConfigSigningKeyId());
        require(violations, "customer-work.nacos.runtime-config-signing-secret",
            isProductionSecret(nacos.getRuntimeConfigSigningSecret())
                && nacos.getRuntimeConfigSigningSecret().getBytes(StandardCharsets.UTF_8).length >= 32);
        require(violations, "customer-work.nacos.server-addr", isRemoteAddress(nacos.getServerAddr()));
        requireText(violations, "customer-work.nacos.namespace", nacos.getNamespace());
        requireText(violations, "customer-work.nacos.group", nacos.getGroup());
        requireText(violations, "customer-work.nacos.runtime-config-data-id", nacos.getRuntimeConfigDataId());
        require(violations, "customer-work.nacos.runtime-config-subscribe-retry-ms",
            nacos.getRuntimeConfigSubscribeRetryMs() > 0L);
        requireText(violations, "customer-work.nacos.username", nacos.getUsername());
        require(violations, "customer-work.nacos.password",
            isProductionSecret(nacos.getPassword()) && !"nacos".equals(nacos.getPassword()));
        require(violations, "customer-work.nacos.runtime-config-ack-url",
            isRemoteEndpoint(nacos.getRuntimeConfigAckUrl()));
        requireSecret(violations, "customer-work.nacos.runtime-config-ack-token",
            nacos.getRuntimeConfigAckToken());
        require(violations, "customer-work.outbox.store-mode",
            !"memory".equalsIgnoreCase(properties.getOutbox().getStoreMode()));
    }

    private void validateNotification(List<String> violations) {
        String endpoint = properties.getNotification().getWebhookUrl();
        require(violations, "customer-work.notification.webhook-url", isProductionEndpoint(endpoint));
        requireSecret(violations, "customer-work.notification.auth-token",
            properties.getNotification().getAuthToken());
    }

    private void requireText(List<String> violations, String key, String value) {
        require(violations, key, hasText(value));
    }

    private void requireSecret(List<String> violations, String key, String value) {
        require(violations, key, isProductionSecret(value));
    }

    private void require(List<String> violations, String key, boolean valid) {
        if (!valid) {
            violations.add(key);
        }
    }

    private boolean validCredentialEntry(Map.Entry<String, String> entry) {
        return isProductionSecret(entry.getKey()) && hasText(entry.getValue());
    }

    private boolean validStructuredCredentials(SecurityProperties.Auth auth) {
        List<SecurityProperties.Credential> credentials = auth.getCredentials();
        if (CollectionUtils.isEmpty(credentials)) {
            return false;
        }
        Set<String> credentialKeys = new HashSet<>();
        Set<String> keyIds = new HashSet<>();
        boolean activeCredentialPresent = false;
        for (SecurityProperties.Credential credential : credentials) {
            if (!wellFormedCredential(credential)) {
                return false;
            }
            String keyId = credential.getKeyId().trim();
            keyIds.add(keyId);
            String uniqueKey = keyId + "\n" + credential.getKeyHash().toLowerCase(Locale.ROOT);
            if (!credentialKeys.add(uniqueKey)) {
                return false;
            }
            long minimumEpoch = minimumEpoch(auth.getMinimumEpochs(), keyId);
            if (minimumEpoch < 0L || credential.getEpoch() < minimumEpoch) {
                return false;
            }
            if (credential.isEnabled()
                && (credential.getExpiresAt() == null || credential.getExpiresAt().isAfter(Instant.now()))) {
                activeCredentialPresent = true;
            }
        }
        if (auth.getMinimumEpochs() != null) {
            for (Map.Entry<String, Long> minimum : auth.getMinimumEpochs().entrySet()) {
                if (!hasText(minimum.getKey()) || minimum.getValue() == null
                    || minimum.getValue() <= 0L || !keyIds.contains(minimum.getKey())) {
                    return false;
                }
            }
        }
        return activeCredentialPresent;
    }

    private boolean wellFormedCredential(SecurityProperties.Credential credential) {
        return credential != null
            && hasText(credential.getKeyId())
            && ApiKeySecretHasher.isSha256Hex(credential.getKeyHash())
            && TenantContext.isValidTenantId(credential.getTenantId())
            && credential.getEpoch() > 0L
            && !CollectionUtils.isEmpty(credential.getScopes())
            && credential.getScopes().stream().anyMatch(this::hasText);
    }

    private long minimumEpoch(Map<String, Long> minimumEpochs, String keyId) {
        if (minimumEpochs == null || minimumEpochs.get(keyId) == null) {
            return 0L;
        }
        return minimumEpochs.get(keyId);
    }

    private boolean isProductionSecret(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase();
        return !normalized.contains("replace") && !normalized.contains("change-me")
            && !normalized.contains("changeme") && !normalized.contains("example")
            && !normalized.startsWith("sk-your-");
    }

    private boolean isProductionEndpoint(String value) {
        if (!hasText(value) || value.toLowerCase().contains("replace")) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && hasText(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isRemoteEndpoint(String value) {
        if (!isProductionEndpoint(value)) {
            return false;
        }
        String host = URI.create(value).getHost();
        return !"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host);
    }

    private boolean isRemoteAddress(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase();
        return !normalized.contains("localhost") && !normalized.contains("127.0.0.1")
            && !normalized.contains("replace");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
