package com.richard.fyoung.customerwork.safety.tenant;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.NacosProperties;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * V65 租户访问快照的 8080 Nacos 消费端。
 *
 * <p>连接参数复用现有 {@code customer-work.nacos.*}，只使用独立 tenant-access dataId；
 * 监听负责低延迟更新，定时回读负责补偿监听丢失与更新快照新鲜度。</p>
 */
@Component
public class TenantAccessNacosService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TenantAccessNacosService.class);

    private final CustomerWorkProperties properties;
    private final TenantAccessSnapshotStore snapshotStore;
    private final ConfigServiceFactory configServiceFactory;
    private final WsSessionRegistry wsSessionRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, String> trackedTenants = new ConcurrentHashMap<>();
    private final Set<String> subscribedTenantKeys = ConcurrentHashMap.newKeySet();

    private volatile ConfigService configService;
    private ThreadPoolTaskScheduler refreshScheduler;

    public TenantAccessNacosService(CustomerWorkProperties properties,
                                    TenantAccessSnapshotStore snapshotStore) {
        this(properties, snapshotStore, NacosFactory::createConfigService, null);
    }

    @Autowired
    public TenantAccessNacosService(CustomerWorkProperties properties,
                                    TenantAccessSnapshotStore snapshotStore,
                                    ObjectProvider<WsSessionRegistry> wsSessionRegistryProvider) {
        this(properties, snapshotStore, NacosFactory::createConfigService,
            wsSessionRegistryProvider.getIfAvailable());
    }

    TenantAccessNacosService(CustomerWorkProperties properties,
                             TenantAccessSnapshotStore snapshotStore,
                             ConfigServiceFactory configServiceFactory) {
        this(properties, snapshotStore, configServiceFactory, null);
    }

    TenantAccessNacosService(CustomerWorkProperties properties,
                             TenantAccessSnapshotStore snapshotStore,
                             ConfigServiceFactory configServiceFactory,
                             WsSessionRegistry wsSessionRegistry) {
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.configServiceFactory = configServiceFactory;
        this.wsSessionRegistry = wsSessionRegistry;
    }

    @PostConstruct
    public void start() {
        NacosProperties nacos = properties.getNacos();
        if (!nacos.isTenantAccessEnabled()) {
            return;
        }
        initialTenants().forEach(this::track);
        refreshScheduler = new ThreadPoolTaskScheduler();
        refreshScheduler.setPoolSize(1);
        refreshScheduler.setThreadNamePrefix("tenant-access-subscribe-");
        refreshScheduler.initialize();
        refreshAllSafely();
        refreshScheduler.scheduleWithFixedDelay(this::refreshAllSafely,
            Duration.ofMillis(requirePositive(nacos.getTenantAccessRefreshIntervalMs(),
                "tenantAccessRefreshIntervalMs")));
        log.info("tenant access snapshot consumer started, tenantCount={}, refreshIntervalMs={}",
            trackedTenants.size(), nacos.getTenantAccessRefreshIntervalMs());
    }

    /** 记录运行时实际出现的租户；下一轮回读会为其建立订阅。 */
    public void track(String tenantId) {
        if (!properties.getNacos().isTenantAccessEnabled()
            || TenantContext.isDefaultTenant(tenantId)
            || !TenantContext.isValidTenantId(tenantId)) {
            return;
        }
        trackedTenants.putIfAbsent(TenantContext.normalizedTenantKey(tenantId),
            TenantContext.canonicalizeTenantId(tenantId));
    }

    /** 同步回读一次；仅供登录/注册等已在 boundedElastic 上执行的入口使用。 */
    public boolean refreshTenant(String tenantId) {
        if (!properties.getNacos().isTenantAccessEnabled() || TenantContext.isDefaultTenant(tenantId)) {
            return true;
        }
        track(tenantId);
        String key = TenantContext.normalizedTenantKey(tenantId);
        String externalTenantId = trackedTenants.get(key);
        if (externalTenantId == null) {
            return false;
        }
        try {
            ConfigService client = configService();
            ensureListener(client, externalTenantId, key);
            NacosProperties nacos = properties.getNacos();
            String json = client.getConfig(dataId(externalTenantId), nacos.getGroup(), nacos.getTimeoutMs());
            if (!StringUtils.hasText(json)) {
                log.error("tenant access snapshot missing, code={}, tenantId={}, dataId={}",
                    "TENANT-ACCESS-SNAPSHOT-MISSING", externalTenantId, dataId(externalTenantId));
                return false;
            }
            return applyConfig(externalTenantId, json);
        } catch (Exception e) {
            log.error("tenant access snapshot refresh failed, code={}, tenantId={}",
                "TENANT-ACCESS-SNAPSHOT-REFRESH-FAIL", externalTenantId, e);
            return false;
        }
    }

    void refreshAllSafely() {
        initialTenants().forEach(this::track);
        for (String tenantId : trackedTenants.values()) {
            if (!refreshTenant(tenantId)) {
                synchronizeWebSocketAccess(tenantId);
            }
        }
    }

    boolean applyConfig(String expectedTenantId, String json) {
        try {
            TenantAccessSnapshot snapshot = objectMapper.readValue(json, TenantAccessSnapshot.class);
            TenantAccessSnapshot previous = snapshotStore.current(expectedTenantId);
            TenantAccessSnapshotStore.ApplyOutcome outcome =
                snapshotStore.apply(expectedTenantId, snapshot, System.currentTimeMillis());
            if (outcome == TenantAccessSnapshotStore.ApplyOutcome.IGNORED_OLDER) {
                log.error("older tenant access snapshot ignored, code={}, tenantId={}, accessEpoch={}",
                    "TENANT-ACCESS-SNAPSHOT-ROLLBACK", expectedTenantId, snapshot.getAccessEpoch());
                return false;
            }
            if (outcome == TenantAccessSnapshotStore.ApplyOutcome.REJECTED
                || outcome == TenantAccessSnapshotStore.ApplyOutcome.REJECTED_CONFLICT) {
                log.error("tenant access snapshot rejected, code={}, tenantId={}, outcome={}",
                    "TENANT-ACCESS-SNAPSHOT-REJECTED", expectedTenantId, outcome);
                return false;
            }
            boolean accessEpochAdvanced = previous != null
                && snapshot.getAccessEpoch() > previous.getAccessEpoch();
            synchronizeWebSocketAccess(expectedTenantId, accessEpochAdvanced);
            return true;
        } catch (Exception e) {
            log.error("tenant access snapshot parse failed, code={}, tenantId={}",
                "TENANT-ACCESS-SNAPSHOT-PARSE-FAIL", expectedTenantId, e);
            return false;
        }
    }

    /**
     * 快照冻结、终止、到期或超过最大失联时间时封锁并断开连接；恢复 ACTIVE 后解除登记封锁。
     * 定时回读无论成功与否都会调用本方法，因此快照删除/失联最终也会按 maxStaleness fail-closed。
     */
    private void synchronizeWebSocketAccess(String tenantId) {
        synchronizeWebSocketAccess(tenantId, false);
    }

    private void synchronizeWebSocketAccess(String tenantId, boolean accessEpochAdvanced) {
        if (wsSessionRegistry == null) {
            return;
        }
        TenantAccessDecision decision = snapshotStore.evaluate(
            tenantId, null, false, System.currentTimeMillis(),
            properties.getNacos().getTenantAccessMaxStalenessMs());
        if (!decision.isAllowed()) {
            wsSessionRegistry.disconnectTenant(tenantId);
        } else if (accessEpochAdvanced) {
            wsSessionRegistry.disconnectTenantSessionsForEpochChange(tenantId);
        } else {
            wsSessionRegistry.allowTenant(tenantId);
        }
    }

    private void ensureListener(ConfigService client, String tenantId, String tenantKey) throws Exception {
        if (subscribedTenantKeys.contains(tenantKey)) {
            return;
        }
        synchronized (subscribedTenantKeys) {
            if (subscribedTenantKeys.contains(tenantKey)) {
                return;
            }
            NacosProperties nacos = properties.getNacos();
            client.addListener(dataId(tenantId), nacos.getGroup(), new Listener() {
                @Override
                public Executor getExecutor() {
                    return Runnable::run;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    if (!StringUtils.hasText(configInfo)) {
                        log.error("tenant access snapshot removed, code={}, tenantId={}",
                            "TENANT-ACCESS-SNAPSHOT-REMOVED", tenantId);
                        return;
                    }
                    applyConfig(tenantId, configInfo);
                }
            });
            subscribedTenantKeys.add(tenantKey);
        }
    }

    private Set<String> initialTenants() {
        Set<String> tenants = new LinkedHashSet<>();
        NacosProperties nacos = properties.getNacos();
        if (StringUtils.hasText(nacos.getTenantCode())) {
            tenants.add(nacos.getTenantCode().trim());
        }
        Map<String, String> tenantKeys = properties.getSecurity().getAuth().getTenantKeys();
        if (tenantKeys != null) {
            tenants.addAll(tenantKeys.values());
        }
        tenants.removeIf(tenant -> tenant == null || TenantContext.isDefaultTenant(tenant));
        return tenants;
    }

    private String dataId(String tenantId) {
        return properties.getNacos().getTenantAccessDataId() + "-tenant-" + tenantId;
    }

    private ConfigService configService() throws Exception {
        ConfigService local = configService;
        if (local == null) {
            synchronized (this) {
                local = configService;
                if (local == null) {
                    local = configServiceFactory.create(buildProperties(properties.getNacos()));
                    configService = local;
                }
            }
        }
        return local;
    }

    private Properties buildProperties(NacosProperties nacos) {
        Properties result = new Properties();
        result.put(PropertyKeyConst.SERVER_ADDR, nacos.getServerAddr());
        if (StringUtils.hasText(nacos.getNamespace())) {
            result.put(PropertyKeyConst.NAMESPACE, nacos.getNamespace());
        }
        if (StringUtils.hasText(nacos.getUsername())) {
            result.put(PropertyKeyConst.USERNAME, nacos.getUsername());
            result.put(PropertyKeyConst.PASSWORD, nacos.getPassword());
        }
        return result;
    }

    private long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public void destroy() {
        if (refreshScheduler != null) {
            refreshScheduler.shutdown();
        }
        ConfigService client = configService;
        if (client != null) {
            try {
                client.shutDown();
            } catch (Exception e) {
                log.error("tenant access Nacos client shutdown failed, code={}",
                    "TENANT-ACCESS-NACOS-SHUTDOWN-FAIL", e);
            }
        }
    }

    @FunctionalInterface
    interface ConfigServiceFactory {
        ConfigService create(Properties properties) throws Exception;
    }
}
