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
 * 解析 JSON → 用 {@link AesGcmDecryptor} 解密 API Key 密文 → 交 {@link RuntimeConfigApplier} 原子应用。</p>
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
    private final CustomerWorkProperties properties;
    private final RuntimeConfigApplier applier;
    private final OutboxService outboxService;
    private final ConfigServiceFactory configServiceFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    private volatile AesGcmDecryptor decryptor;
    private volatile ConfigService boundConfigService;
    private ThreadPoolTaskScheduler retryScheduler;

    public NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier) {
        this(properties, applier, (OutboxService) null);
    }

    @Autowired
    public NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                                     ObjectProvider<OutboxService> outboxProvider) {
        this(properties, applier, outboxProvider == null ? null : outboxProvider.getIfAvailable());
    }

    NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                              OutboxService outboxService) {
        this(properties, applier, outboxService,
            nacosProperties -> NacosFactory.createConfigService(nacosProperties));
    }

    NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier,
                              OutboxService outboxService, ConfigServiceFactory configServiceFactory) {
        this.properties = properties;
        this.applier = applier;
        this.outboxService = outboxService;
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
                cfg.getRuntimeConfigDataId(), cfg.getGroup());
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
     * <p><b>灰度优先</b>：配了 {@code nacos.tenant-code} 时先看租户专属 dataId
     * （{@code <主dataId>-tenant-<租户码>}），有内容就用它，没有才回落主 dataId。
     * 灰度因此对本端是透明的——本端并不理解"灰度"，只是多试了一个更具体的 dataId；
     * 运营方把灰度版本写进那个 dataId，名单外的实例自然继续用主 dataId 上的全量版本。</p>
     *
     * <p>两个 dataId 都要挂监听：灰度期间运营方可能改灰度版本，灰度结束后又会删掉它——
     * 只听一个的话，要么灰度更新收不到，要么灰度撤销后回不到全量版本。</p>
     */
    void bind(ConfigService configService) throws NacosException {
        NacosProperties cfg = properties.getNacos();
        String mainDataId = cfg.getRuntimeConfigDataId();
        String tenantDataId = tenantDataId(cfg);

        String initial = null;
        if (tenantDataId != null) {
            initial = configService.getConfig(tenantDataId, cfg.getGroup(), cfg.getTimeoutMs());
            if (StringUtils.hasText(initial)) {
                log.info("[Nacos] gray config applied, dataId={}", tenantDataId);
            }
        }
        if (!StringUtils.hasText(initial)) {
            String legacyDataId = legacyDefaultTenantDataId(cfg);
            if (legacyDataId != null) {
                initial = configService.getConfig(legacyDataId, cfg.getGroup(), cfg.getTimeoutMs());
                if (StringUtils.hasText(initial)) {
                    // 读旧值后再次确认 canonical key，避免并发发布的新配置被本次启动误用旧值遮住。
                    String canonical = configService.getConfig(tenantDataId, cfg.getGroup(), cfg.getTimeoutMs());
                    if (StringUtils.hasText(canonical)) {
                        initial = canonical;
                    } else {
                        log.info("[Nacos] legacy platform runtime config used as default compatibility fallback");
                    }
                }
            }
        }
        if (!StringUtils.hasText(initial)) {
            initial = configService.getConfig(mainDataId, cfg.getGroup(), cfg.getTimeoutMs());
        }
        applyConfig(initial);

        configService.addListener(mainDataId, cfg.getGroup(), new Listener() {
            @Override
            public Executor getExecutor() {
                return Runnable::run;   // 同步回调，简单可控
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                applyConfig(configInfo);
            }
        });

        if (tenantDataId != null) {
            configService.addListener(tenantDataId, cfg.getGroup(), new Listener() {
                @Override
                public Executor getExecutor() {
                    return Runnable::run;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    // 灰度被撤销时 Nacos 回调的是空串：此时不 applyConfig（那会被当成"无配置"跳过），
                    // 而是主动回读主 dataId 恢复全量版本，否则实例会一直停在灰度版本上
                    if (StringUtils.hasText(configInfo)) {
                        applyConfig(configInfo);
                        return;
                    }
                    restoreFromMainDataId(configService, cfg);
                }
            });
        }
    }

    /** 灰度撤销后回到全量版本。读取失败只记日志——保持当前配置总比清空好。 */
    private void restoreFromMainDataId(ConfigService configService, NacosProperties cfg) {
        try {
            String main = configService.getConfig(cfg.getRuntimeConfigDataId(), cfg.getGroup(), cfg.getTimeoutMs());
            if (StringUtils.hasText(main)) {
                applyConfig(main);
                log.info("[Nacos] gray config removed, restored from main dataId={}", cfg.getRuntimeConfigDataId());
            }
        } catch (Exception e) {
            log.error("restore runtime config from main dataId failed, code={}",
                "RUNTIME-CONFIG-RESTORE-FAIL", e);
        }
    }

    /** 租户专属 dataId；未配租户码时返回 null（单租户部署不受灰度机制影响）。 */
    private String tenantDataId(NacosProperties cfg) {
        String tenantCode = configuredTenantCode(cfg);
        if (tenantCode == null) {
            return null;
        }
        return cfg.getRuntimeConfigDataId() + "-tenant-" + tenantCode;
    }

    /** 仅 default 实例兼容读取旧平台 dataId；业务租户永不进入此分支。 */
    private String legacyDefaultTenantDataId(NacosProperties cfg) {
        return TenantContext.isDefaultTenant(configuredTenantCode(cfg))
            ? cfg.getRuntimeConfigDataId() + "-tenant-" + LegacyTenantCompatibility.PLATFORM_TENANT_ID
            : null;
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
     * <p>坏 JSON / 解密失败在此收口——记 error 后直接返回，绝不调用 applier，保证旧配置不被覆盖。</p>
     *
     * @param json Nacos 配置正文；空白视为「无配置」直接跳过
     * @return 是否成功应用
     */
    boolean applyConfig(String json) {
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
        try {
            primaryKey = decryptIfPresent(dto.getModel() == null ? null : dto.getModel().getApiKeyCipher());
            fallbackKey = decryptIfPresent(dto.getFallback() == null ? null : dto.getFallback().getApiKeyCipher());
        } catch (Exception e) {
            log.error("runtime config api key decrypt failed, keep old config, code={}",
                "RUNTIME-CONFIG-DECRYPT-FAIL", e);
            enqueueAck(dto.getRevision(), dto.getContentHash(), "REJECTED",
                "runtime config API key decrypt failed");
            return false;
        }
        boolean applied = applier.apply(dto, primaryKey, fallbackKey);
        enqueueAck(dto.getRevision(), dto.getContentHash(), applied ? "APPLIED" : "REJECTED",
            applied ? null : "runtime config applier rejected configuration");
        return applied;
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
