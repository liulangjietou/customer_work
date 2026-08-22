package com.richard.fyoung.customerwork.infra.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.data.outbox.OutboxService;
import com.richard.fyoung.customerwork.safety.tenant.LegacyTenantCompatibility;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import com.richard.fyoung.customerwork.infra.config.properties.NacosProperties;

/**
 * Nacos 配置中心驱动的「运行时配置」热更新服务（消费端，对应 admin 8082 下发链路）。
 *
 * <p>{@code customer-work.nacos.runtime-config-enabled=true} 时：启动拉取一次 + 注册监听器，admin 侧
 * 在 Nacos 修改整份运行时配置（模型/兜底/重试/提示词/MCP/maxIters）后<b>无需重启 8080 即热生效</b>。
 * 解析 JSON → 用 {@link AesGcmDecryptor} 解密 API Key 密文 → 内容摘要变化时先严格失效当前租户语义缓存
 * → 交 {@link RuntimeConfigApplier} 原子应用。缓存失效失败与 JSON/密钥解析失败一样保留旧配置。</p>
 *
 * <p>Nacos 不可用 / 无该配置 / JSON 坏 / 解密失败，均<b>保持 yml 既有行为</b>（不覆盖运行链），
 * 与 {@link NacosPromptService} 的降级语义一致——托管失败不拖垮主链路可用性。默认关闭。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class NacosRuntimeConfigService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NacosRuntimeConfigService.class);

    private static final String CODE_PARSE_FAIL = "RUNTIME-CONFIG-PARSE-FAIL";
    private static final String CODE_HASH_FAIL = "RUNTIME-CONFIG-HASH-FAIL";
    private static final RuntimeConfigCacheInvalidator NO_OP_CACHE_INVALIDATOR =
        new RuntimeConfigCacheInvalidator() {
            @Override
            public void invalidateCurrentTenant() {
            }
        };
    private final CustomerWorkProperties properties;
    private final RuntimeConfigApplier applier;
    private final OutboxService outboxService;
    private final RuntimeConfigCacheInvalidator cacheInvalidator;
    private final ConfigServiceFactory configServiceFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    private volatile AesGcmDecryptor decryptor;
    private volatile ConfigService boundConfigService;
    /** 最近一份真正通过 applier 的配置身份；失败发布不得覆盖。 */
    private volatile String activeRevision = "";
    private volatile String activeContentHash = "";
    private ThreadPoolTaskScheduler retryScheduler;

    public NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier) {
        this(properties, applier, null, NO_OP_CACHE_INVALIDATOR);
    }

    @Autowired
    public NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                                     ObjectProvider<OutboxService> outboxProvider,
                                     ObjectProvider<RuntimeConfigCacheInvalidator> cacheInvalidatorProvider) {
        this(properties, applier,
            outboxProvider == null ? null : outboxProvider.getIfAvailable(),
            cacheInvalidatorProvider == null
                ? NO_OP_CACHE_INVALIDATOR
                : cacheInvalidatorProvider.getIfAvailable(() -> NO_OP_CACHE_INVALIDATOR));
    }

    NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                              OutboxService outboxService) {
        this(properties, applier, outboxService, NO_OP_CACHE_INVALIDATOR);
    }

    NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                              OutboxService outboxService,
                              RuntimeConfigCacheInvalidator cacheInvalidator) {
        this(properties, applier, outboxService, cacheInvalidator,
            nacosProperties -> NacosFactory.createConfigService(nacosProperties));
    }

    NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                              OutboxService outboxService, ConfigServiceFactory configServiceFactory) {
        this(properties, applier, outboxService, NO_OP_CACHE_INVALIDATOR, configServiceFactory);
    }

    NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                              OutboxService outboxService,
                              RuntimeConfigCacheInvalidator cacheInvalidator,
                              ConfigServiceFactory configServiceFactory) {
        this.properties = properties;
        this.applier = applier;
        this.outboxService = outboxService;
        this.cacheInvalidator = cacheInvalidator == null ? NO_OP_CACHE_INVALIDATOR : cacheInvalidator;
        this.configServiceFactory = configServiceFactory;
    }

    @PostConstruct
    public void start() {
        NacosProperties cfg = properties.getNacos();
        if (!cfg.isRuntimeConfigEnabled()) {
            return;
        }
        retryScheduler = new ThreadPoolTaskScheduler();
        retryScheduler.setPoolSize(1);
        retryScheduler.setThreadNamePrefix("runtime-config-subscribe-");
        retryScheduler.initialize();
        attemptSubscription();
        retryScheduler.scheduleWithFixedDelay(this::attemptSubscription,
            Duration.ofMillis(cfg.getRuntimeConfigSubscribeRetryMs()));
    }

    /** 首次连接或后续重试共用的单点；成功后定时任务变为轻量 no-op。 */
    boolean attemptSubscription() {
        if (subscribed.get()) {
            return true;
        }
        NacosProperties cfg = properties.getNacos();
        ConfigService candidate = null;
        try {
            this.decryptor = new AesGcmDecryptor(cfg.getConfigAesKey());
            candidate = configServiceFactory.create(buildProperties(cfg));
            bind(candidate);
            boundConfigService = candidate;
            subscribed.set(true);
            log.info("[Nacos] runtime config hot-update enabled, dataId={}, group={}",
                subscriptionDataId(cfg), cfg.getGroup());
            return true;
        } catch (Exception e) {
            shutdownQuietly(candidate);
            // 保持旧配置继续服务，Nacos 恢复后由 retryScheduler 自动重新建立订阅。
            log.error("runtime config subscribe failed, keep old config and retry, code={}",
                "RUNTIME-CONFIG-SUBSCRIBE-FAIL", e);
            return false;
        }
    }

    /**
     * 绑定到给定 ConfigService：拉取初始配置并注册热更新监听器（抽出以便单测）。
     *
     * <p><b>租户边界</b>：多租户开启或配置了 {@code nacos.tenant-code} 时，只读取并监听
     * {@code <主dataId>-tenant-<租户码>}。租户键缺失或删除只保留当前安全配置，绝不回落主 dataId，
     * 避免一个租户误用另一发布域的模型凭据。只有未开启多租户且未配置租户码的单租户部署读取主 dataId。</p>
     */
    void bind(ConfigService configService) throws NacosException {
        NacosProperties cfg = properties.getNacos();
        String dataId = subscriptionDataId(cfg);
        String initial = configService.getConfig(dataId, cfg.getGroup(), cfg.getTimeoutMs());
        if (!StringUtils.hasText(initial) && tenantScopedMode(cfg)) {
            log.error("tenant runtime config missing, keep last safe config, code={}, dataId={}",
                "RUNTIME-CONFIG-TENANT-MISSING", dataId);
        }
        applyConfig(initial);

        configService.addListener(dataId, cfg.getGroup(), new Listener() {
            @Override
            public Executor getExecutor() {
                return Runnable::run;   // 同步回调，简单可控
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                if (!StringUtils.hasText(configInfo) && tenantScopedMode(cfg)) {
                    log.error("tenant runtime config removed, keep last safe config, code={}, dataId={}",
                        "RUNTIME-CONFIG-TENANT-REMOVED", dataId);
                    return;
                }
                applyConfig(configInfo);
            }
        });
    }

    private String subscriptionDataId(NacosProperties cfg) {
        if (!tenantScopedMode(cfg)) {
            return cfg.getRuntimeConfigDataId();
        }
        String tenantDataId = tenantDataId(cfg);
        if (tenantDataId == null) {
            throw new IllegalStateException("nacos.tenant-code is required in multi-tenant mode");
        }
        return tenantDataId;
    }

    private boolean tenantScopedMode(NacosProperties cfg) {
        return properties.getTenant().isEnabled() || configuredTenantCode(cfg) != null;
    }

    /** 租户专属 dataId；未配租户码时返回 null（单租户部署不受灰度机制影响）。 */
    private String tenantDataId(NacosProperties cfg) {
        String tenantCode = configuredTenantCode(cfg);
        if (tenantCode == null) {
            return null;
        }
        return cfg.getRuntimeConfigDataId() + "-tenant-" + tenantCode;
    }

    /** 兼容旧部署环境变量；运行期只暴露 default，不让历史字面量重新进入上下文或 dataId。 */
    private String configuredTenantCode(NacosProperties cfg) {
        String tenantCode = cfg.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        String trimmed = tenantCode.trim();
        return LegacyTenantCompatibility.PLATFORM_TENANT_ID.equals(trimmed)
            ? TenantContext.DEFAULT : TenantContext.canonicalizeTenantId(trimmed);
    }

    /**
     * 解析并应用一份运行时配置 JSON。包内可见以便单测直接驱动（不依赖真实 Nacos）。
     *
     * <p>坏 JSON、解密失败或内容摘要校验失败均在此收口——记 error 后直接返回，绝不清缓存或调用
     * applier，保证旧配置不被覆盖。</p>
     *
     * @param json Nacos 配置正文；空白视为「无配置」直接跳过
     * @return 是否成功应用
     */
    synchronized boolean applyConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return false;
        }
        CustomerWorkRuntimeConfig dto;
        try {
            dto = objectMapper.readValue(json, CustomerWorkRuntimeConfig.class);
        } catch (Exception e) {
            log.error("runtime config json parse failed, keep old config, code={}", CODE_PARSE_FAIL, e);
            enqueueAck(extractRevision(json), extractContentHash(json), "REJECTED",
                "runtime config JSON parse failed");
            return false;
        }
        String primaryKey;
        String fallbackKey;
        Map<Long, String> routingKeys;
        Map<Long, String> experimentKeys;
        try {
            primaryKey = decryptIfPresent(dto.getModel() == null ? null : dto.getModel().getApiKeyCipher());
            fallbackKey = decryptIfPresent(dto.getFallback() == null ? null : dto.getFallback().getApiKeyCipher());
            routingKeys = decryptRoutingKeys(dto.getRoutingPolicy());
            experimentKeys = decryptExperimentKeys(dto.getOnlineExperiment());
        } catch (Exception e) {
            log.error("runtime config api key decrypt failed, keep old config, code={}",
                "RUNTIME-CONFIG-DECRYPT-FAIL", e);
            enqueueAck(dto.getRevision(), dto.getContentHash(), "REJECTED",
                "runtime config API key decrypt failed");
            return false;
        }
        String verifiedContentHash;
        try {
            verifiedContentHash = RuntimeConfigContentHasher.compute(dto, objectMapper);
        } catch (Exception e) {
            log.error("runtime config content hash calculation failed, keep old config, code={}",
                CODE_HASH_FAIL, e);
            enqueueAck(dto.getRevision(), dto.getContentHash(), "REJECTED",
                "runtime config content hash calculation failed");
            return false;
        }
        String declaredContentHash = dto.getContentHash();
        if (!RuntimeConfigContentHasher.isValidFormat(declaredContentHash)) {
            log.error("runtime config content hash is missing or malformed, keep old config, code={}, revision={}",
                CODE_HASH_FAIL, dto.getRevision());
            enqueueAck(dto.getRevision(), verifiedContentHash, "REJECTED",
                "runtime config content hash is missing or malformed");
            return false;
        }
        if (!verifiedContentHash.equalsIgnoreCase(declaredContentHash)) {
            log.error("runtime config content hash mismatch, keep old config, code={}, revision={}",
                CODE_HASH_FAIL, dto.getRevision());
            enqueueAck(dto.getRevision(), declaredContentHash, "REJECTED",
                "runtime config content hash mismatch");
            return false;
        }
        boolean generationChanged = !verifiedContentHash.equals(activeContentHash);
        if (generationChanged) {
            try {
                beginSemanticCacheTransition(verifiedContentHash);
            } catch (Exception e) {
                log.error("runtime config semantic cache invalidation failed, keep old config, code={}, "
                        + "contentHash={}",
                    "RUNTIME-CONFIG-CACHE-INVALIDATE-FAIL", verifiedContentHash, e);
                enqueueAck(dto.getRevision(), verifiedContentHash, "REJECTED",
                    "runtime config semantic cache invalidation failed");
                return false;
            }
        }
        boolean applied = dto.getRoutingPolicy() == null && dto.getOnlineExperiment() == null
            ? applier.apply(dto, primaryKey, fallbackKey)
            : applier.apply(dto, primaryKey, fallbackKey, routingKeys, experimentKeys);
        if (generationChanged) {
            finishSemanticCacheTransition(verifiedContentHash, applied);
        }
        if (applied) {
            activeRevision = normalize(dto.getRevision());
            activeContentHash = verifiedContentHash;
        }
        enqueueAck(dto.getRevision(), verifiedContentHash, applied ? "APPLIED" : "REJECTED",
            applied ? null : "runtime config applier rejected configuration");
        return applied;
    }

    /** 路由候选密钥必须全部先解密成功，之后才允许清缓存与切换模型链。 */
    private Map<Long, String> decryptRoutingKeys(CustomerWorkRuntimeConfig.RoutingPolicy policy) {
        if (policy == null || policy.getDeployments() == null) {
            return Map.of();
        }
        Map<Long, String> keys = new LinkedHashMap<>();
        for (CustomerWorkRuntimeConfig.RoutingDeployment deployment : policy.getDeployments()) {
            if (deployment == null || deployment.getDeploymentId() == null) {
                throw new IllegalArgumentException("runtime routing deployment id is missing");
            }
            if (keys.containsKey(deployment.getDeploymentId())) {
                throw new IllegalArgumentException(
                    "runtime routing deployment is duplicated: " + deployment.getDeploymentId());
            }
            keys.put(deployment.getDeploymentId(), decryptIfPresent(deployment.getApiKeyCipher()));
        }
        return keys;
    }

    /** 双臂凭据也必须全部先解密成功，任何一臂失败都不能清缓存或切流量。 */
    private Map<Long, String> decryptExperimentKeys(
        CustomerWorkRuntimeConfig.OnlineExperiment experiment) {
        if (experiment == null) {
            return Map.of();
        }
        if (experiment.getControl() == null || experiment.getTreatment() == null) {
            throw new IllegalArgumentException("runtime online experiment arms are missing");
        }
        Map<Long, String> keys = new LinkedHashMap<>();
        decryptExperimentArm(experiment.getControl(), keys);
        decryptExperimentArm(experiment.getTreatment(), keys);
        return keys;
    }

    private void decryptExperimentArm(CustomerWorkRuntimeConfig.ExperimentArm arm,
                                      Map<Long, String> keys) {
        if (arm.getDeploymentId() == null) {
            throw new IllegalArgumentException("runtime online experiment deployment id is missing");
        }
        if (keys.containsKey(arm.getDeploymentId())) {
            throw new IllegalArgumentException(
                "runtime online experiment deployment is duplicated: " + arm.getDeploymentId());
        }
        keys.put(arm.getDeploymentId(), decryptIfPresent(arm.getApiKeyCipher()));
    }

    /** 在当前部署租户上下文中先阻断缓存读写并清理旧代际。 */
    private void beginSemanticCacheTransition(String nextContentHash) {
        String configuredTenant = configuredTenantCode(properties.getNacos());
        String tenant = configuredTenant == null ? TenantContext.DEFAULT : configuredTenant;
        TenantContext.runWith(tenant, () -> cacheInvalidator.beginTransition(nextContentHash));
    }

    /** 应用成功才提交新 contentHash 代际；失败则恢复旧代际，候选配置期间不产生缓存写入。 */
    private void finishSemanticCacheTransition(String nextContentHash, boolean applied) {
        String configuredTenant = configuredTenantCode(properties.getNacos());
        String tenant = configuredTenant == null ? TenantContext.DEFAULT : configuredTenant;
        TenantContext.runWith(tenant, () -> {
            if (applied) {
                cacheInvalidator.commitTransition(nextContentHash);
            } else {
                cacheInvalidator.rollbackTransition(nextContentHash);
            }
        });
    }

    /** 当前实例最后成功应用的发布修订；仅 yml 启动、尚未接收发布时为空。 */
    public String activeRevision() {
        return activeRevision;
    }

    /** 当前实例最后成功应用的发布内容摘要；失败配置不会推进该值。 */
    public String activeContentHash() {
        return activeContentHash;
    }

    private void enqueueAck(String revision, String contentHash, String status, String reason) {
        NacosProperties nacos = properties.getNacos();
        if (!StringUtils.hasText(revision) || !StringUtils.hasText(nacos.getRuntimeConfigAckUrl())
            || outboxService == null) {
            return;
        }
        try {
            RuntimeConfigAck ack = new RuntimeConfigAck(revision, contentHash, resolveInstanceId(nacos),
                status, reason, System.currentTimeMillis());
            String payload = objectMapper.writeValueAsString(ack);
            String configuredTenant = configuredTenantCode(nacos);
            String tenant = configuredTenant == null ? TenantContext.DEFAULT : configuredTenant;
            TenantContext.runWith(tenant, () ->
                outboxService.publish(RuntimeConfigAckOutboxHandler.TYPE, revision, payload));
        } catch (Exception e) {
            log.error("enqueue runtime config ACK failed, code={}, revision={}",
                "RUNTIME-CONFIG-ACK-ENQUEUE-FAIL", revision, e);
        }
    }

    private String resolveInstanceId(NacosProperties nacos) {
        if (StringUtils.hasText(nacos.getRuntimeConfigInstanceId())) {
            return nacos.getRuntimeConfigInstanceId().trim();
        }
        String hostname = System.getenv("HOSTNAME");
        return StringUtils.hasText(hostname) ? hostname : ManagementFactory.getRuntimeMXBean().getName();
    }

    private String extractRevision(String json) {
        return extractText(json, "revision");
    }

    private String extractContentHash(String json) {
        return extractText(json, "contentHash");
    }

    private String extractText(String json, String field) {
        try {
            return objectMapper.readTree(json).path(field).asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /** 密文非空则解密，空则返回 null（表示不改动现有密钥）。 */
    private String decryptIfPresent(String cipher) {
        if (!StringUtils.hasText(cipher)) {
            return null;
        }
        return requireDecryptor().decrypt(cipher);
    }

    private AesGcmDecryptor requireDecryptor() {
        AesGcmDecryptor local = this.decryptor;
        if (local == null) {
            // 单测直接调 applyConfig 未走 start() 时按配置懒建
            local = new AesGcmDecryptor(properties.getNacos().getConfigAesKey());
            this.decryptor = local;
        }
        return local;
    }

    private Properties buildProperties(NacosProperties cfg) {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, cfg.getServerAddr());
        if (StringUtils.hasText(cfg.getNamespace())) {
            props.put(PropertyKeyConst.NAMESPACE, cfg.getNamespace());
        }
        if (StringUtils.hasText(cfg.getUsername())) {
            props.put(PropertyKeyConst.USERNAME, cfg.getUsername());
            props.put(PropertyKeyConst.PASSWORD, cfg.getPassword());
        }
        return props;
    }

    @Override
    public void destroy() {
        if (retryScheduler != null) {
            retryScheduler.shutdown();
        }
        shutdownQuietly(boundConfigService);
    }

    private void shutdownQuietly(ConfigService configService) {
        if (configService == null) {
            return;
        }
        try {
            configService.shutDown();
        } catch (Exception e) {
            log.error("runtime config Nacos client shutdown failed, code={}",
                "RUNTIME-CONFIG-NACOS-SHUTDOWN-FAIL", e);
        }
    }

    @FunctionalInterface
    interface ConfigServiceFactory {
        ConfigService create(Properties properties) throws Exception;
    }
}
